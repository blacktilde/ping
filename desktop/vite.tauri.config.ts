import { resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'

/**
 * The same renderer as `electron.vite.config.ts`, built for the Tauri shell.
 *
 * Two differences, both in the HTML: the entry becomes `src/tauri/main.ts`, which installs the
 * `window.ping` bridge first, and the CSP meta tag is dropped because Tauri sets the policy from
 * `tauri.conf.json` and adds its own IPC origin to it. A meta tag would block that origin.
 */
function tauriHtml(): Plugin {
  return {
    name: 'ping-tauri-html',
    transformIndexHtml: {
      // Before Vite reads the entry out of the HTML, so the build bundles the bridge too.
      order: 'pre',
      handler(html, context) {
        // The dev server serves files outside its root under /@fs/; the build resolves from the HTML.
        const entry = context.server ? `/@fs/${resolve(__dirname, 'src/tauri/main.ts')}` : '../tauri/main.ts'
        return html
          .replace(/<!--[\s\S]*?-->\s*<meta\s+http-equiv="Content-Security-Policy"[\s\S]*?\/>/, '')
          .replace('src="/src/main.ts"', `src="${entry}"`)
      }
    }
  }
}

export default defineConfig({
  root: resolve(__dirname, 'src/renderer'),
  plugins: [svelte(), tailwindcss(), tauriHtml()],
  clearScreen: false,
  server: { port: 5174, strictPort: true, fs: { allow: [resolve(__dirname, 'src')] } },
  build: {
    outDir: resolve(__dirname, 'out/tauri'),
    emptyOutDir: true,
    minify: true,
    rollupOptions: { input: resolve(__dirname, 'src/renderer/index.html') }
  }
})
