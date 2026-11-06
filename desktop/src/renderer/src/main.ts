import { mount } from 'svelte'
import App from './App.svelte'
import { loadTheme } from './lib/theme.svelte'
import './app.css'

const target = document.getElementById('app')
if (!target) {
  throw new Error('Missing #app mount point')
}

// Apply the remembered theme before the first paint to keep the flash short.
loadTheme()

export default mount(App, { target })

// The performance benchmark (docs/PERF.md). A build without VITE_PING_BENCH=1 folds this to
// `if (false)` and drops the module, so releases never carry it.
if (import.meta.env.VITE_PING_BENCH === '1') {
  void import('./bench/bench')
}
