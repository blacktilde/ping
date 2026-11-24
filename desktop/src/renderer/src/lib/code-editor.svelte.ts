import type { Component } from 'svelte'

/**
 * The CodeMirror editor is large, so it is loaded on its own rather than with the first paint.
 *
 * It is held here once loaded, so a later mount (switching the response between Pretty and Raw,
 * say) renders in the same frame instead of waiting a tick on a fresh `import()`. `preloadEditor`
 * starts the load when the app is idle, so the first editor is normally ready before it is needed.
 */
type EditorComponent = Component<any>

export const codeEditor = $state<{ component: EditorComponent | null }>({ component: null })

let loading: Promise<void> | undefined

export function loadEditor(): Promise<void> {
  loading ??= import('../components/CodeEditor.svelte').then((module) => {
    codeEditor.component = module.default as EditorComponent
  })
  return loading
}

/** Loads the editor once the window has painted and the main thread is free. */
export function preloadEditor(): void {
  const start = (): void => void loadEditor().catch(() => undefined)
  if ('requestIdleCallback' in window) {
    window.requestIdleCallback(start, { timeout: 2000 })
  } else {
    setTimeout(start, 200)
  }
}
