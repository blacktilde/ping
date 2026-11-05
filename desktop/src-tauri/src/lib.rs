//! The Tauri shell: the same job as `desktop/src/main`, in Rust.
//!
//! Spike scope (docs/PLAN.md phase 30a): the core process, `core:request` with the shell's
//! guards, the workspace folder, captured runtime values and the cookie jar. Everything else the
//! renderer calls is stubbed in `desktop/src/tauri/bridge.ts` until it is ported.

mod core;
mod guard;

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Mutex;

use serde_json::{json, Value};
use tauri::{AppHandle, Manager, RunEvent, State};
use tauri_plugin_dialog::DialogExt;

use crate::core::{Core, RpcError};

#[derive(Default)]
struct Shell {
    root: Mutex<Option<String>>,
    /// Values captured from responses, for the session. The renderer learns names, never values.
    runtime: Mutex<BTreeMap<String, String>>,
}

impl Shell {
    fn root(&self) -> Option<String> {
        self.root.lock().unwrap().clone()
    }
}

fn envelope(outcome: Result<Value, RpcError>) -> Value {
    match outcome {
        Ok(value) => json!({ "ok": true, "value": value }),
        Err(error) => json!({ "ok": false, "error": error }),
    }
}

fn state_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|dir| dir.join("workspace.json"))
}

fn adopt(app: &AppHandle, shell: &Shell, root: String) {
    if let Some(path) = state_path(app) {
        let _ = std::fs::create_dir_all(path.parent().unwrap());
        // Written beside and renamed over, so a crash mid-write cannot leave half a file.
        let temp = path.with_extension("json.tmp");
        if std::fs::write(&temp, json!({ "root": root }).to_string()).is_ok() {
            let _ = std::fs::rename(&temp, &path);
        }
    }
    *shell.root.lock().unwrap() = Some(root);
}

fn restore(app: &AppHandle, shell: &Shell) {
    if let Ok(root) = std::env::var("PING_WORKSPACE") {
        *shell.root.lock().unwrap() = Some(root);
        return;
    }
    let saved = state_path(app)
        .and_then(|path| std::fs::read_to_string(path).ok())
        .and_then(|text| serde_json::from_str::<Value>(&text).ok())
        .and_then(|value| value.get("root").and_then(Value::as_str).map(str::to_string))
        .filter(|root| !root.is_empty());
    *shell.root.lock().unwrap() = saved;
}

/// First run: a folder and a starter collection instead of an empty sidebar.
async fn seed(app: &AppHandle, core: &Core, shell: &Shell) {
    let Ok(home) = app.path().home_dir() else { return };
    let root = home.join("Ping").to_string_lossy().into_owned();
    let made = match core.ready().await {
        Ok(()) => core.request("store.scaffold", json!({ "root": root, "collection": "My Collection" })).await,
        Err(error) => Err(error),
    };
    match made {
        Ok(_) => adopt(app, shell, root),
        Err(error) => eprintln!("[workspace] could not create a starter collection: {}", error.message),
    }
}

#[tauri::command]
async fn core_request(core: State<'_, Core>, shell: State<'_, Shell>, method: String, params: Option<Value>) -> Result<Value, ()> {
    let params = params.unwrap_or(Value::Null);
    let root = shell.root();
    // No file dialog exists yet, so no absolute path has been granted; network settings are not ported.
    let mut args = match guard::prepare(&method, &params, root.as_deref(), None, &|_| false) {
        Ok(args) => args,
        Err(message) => return Ok(envelope(Err(RpcError::shell(message)))),
    };
    if method == "http.send" || method == "auth.authorize" {
        // Runtime values outrank the files' variables. Secrets (not ported yet) will merge after them.
        let runtime = shell.runtime.lock().unwrap().clone();
        if let Value::Object(map) = &mut args {
            let mut variables = map.get("variables").and_then(Value::as_object).cloned().unwrap_or_default();
            variables.extend(runtime.into_iter().map(|(k, v)| (k, Value::String(v))));
            map.insert("variables".into(), Value::Object(variables));
        }
    }

    let outcome = match core.ready().await {
        Ok(()) => core.request(&method, args).await,
        Err(error) => Err(error),
    };
    let outcome = outcome.map(|value| {
        if method != "http.send" {
            return value;
        }
        let (stripped, kept) = guard::absorb_captures(value);
        shell.runtime.lock().unwrap().extend(kept);
        stripped
    });
    Ok(envelope(outcome))
}

#[tauri::command]
fn workspace_current(shell: State<'_, Shell>) -> Option<Value> {
    shell.root().map(|root| json!({ "root": root }))
}

#[tauri::command]
async fn workspace_choose(app: AppHandle, shell: State<'_, Shell>) -> Result<Option<Value>, ()> {
    let picked = app.dialog().file().blocking_pick_folder();
    if let Some(path) = picked.and_then(|p| p.into_path().ok()) {
        adopt(&app, &shell, path.to_string_lossy().into_owned());
    }
    Ok(shell.root().map(|root| json!({ "root": root })))
}

#[tauri::command]
fn runtime_list(shell: State<'_, Shell>) -> Vec<String> {
    shell.runtime.lock().unwrap().keys().cloned().collect()
}

#[tauri::command]
fn runtime_clear(shell: State<'_, Shell>) {
    shell.runtime.lock().unwrap().clear();
}

fn scope_from(shell: &Shell, collection: &str, environment: &str) -> Option<String> {
    let valid = |value: &str| if !value.is_empty() && guard::is_relative_path(value) { value.to_string() } else { String::new() };
    shell.root().map(|root| guard::cookie_scope(&root, &valid(collection), &valid(environment)))
}

#[tauri::command]
async fn cookies_list(core: State<'_, Core>, shell: State<'_, Shell>, collection: String, environment: String) -> Result<Value, String> {
    let Some(scope) = scope_from(&shell, &collection, &environment) else { return Ok(json!([])) };
    core.ready().await.map_err(|e| e.message)?;
    let result = core.request("cookies.list", json!({ "scope": scope })).await.map_err(|e| e.message)?;
    Ok(result.get("cookies").cloned().unwrap_or(json!([])))
}

#[tauri::command]
async fn cookies_clear(
    core: State<'_, Core>,
    shell: State<'_, Shell>,
    collection: String,
    environment: String,
    domain: Option<String>,
    name: Option<String>,
) -> Result<u64, String> {
    let Some(scope) = scope_from(&shell, &collection, &environment) else { return Ok(0) };
    core.ready().await.map_err(|e| e.message)?;
    let result = core
        .request("cookies.clear", json!({ "scope": scope, "domain": domain, "name": name }))
        .await
        .map_err(|e| e.message)?;
    Ok(result.get("removed").and_then(Value::as_u64).unwrap_or(0))
}

pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(Shell::default())
        .setup(|app| {
            let handle = app.handle().clone();
            let core = Core::new(handle.clone());
            core.start();
            app.manage(core.clone());
            let shell = app.state::<Shell>();
            restore(&handle, &shell);
            if shell.root().is_none() {
                tauri::async_runtime::spawn(async move {
                    let shell = handle.state::<Shell>();
                    seed(&handle, &core, &shell).await;
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            core_request,
            workspace_current,
            workspace_choose,
            runtime_list,
            runtime_clear,
            cookies_list,
            cookies_clear
        ])
        .build(tauri::generate_context!())
        .expect("error while building the Ping shell");

    app.run(|handle, event| {
        // Closing stdin is what tells the core to exit; without this it would outlive the UI.
        if let RunEvent::Exit = event {
            handle.state::<Core>().stop();
        }
    });
}
