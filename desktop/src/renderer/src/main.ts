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
