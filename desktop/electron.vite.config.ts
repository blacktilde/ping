import { resolve } from 'node:path'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    build: {
      rollupOptions: { input: resolve(__dirname, 'src/main/index.ts') }
    }
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: {
      // CJS so the renderer can stay sandboxed: Electron rejects ESM preloads under a sandbox.
      rollupOptions: {
        input: resolve(__dirname, 'src/preload/index.ts'),
        output: { format: 'cjs', entryFileNames: '[name].cjs' }
      }
    }
  },
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    plugins: [svelte(), tailwindcss()],
    build: {
      // electron-vite leaves the renderer unminified by default; minified, the bundle is
      // little more than half the size for the window to parse on every launch.
      minify: true,
      rollupOptions: { input: resolve(__dirname, 'src/renderer/index.html') }
    }
  }
})
