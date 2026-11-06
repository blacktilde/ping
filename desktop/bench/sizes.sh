#!/usr/bin/env bash
# Installer and installed sizes of both shells on Linux (docs/PERF.md). Run from desktop/.
#
# Both are packaged without the core, so the numbers are the shells alone: the native core is
# the same ~40 MB in either. Builds normal (non-bench) packages, overwriting dist/ and the
# Tauri release target.
set -euo pipefail
cd "$(dirname "$0")/.."

mb() { awk -v b="$1" 'BEGIN { printf "%.1f MB", b / 1048576 }'; }
size() { mb "$(stat -c %s "$1")"; }
installed() { mb "$(( $(dpkg-deb -f "$1" Installed-Size) * 1024 ))"; }

# electron-builder copies the core from here; an empty folder packages no core.
core_dir=../core/build/native/nativeCompile
made_core_dir=false
if [ ! -d "$core_dir" ]; then mkdir -p "$core_dir"; made_core_dir=true; fi
trap '$made_core_dir && rmdir "$core_dir" ../core/build/native 2>/dev/null || true' EXIT

rm -rf dist
npx electron-vite build >/dev/null
CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --linux AppImage deb >/dev/null
e_deb=$(ls dist/*.deb) e_app=$(ls dist/*.AppImage)

npx tauri build --bundles deb,appimage -c '{"bundle":{"active":true}}' >/dev/null
bundle=src-tauri/target/release/bundle
t_deb=$(ls "$bundle"/deb/*.deb) t_app=$(ls "$bundle"/appimage/*.AppImage)

# What the Tauri .deb leans on instead of shipping: the system WebKitGTK and its JavaScript engine.
webkit_kb=$(dpkg-query -W -f='${Installed-Size}\n' libwebkit2gtk-4.1-0 libjavascriptcoregtk-4.1-0 | paste -sd+ | bc)

cat <<EOF
| | Electron | Tauri |
|---|---|---|
| .deb download | $(size "$e_deb") | $(size "$t_deb") |
| .deb installed | $(installed "$e_deb") | $(installed "$t_deb") |
| AppImage (also the Linux update download) | $(size "$e_app") | $(size "$t_app") |
| Unpacked app folder | $(mb "$(du -sb dist/linux-unpacked | cut -f1)") | $(size src-tauri/target/release/ping-shell) binary |
| System WebKitGTK the .deb depends on | – | $(mb $((webkit_kb * 1024))) installed |

Electron .deb depends on: $(dpkg-deb -f "$e_deb" Depends)

Tauri .deb depends on: $(dpkg-deb -f "$t_deb" Depends)
EOF
