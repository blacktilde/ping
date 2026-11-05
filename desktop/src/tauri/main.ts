// The Tauri entry: install `window.ping` before the renderer's own entry reads it.
import './bridge'
import '../renderer/src/main'
