//! Owns the core child process and the JSON-RPC conversation with it.
//!
//! A port of `desktop/src/main/core.ts`: one JSON object per line on stdin/stdout, stderr is
//! diagnostics, a 60s answer deadline for everything but sends and runs, and a backoff restart
//! after a crash so one failure does not brick the session.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Serialize;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{oneshot, watch};

const RESPONSE_TIMEOUT: Duration = Duration::from_secs(60);
/// Their duration is the user's: a send runs to its own `timeoutMs` or Cancel.
const UNBOUNDED_METHODS: [&str; 2] = ["http.send", "run.collection"];
const RESTART_BASE_MS: u64 = 500;
const RESTART_MAX_MS: u64 = 10_000;

/// A core failure as the renderer sees it. `code` is null when it was not a JSON-RPC error.
#[derive(Debug, Clone, Serialize)]
pub struct RpcError {
    pub code: Option<i64>,
    pub message: String,
}

impl RpcError {
    pub fn shell(message: impl Into<String>) -> Self {
        RpcError { code: None, message: message.into() }
    }
}

#[derive(Clone, Debug, PartialEq)]
enum State {
    Starting,
    Ready,
    Failed(String),
}

type Reply = oneshot::Sender<Result<Value, RpcError>>;

struct Inner {
    app: AppHandle,
    stdin: Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    pending: Mutex<HashMap<u64, Reply>>,
    next_id: AtomicU64,
    state: watch::Sender<State>,
    stopped: AtomicBool,
    restarting: AtomicBool,
    attempts: AtomicU32,
    /// Bumped on every spawn, so a dead child's reader cannot fail its successor.
    generation: AtomicU64,
}

#[derive(Clone)]
pub struct Core {
    inner: Arc<Inner>,
}

/// The development JVM launcher in debug builds, the bundled native image in release ones.
/// `PING_CORE_BIN` overrides either, for testing one against the other.
fn resolve_binary(app: &AppHandle) -> Result<PathBuf, String> {
    if let Ok(path) = std::env::var("PING_CORE_BIN") {
        return Ok(PathBuf::from(path));
    }
    let windows = cfg!(windows);
    let path = if cfg!(debug_assertions) {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../core/build/install/ping-core/bin")
            .join(if windows { "ping-core.bat" } else { "ping-core" })
    } else {
        app.path()
            .resource_dir()
            .map_err(|e| e.to_string())?
            .join("core")
            .join(if windows { "ping-core.exe" } else { "ping-core" })
    };
    if path.exists() {
        Ok(path)
    } else if cfg!(debug_assertions) {
        Err(format!("Core binary not found at {}. Run './gradlew :core:installDist' first.", path.display()))
    } else {
        Err(format!("Core binary not found at {}. The installation is incomplete.", path.display()))
    }
}

fn log(line: &str) {
    // The spike logs to stderr; porting `main/log.ts` (redaction, Logs view, ping.log) is phase 30b.
    eprintln!("[core] {line}");
}

impl Core {
    pub fn new(app: AppHandle) -> Self {
        let (state, _) = watch::channel(State::Starting);
        Core {
            inner: Arc::new(Inner {
                app,
                stdin: Mutex::new(None),
                child: Mutex::new(None),
                pending: Mutex::new(HashMap::new()),
                next_id: AtomicU64::new(1),
                state,
                stopped: AtomicBool::new(false),
                restarting: AtomicBool::new(false),
                attempts: AtomicU32::new(0),
                generation: AtomicU64::new(0),
            }),
        }
    }

    pub fn start(&self) {
        self.inner.stopped.store(false, Ordering::SeqCst);
        self.spawn();
    }

