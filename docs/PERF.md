# Electron vs Tauri: performance on Linux

The measurement behind phase 30's decision ([PLAN.md](PLAN.md)): is the Tauri shell enough faster,
lighter or smaller than Electron to justify replacing it? Linux only; macOS and Windows are
not measured here.

## Verdict

**On Linux, no.** Tauri's one clear win is the `.deb`: 2.1 MB against 95.7 MB. Everything a user
feels while the app runs is the same or worse:

- **Startup is slower,** by 300–400 ms warm and 260–410 ms cold. WebKitGTK takes about half a
  second longer than Chromium just to start loading the page, and no setting closes that gap.
- **Memory is lower only with a non-default setting.** WebKitGTK's default renderer uses 42%
  more memory than Electron at idle and 45% more after a 9 MB response. With
  `WEBKIT_DISABLE_DMABUF_RENDERER=1` it uses 21% less at idle and about 10% less under load,
  but that setting is a rendering workaround for machines without a usable GPU, and Ping would
  have to decide when to turn it on.
- **Moving data is slower.** Round trips take 30–35% longer, 9 MB echoes in 218–221 ms against
  148 ms, and a 1 MB response takes 185 ms to paint against 111 ms (1.7×).
- **Interaction is a wash, at best.** With the setting, new-tab, tab switching and scrolling are
  equal or slightly better. Without it, scrolling drops to 43 fps. Typing into a 1 MB body is
  slower in both Tauri configurations: p95 40–50 ms against 31 ms.

Even the size win is smaller than the `.deb` suggests:

- **The AppImage is only 36% smaller** (77 MB against 122 MB), because it has to carry WebKitGTK
  itself. On Linux the AppImage is also what both updaters download.
- **The `.deb` leans on the system WebKitGTK,** 122 MB installed, which many distributions do not
  ship by default.

Against that, the change costs:

- **A rewrite:** about 3,500 lines of tested TypeScript, rewritten in Rust.
- **A new updater path,** replacing both electron-updater and the signed macOS route.
- **A smoke suite that has to move to WebDriver,** because WebKitGTK has no CDP.
- **Rendering that differs per OS,** which is the reason Electron was chosen in the first place.

The case for Tauri therefore rests on platforms not measured here:

- **macOS:** WKWebView is the best-regarded Tauri webview, and the download shrinks the most there.
- **Windows:** WebView2 is itself Chromium, so expect little memory or speed change, but the
  installer drops from about 100 MB to a few MB.

If download size on those two is the goal, measure them before deciding. If it isn't,
Electron's own size can be cut much more cheaply (see "Cheaper options" below).

## Results

Medians, with the range across runs in brackets. Warm startup is 10 runs per shell, cold is 5,
and everything after startup is 3 full runs. Memory is the PSS (proportional set size: shared
pages split between the processes using them) of the shell's processes only. The core is the
same JVM process under every shell and is listed once for scale.

Machine: Intel Xeon @ 2.10 GHz, 4 cores, 16 GB, Linux 6.18, Xvfb, no GPU. Electron 44.7.2
(Chrome 152) against Tauri 2 on WebKitGTK 2.52 ("Version/60.5" in its user agent).
`tauri-nodmabuf` is the same Tauri build run with `WEBKIT_DISABLE_DMABUF_RENDERER=1`.

### Size

Both packaged without the core, which adds the same ~40 MB native image to either.

| | Electron | Tauri |
|---|---|---|
| .deb download | 95.7 MB | 2.1 MB |
| .deb installed | 289.1 MB | 8.2 MB |
| AppImage (also the Linux update download) | 121.5 MB | 77.3 MB |
| System WebKitGTK the .deb depends on | – | 122.1 MB installed |

The Electron .deb depends on libgtk-3-0, libnotify4, libnss3, libxss1, libxtst6, xdg-utils,
libatspi2.0-0, libuuid1 and libsecret-1-0. The Tauri .deb depends on libwebkit2gtk-4.1-0 and
libgtk-3-0. electron-updater can download an AppImage update as a block-level delta;
Tauri's updater downloads the whole file.

