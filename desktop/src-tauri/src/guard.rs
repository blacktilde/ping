//! What the shell does to a renderer's `core:request` before the core sees it.
//!
//! A port of the checks in `desktop/src/main/index.ts` (the `core:request` handler) and
//! `desktop/src/main/files.ts`. These are pure functions over JSON so they can be tested
//! without a window or a core, and so the two shells can be compared line by line while both
//! exist. The renderer is the least-trusted layer: everything it names is checked here.

use serde_json::{Map, Value};

/// Rejects anything that is not a plain path inside the folder it is resolved against.
pub fn is_relative_path(value: &str) -> bool {
    if value.starts_with('/') || value.starts_with('\\') || has_drive_prefix(value) {
        return false;
    }
    !value.split(['/', '\\']).any(|part| part == "..")
}

/// True for a path that names a location by itself, whichever platform wrote it.
pub fn looks_absolute(value: &str) -> bool {
    std::path::Path::new(value).is_absolute()
        || value.starts_with('/')
        || value.starts_with('\\')
        || has_drive_prefix(value)
}

fn has_drive_prefix(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() >= 3
        && bytes[0].is_ascii_alphabetic()
        && bytes[1] == b':'
        && (bytes[2] == b'/' || bytes[2] == b'\\')
}

/// One jar scope per collection and environment, built here and never taken from the renderer.
pub fn cookie_scope(root: &str, collection: &str, environment: &str) -> String {
    [root, collection, environment].join("|")
}

fn as_object(params: &Value) -> Map<String, Value> {
    match params {
        Value::Object(map) => map.clone(),
        _ => Map::new(),
    }
}

fn valid_relative(value: Option<&Value>) -> String {
    match value.and_then(Value::as_str) {
        Some(text) if !text.is_empty() && is_relative_path(text) => text.to_string(),
        _ => String::new(),
    }
}

/// Injects the open folder's root and refuses any path field that could escape it.
fn with_workspace_root(params: &Value, root: &str) -> Result<Map<String, Value>, String> {
    let mut safe = as_object(params);
    safe.insert("root".into(), Value::String(root.into()));

    for field in ["path", "collection", "to"] {
        match safe.get(field) {
            None => {}
            Some(Value::String(text)) if is_relative_path(text) => {}
            Some(other) => {
                let shown = other.as_str().map(str::to_string).unwrap_or_else(|| other.to_string());
                return Err(format!("Unsafe {field}: {shown}"));
            }
        }
    }
    // `store.reorder` names entries of one folder: bare names, never a path.
    if let Some(names) = safe.get("names") {
        let ok = names.as_array().is_some_and(|names| {
            names.iter().all(|name| {
                name.as_str().is_some_and(|name| {
                    !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\\'])
                })
            })
        });
        if !ok {
            return Err("Unsafe names".into());
        }
    }
    Ok(safe)
}

/// Every file path a request body names, skipping rows the core will skip.
fn body_files(params: &Map<String, Value>) -> Vec<(String, String)> {
    let Some(Value::Object(body)) = params.get("body") else {
        return Vec::new();
    };
    let mut found = Vec::new();
    if body.get("type").and_then(Value::as_str) == Some("file") {
        if let Some(file) = body.get("file").and_then(Value::as_str).filter(|f| !f.is_empty()) {
            found.push((file.to_string(), "the body".to_string()));
        }
    }
    if let Some(fields) = body.get("fields").and_then(Value::as_array) {
        for field in fields {
            let enabled = field.get("enabled") != Some(&Value::Bool(false));
            if let Some(file) = field.get("file").and_then(Value::as_str).filter(|f| !f.is_empty()) {
                if enabled {
                    let name = field.get("name").and_then(Value::as_str).unwrap_or("");
                    found.push((file.to_string(), format!("the field \"{name}\"")));
                }
            }
        }
    }
    found
}