    fn spawn(&self) {
        let generation = self.inner.generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.inner.state.send_replace(State::Starting);
        let binary = match resolve_binary(&self.inner.app) {
            Ok(binary) => binary,
            Err(message) => return self.fail(generation, message),
        };
        log(&format!("starting {}", binary.display()));

        let batch = cfg!(windows)
            && binary.extension().is_some_and(|e| e.eq_ignore_ascii_case("bat") || e.eq_ignore_ascii_case("cmd"));
        let mut command = if batch {
            let mut c = Command::new("cmd");
            c.arg("/c").arg(&binary);
            c
        } else {
            Command::new(&binary)
        };
        command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: no console flashing over the app.
        }

        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(error) => return self.fail(generation, format!("Core failed to start: {error}")),
        };
        let stdout = child.stdout.take().expect("piped stdout");
        let stderr = child.stderr.take().expect("piped stderr");
        *self.inner.stdin.lock().unwrap() = child.stdin.take();
        *self.inner.child.lock().unwrap() = Some(child);
        self.emit_state("starting");

        std::thread::spawn(move || {
            for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                log(&line);
            }
        });

        let core = self.clone();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                match line {
                    Ok(line) if line.trim().is_empty() => {}
                    Ok(line) => core.dispatch(&line),
                    Err(_) => break,
                }
            }
            let status = core.inner.child.lock().unwrap().take().and_then(|mut c| c.wait().ok());
            let code = status.and_then(|s| s.code()).map_or("null".into(), |c| c.to_string());
            core.fail(generation, format!("Core exited (code={code})"));
        });
    }

    fn emit_state(&self, state: &str) {
        let _ = self.inner.app.emit("core:state", state);
    }

    fn dispatch(&self, line: &str) {
        let message: Value = match serde_json::from_str(line) {
            Ok(message) => message,
            Err(_) => return log(&format!("unparseable line: {line}")),
        };

        let Some(id) = message.get("id").and_then(Value::as_u64) else {
            let method = message.get("method").and_then(Value::as_str).unwrap_or_default();
            if method == "core.ready" {
                log("ready");
                self.inner.attempts.store(0, Ordering::SeqCst);
                self.inner.state.send_replace(State::Ready);
                self.emit_state("ready");
            }
            let params = message.get("params").cloned().unwrap_or(Value::Null);
            let _ = self.inner.app.emit("core:notification", json!({ "method": method, "params": params }));
            return;
        };

        let Some(reply) = self.inner.pending.lock().unwrap().remove(&id) else {
            return log(&format!("response for unknown id {id}"));
        };
        let outcome = match message.get("error") {
            Some(error) => Err(RpcError {
                code: error.get("code").and_then(Value::as_i64),
                message: error.get("message").and_then(Value::as_str).unwrap_or("Core error").to_string(),
            }),
            None => Ok(message.get("result").cloned().unwrap_or(Value::Null)),
        };
        let _ = reply.send(outcome);
    }

    fn write(&self, message: &Value) -> Result<(), RpcError> {
        let mut stdin = self.inner.stdin.lock().unwrap();
        let pipe = stdin.as_mut().ok_or_else(|| RpcError::shell("Core is not running"))?;
        let mut line = message.to_string();
        line.push('\n');
        pipe.write_all(line.as_bytes())
            .and_then(|_| pipe.flush())
            .map_err(|e| RpcError::shell(format!("Core is not running: {e}")))
    }

    /// Waits until the core has announced `core.ready`, or reports why it never will.
    pub async fn ready(&self) -> Result<(), RpcError> {
        let mut state = self.inner.state.subscribe();
        loop {
            match state.borrow_and_update().clone() {
                State::Ready => return Ok(()),
                State::Failed(message) if !self.inner.restarting.load(Ordering::SeqCst) => {
                    return Err(RpcError::shell(message))
                }
                _ => {}
            }
            if state.changed().await.is_err() {
                return Err(RpcError::shell("Core is not running"));
            }
        }
    }

    pub async fn request(&self, method: &str, params: Value) -> Result<Value, RpcError> {
        let id = self.inner.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = oneshot::channel();
        self.inner.pending.lock().unwrap().insert(id, tx);
        if let Err(error) = self.write(&json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params })) {
            self.inner.pending.lock().unwrap().remove(&id);
            return Err(error);
        }

        let closed = || RpcError::shell("Core is not running");
        if UNBOUNDED_METHODS.contains(&method) {
            return rx.await.unwrap_or_else(|_| Err(closed()));
        }
        match tokio::time::timeout(RESPONSE_TIMEOUT, rx).await {
            Ok(outcome) => outcome.unwrap_or_else(|_| Err(closed())),
            Err(_) => {
                self.inner.pending.lock().unwrap().remove(&id);
                // Without this the core finishes a slow handler nobody is waiting for.
                let _ = self.write(&json!({ "jsonrpc": "2.0", "method": "http.cancel", "params": { "requestId": id } }));
                Err(RpcError::shell(format!(
                    "The core did not answer {method} within {}ms",
                    RESPONSE_TIMEOUT.as_millis()
                )))
            }
        }
    }

    /// Closing stdin ends the core's read loop, which is how it learns to shut down.
    pub fn stop(&self) {
        self.inner.stopped.store(true, Ordering::SeqCst);
        self.inner.stdin.lock().unwrap().take();
    }

    /// A dead core cannot answer anything, so every in-flight call fails now rather than hanging.
    fn fail(&self, generation: u64, message: String) {
        if self.inner.generation.load(Ordering::SeqCst) != generation {
            return;
        }
        let stopped = self.inner.stopped.load(Ordering::SeqCst);
        if !stopped {
            log(&message);
        }
        self.inner.stdin.lock().unwrap().take();
        for (_, reply) in self.inner.pending.lock().unwrap().drain() {
            let _ = reply.send(Err(RpcError::shell(message.clone())));
        }
        if stopped || self.inner.restarting.swap(true, Ordering::SeqCst) {
            self.inner.state.send_replace(State::Failed(message));
            return;
        }
        self.inner.state.send_replace(State::Failed(message));
        self.emit_state("down");

        let attempts = self.inner.attempts.fetch_add(1, Ordering::SeqCst);
        let delay = (RESTART_BASE_MS << attempts.min(16)).min(RESTART_MAX_MS);
        let core = self.clone();
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(delay));
            core.inner.restarting.store(false, Ordering::SeqCst);
            if core.inner.stopped.load(Ordering::SeqCst) {
                return;
            }
            log(&format!("restarting (attempt {})", attempts + 1));
            core.spawn();
        });
    }
}