### Startup, warm (ms from spawn)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| Webview navigation starts | 208 (192–258) | 773 (757–822) | 704 (695–715) |
| DOM content loaded | 351 (315–397) | 942 (922–987) | 871 (858–947) |
| First paint | 386 (348–434) | 948 (927–991) | 893 (872–951) |
| UI ready (collection listed, Send idle) | 763 (727–806) | 1167 (1132–1226) | 1055 (1025–1115) |

### Startup, cold: page cache dropped (ms from spawn)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| Webview navigation starts | 346 (319–372) | 910 (875–943) | 815 (814–835) |
| DOM content loaded | 520 (479–554) | 1135 (1075–1154) | 1018 (1012–1035) |
| UI ready | 939 (919–1007) | 1349 (1339–1352) | 1201 (1199–1228) |

The first launch, which also creates a new profile, reached UI ready in 815 ms for Electron,
2242 ms for Tauri and 1155 ms for Tauri with the setting. "UI ready" includes the JVM core
starting, which is identical under each shell. The release native core starts in tens of
milliseconds, so in a release build the gap between the shells is closer to the
navigation-start gap of 500–560 ms.

### Memory: shell processes, core excluded (MB)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| Idle PSS | 292 (289–294) | 415 (401–420) | 231 (227–231) |
| Idle private (anonymous) PSS | 109 (107–109) | 239 (223–243) | 88 (86–88) |
| After a 1 MB response | 501 (491–517) | 717 (659–719) | 410 (389–434) |
| After a 9 MB response | 619 (618–633) | 880 (852–886) | 557 (549–568) |
| End: 23 tabs, 1 MB body typed into | 541 (468–604) | 897 (881–962) | 525 (481–644) |
| Processes | 6 | 3 | 3 |
| Threads | 67 | 70 | 48 |
| Core PSS at idle, for scale | 93 | 92 | 92 |

### CPU: shell processes, % of one core

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| Idle, 10 s after ready | 0.0 | 0.1 (0.1–0.2) | 0.1 (0.0–0.2) |
| Idle after the heavy work | 2.0 (1.8–2.0) | 9.0 (9.0–9.4) | 1.4 (1.0–2.0) |

### IPC (ms unless noted)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| Renderer ↔ shell round trip | 0.115 (0.106–0.118) | 0.137 (0.132–0.164) | 0.149 (0.135–0.164) |
| Renderer ↔ core round trip (`core.ping`) | 0.436 (0.412–0.442) | 0.582 (0.572–0.627) | 0.572 (0.527–0.592) |
| … p95 | 0.8 (0.7–0.8) | 1.0 | 1.0 |
| 1000 concurrent `core.ping`, total | 55 (52–70) | 92 (84–136) | 98 (71–99) |
| Echo 100 KB | 6.0 (3.9–6.3) | 7.0 (7.0–10.0) | 8.0 (5.0–8.0) |
| Echo 1 MB | 21 (21–22) | 31 (31–33) | 30 (30–36) |
| Echo 9 MB | 148 (147–157) | 221 (213–223) | 218 (213–221) |
| Echo 9 MB throughput (MB/s, both ways) | 122 (115–123) | 81 (81–85) | 83 (81–85) |

### Large responses (ms unless noted)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| 1 MB: Send to status shown | 49 (49–53) | 197 (79–222) | 66 (63–70) |
| 1 MB: Send to pretty view painted | 111 (108–119) | 381 (250–443) | 185 (172–192) |
| 1 MB: longest frame while loading | 33 (17–50) | 187 (152–270) | 78 (76–84) |
| 1 MB: scroll fps | 60.0 | 42.9 (42.7–43.1) | 61.6 (60.5–61.7) |
| 1 MB: scroll p95 frame | 16.7 | 39.0 (37.0–42.0) | 22.0 (19.0–26.0) |
| 9 MB: Send to status shown | 193 (189–253) | 402 (393–799) | 358 (331–381) |
| 9 MB: Send to pretty view painted | 400 (389–469) | 632 (625–830) | 567 (517–574) |
| 9 MB: longest frame while loading | 117 (100–117) | 151 (131–775) | 107 (104–113) |
| 9 MB: scroll fps | 60.0 (59.5–60.0) | 43.2 (42.2–44.9) | 62.3 (61.3–62.3) |
| 9 MB: scroll p95 frame | 16.8 | 37.0 (36.0–39.0) | 21.0 (21.0–23.0) |