/// A message when the request names a file it may not read. `granted` answers whether a file
/// dialog chose an absolute path this session; the spike has no dialog yet, so it refuses all.
fn check_body_files(params: &Map<String, Value>, granted: &dyn Fn(&str) -> bool) -> Option<String> {
    for (path, label) in body_files(params) {
        if looks_absolute(&path) {
            if !granted(&path) {
                return Some(format!("The file for {label} was not chosen in this session. Choose it again."));
            }
        } else if !is_relative_path(&path) {
            return Some(format!("The file for {label} is outside the collection."));
        }
    }
    None
}

/// Where relative files resolve and which cookie jar a send uses: decided here, never by the renderer.
fn with_request_context(mut params: Map<String, Value>, root: Option<&str>) -> Map<String, Value> {
    let collection = valid_relative(params.get("collection"));
    let environment = valid_relative(params.get("environment"));
    for key in ["collection", "environment", "filesBase", "cookieScope"] {
        params.remove(key);
    }
    if let Some(root) = root {
        if !collection.is_empty() {
            let base = std::path::Path::new(root).join(&collection);
            params.insert("filesBase".into(), Value::String(base.to_string_lossy().into_owned()));
        }
        params.insert("cookieScope".into(), Value::String(cookie_scope(root, &collection, &environment)));
    }
    params
}

/// Turns a renderer's call into what the core may receive, or the reason it may not.
///
/// `network` is the user's saved network settings, if any; whatever the renderer sent under that
/// key is always discarded. Secrets and OAuth tokens are not merged yet (no store in the spike).
pub fn prepare(
    method: &str,
    params: &Value,
    root: Option<&str>,
    network: Option<&Value>,
    granted: &dyn Fn(&str) -> bool,
) -> Result<Value, String> {
    if method == "import.collection" {
        return Err("import.collection is only available through the import dialog".into());
    }
    if method == "export.collection" {
        return Err("export.collection is only available through the export dialog".into());
    }

    let mut args = as_object(params);
    if method.starts_with("store.") || method.starts_with("vars.") || method.starts_with("run.") {
        let root = root.ok_or_else(|| "No collection folder is open".to_string())?;
        args = with_workspace_root(params, root)?;
    }

    let leaves_machine = matches!(method, "http.send" | "auth.authorize" | "net.probe") || method.starts_with("run.");
    if leaves_machine {
        args.remove("network");
        if let Some(network) = network {
            args.insert("network".into(), network.clone());
        }
    }

    if method == "http.send" {
        if let Some(refused) = check_body_files(&args, granted) {
            return Err(refused);
        }
        args = with_request_context(args, root);
    }
    if method.starts_with("run.") {
        // A collection is data from disk and possibly someone else's: it may not upload an absolute path.
        args.insert("allowAbsoluteFiles".into(), Value::Bool(false));
    }
    Ok(Value::Object(args))
}