### Tabs and typing (ms)

| | electron | tauri | tauri-nodmabuf |
|---|---|---|---|
| New tab, p50 | 33.2 | 66.0 (51.0–66.0) | 21.0 (18.0–21.0) |
| Switch heavy ↔ light tab, p50 | 33.2 | 54.0 (48.0–68.0) | 22.0 (19.0–24.0) |
| Switch heavy ↔ light tab, p95 | 35.2 (34.6–38.4) | 75.0 (73.0–88.0) | 29.0 (29.0–35.0) |
| Key to next frame, small body, p50 | 11.3 (10.6–11.5) | 11.0 (10.0–16.0) | 11.0 (10.0–11.0) |
| Key to next frame, small body, p95 | 18.3 (17.9–19.0) | 20.0 (18.0–28.0) | 14.0 (13.0–16.0) |
| Key to next frame, 1 MB body, p50 | 13.8 (13.7–15.1) | 18.0 (18.0–21.0) | 19.0 (18.0–19.0) |
| Key to next frame, 1 MB body, p95 | 31.4 (30.7–32.6) | 40.0 (37.0–42.0) | 50.0 (37.0–103.0) |

All 60 key presses arrived in every typing run.

## What the numbers can and cannot say

- **No GPU.** Both engines render in software here, Chromium through SwiftShader and WebKitGTK
  through Mesa's llvmpipe. A desktop with a GPU changes absolute numbers for both, and it may
  change WebKitGTK's default DMA-BUF renderer most, which is why both Tauri configurations are
  reported.
- **Electron's frame rate is capped at 60 fps** by its vsync, which explains its exact 16.7 ms
  frames. WebKitGTK under Xvfb is not capped, so its 61–62 fps is "at least as smooth", not faster.
- **Electron ran with `--no-sandbox`,** because this container runs as root. The sandbox adds a
  little memory to Electron in normal use.
- **WebKit rounds `performance.now()` to whole milliseconds.** Sub-millisecond IPC is therefore
  timed as a loop of 1,000 calls divided by 1,000, the same way in both shells.
- **The core is the JVM launcher, not the native image,** in every configuration. It is the same
  process under each shell and its memory is excluded, but it adds the same startup time to every
  "UI ready" figure.
- **This is the spike's Rust shell,** which does less than Electron's main process: no secrets,
  logs or history yet. That can only flatter Tauri's numbers.

## Cheaper options, if the goal is size

Electron's download can be cut without changing shells:

- **Language packs.** Only one of the ~55 Chromium locale packs is needed, which saves tens of MB
  installed. electron-builder's `electronLanguages` setting does this.
- **Compression.** A `.deb` compressed with xz instead of the default.
- **Update deltas.** On Linux the AppImage is already updated with block-level deltas, so most
  updates are already small.

None of these were measured here.

## Re-running

```sh
make core
cd desktop
VITE_PING_BENCH=1 npx electron-vite build
CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --linux --dir
VITE_PING_BENCH=1 npx tauri build --no-bundle
Xvfb :99 -screen 0 1440x900x24 &
DISPLAY=:99 node bench/bench.mjs --out bench/results-linux.json   # ~25 minutes; needs xdotool, root for cold starts
node bench/report.mjs bench/results-linux.json
./bench/sizes.sh                                                  # builds real installers, overwrites dist/
```

`bench/results-linux.json` holds the raw numbers behind every table here. The in-page half,
`src/renderer/src/bench/bench.ts`, is included only when `VITE_PING_BENCH=1` and drives the real
UI. It reports each phase through the app's own `http.send` to the harness's loopback collector,
so neither shell needs a hook. The harness samples memory and CPU from `/proc` between phases,
drops the page cache for cold starts, and types with xdotool, so key presses are real input
events.