/// Strips captured values from an `http.send` result, returning them for the shell to keep.
/// The renderer sees only names and hit/miss, never a value.
pub fn absorb_captures(mut result: Value) -> (Value, Vec<(String, String)>) {
    let mut kept = Vec::new();
    if let Some(Value::Array(captured)) = result.get_mut("captured") {
        for entry in captured.iter_mut() {
            let Value::Object(entry) = entry else { continue };
            let found = entry.get("found") == Some(&Value::Bool(true));
            let value = entry.remove("value");
            if let (true, Some(Value::String(name)), Some(Value::String(value))) =
                (found, entry.get("name").cloned(), value)
            {
                kept.push((name, value));
            }
        }
    }
    (result, kept)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn none(_: &str) -> bool {
        false
    }

    #[test]
    fn relative_paths() {
        assert!(is_relative_path("api/users.yaml"));
        assert!(!is_relative_path("/etc/passwd"));
        assert!(!is_relative_path("\\share"));
        assert!(!is_relative_path("C:\\Windows"));
        assert!(!is_relative_path("a/../../b"));
        assert!(!is_relative_path("a\\..\\b"));
        assert!(is_relative_path("a..b/c"));
    }

    #[test]
    fn refuses_dialog_only_methods() {
        for method in ["import.collection", "export.collection"] {
            assert!(prepare(method, &json!({}), Some("/w"), None, &none).is_err());
        }
    }

    #[test]
    fn store_calls_get_the_shell_root() {
        let out = prepare("store.list", &json!({"root": "/elsewhere", "path": "c"}), Some("/w"), None, &none).unwrap();
        assert_eq!(out["root"], "/w");
        assert!(prepare("store.read", &json!({"path": "../x"}), Some("/w"), None, &none).is_err());
        assert!(prepare("store.read", &json!({"path": 3}), Some("/w"), None, &none).is_err());
        assert!(prepare("store.move", &json!({"to": "/tmp"}), Some("/w"), None, &none).is_err());
        assert!(prepare("store.list", &json!({}), None, None, &none).is_err());
    }

    #[test]
    fn reorder_takes_bare_names_only() {
        assert!(prepare("store.reorder", &json!({"names": ["a", "b"]}), Some("/w"), None, &none).is_ok());
        for bad in [json!(["a/b"]), json!([".."]), json!([""]), json!("a"), json!([1])] {
            assert!(prepare("store.reorder", &json!({"names": bad}), Some("/w"), None, &none).is_err());
        }
    }

    #[test]
    fn renderer_network_is_discarded() {
        let sent = json!({"network": {"proxy": {"url": "http://evil"}}});
        let out = prepare("http.send", &sent, Some("/w"), None, &none).unwrap();
        assert!(out.get("network").is_none());
        let saved = json!({"proxy": {"url": "http://mine"}});
        let out = prepare("net.probe", &sent, Some("/w"), Some(&saved), &none).unwrap();
        assert_eq!(out["network"], saved);
    }

    #[test]
    fn send_context_comes_from_the_shell() {
        let sent = json!({"collection": "api", "environment": "dev", "filesBase": "/etc", "cookieScope": "x"});
        let out = prepare("http.send", &sent, Some("/w"), None, &none).unwrap();
        assert!(out["filesBase"].as_str().unwrap().ends_with("api"));
        assert_eq!(out["cookieScope"], "/w|api|dev");
        assert!(out.get("collection").is_none());

        let escaping = json!({"collection": "../..", "filesBase": "/etc"});
        let out = prepare("http.send", &escaping, Some("/w"), None, &none).unwrap();
        assert!(out.get("filesBase").is_none());
        assert_eq!(out["cookieScope"], "/w||");
    }

    #[test]
    fn body_files_need_a_grant_or_stay_inside() {
        let absolute = json!({"body": {"type": "file", "file": "/etc/passwd"}});
        assert!(prepare("http.send", &absolute, Some("/w"), None, &none).is_err());
        assert!(prepare("http.send", &absolute, Some("/w"), None, &|p| p == "/etc/passwd").is_ok());

        let escaping = json!({"body": {"type": "multipart", "fields": [{"name": "f", "file": "../x"}]}});
        assert!(prepare("http.send", &escaping, Some("/w"), None, &none).is_err());

        let disabled = json!({"body": {"type": "multipart", "fields": [{"file": "/x", "enabled": false}]}});
        assert!(prepare("http.send", &disabled, Some("/w"), None, &none).is_ok());
    }

    #[test]
    fn runs_never_read_absolute_files() {
        let out = prepare("run.collection", &json!({"allowAbsoluteFiles": true}), Some("/w"), None, &none).unwrap();
        assert_eq!(out["allowAbsoluteFiles"], false);
    }

    #[test]
    fn captured_values_are_stripped() {
        let result = json!({"status": 200, "captured": [
            {"name": "token", "found": true, "value": "s3cret"},
            {"name": "miss", "found": false}
        ]});
        let (out, kept) = absorb_captures(result);
        assert_eq!(kept, vec![("token".to_string(), "s3cret".to_string())]);
        assert!(!out.to_string().contains("s3cret"));
        assert_eq!(out["captured"][0]["name"], "token");
    }
}
