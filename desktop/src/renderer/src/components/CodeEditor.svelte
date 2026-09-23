<script lang="ts">
  import { onMount } from 'svelte'
  import { Compartment, EditorState, Prec, type Extension } from '@codemirror/state'
  import { EditorView } from '@codemirror/view'
  import { basicSetup } from 'codemirror'
  import { json, jsonParseLinter } from '@codemirror/lang-json'
  import { linter } from '@codemirror/lint'
  import { oneDark } from '@codemirror/theme-one-dark'
  import { isDark, theme } from '../lib/theme.svelte'

  interface Props {
    value?: string
    language?: 'json' | 'plain'
    label?: string
    readonly?: boolean
    /** Horizontal inset for the editor, including its line-number gutter. */
    pad?: string
  }

  let {
    value = $bindable(''),
    language = 'plain',
    label = 'Request body',
    readonly = false,
    pad = ''
  }: Props = $props()

  let host: HTMLDivElement
  let view: EditorView | undefined
  // The text the editor holds, as last read into or out of it. Comparing an incoming value
  // with this is free; comparing it with the document means serialising the whole document,
  // which on a large body costs tens of milliseconds on every keystroke.
  let current = value

  // oneDark supplies the token colours; this trims it to the app's panel surface.
  const appearance = EditorView.theme({
    '&': { height: '100%', fontSize: '13px', backgroundColor: 'transparent' },
    '.cm-scroller': {
      fontFamily: 'var(--font-mono)',
      overflow: 'auto'
    },
    '.cm-content': { padding: '12px 0' },
    '.cm-gutters': { backgroundColor: 'transparent', border: 'none' }
  })

  const noActiveLine = EditorView.theme({
    '.cm-activeLine': { backgroundColor: 'transparent' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent' }
  })

  const themeCompartment = new Compartment()

  const lightLayer = EditorView.theme({
    '&': { backgroundColor: 'transparent', color: '#1f2933' },
    '.cm-gutters': { backgroundColor: 'transparent', color: '#7b8794', border: 'none' },
    '.cm-activeLine': { backgroundColor: 'rgba(0, 0, 0, 0.04)' },
    '.cm-activeLineGutter': { backgroundColor: 'rgba(0, 0, 0, 0.04)' }
  })

  function themeLayer(): Extension {
    return isDark() ? oneDark : lightLayer
  }

  function extensions(): Extension[] {
    const list: Extension[] = [
      basicSetup,
      appearance,
      themeCompartment.of(themeLayer()),
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ 'aria-label': label }),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          current = update.state.doc.toString()
          value = current
        }
      })
    ]
    if (language === 'json') {
      list.push(json())
      // A read-only view has nothing to lint: a response that does not parse is shown raw
      // with its own warning, so the linter would only re-parse a large body to find nothing.
      if (!readonly) {
        list.push(linter(jsonParseLinter()))
      }
    }
    if (readonly) {
      // A response has no cursor, so oneDark's active-line band would mark a line for no reason.
      list.push(EditorState.readOnly.of(true), Prec.highest(noActiveLine))
    }
    return list
  }

  onMount(() => {
    current = value
    view = new EditorView({
      parent: host,
      state: EditorState.create({ doc: current, extensions: extensions() })
    })
    return () => view?.destroy()
  })

  // Reflect a theme change without losing the document or the cursor.
  $effect(() => {
    theme.resolved
    view?.dispatch({ effects: themeCompartment.reconfigure(themeLayer()) })
  })

  // Reflect changes made from outside the editor, such as a body mode reset. The equality
  // guard stops the update listener and this effect from ping-ponging.
  $effect(() => {
    const next = value
    if (view && next !== current) {
      // A streamed body only grows: append the new tail rather than rebuilding the document
      // from scratch on every chunk.
      const changes =
        current.length > 0 && next.startsWith(current)
          ? { from: view.state.doc.length, insert: next.slice(current.length) }
          : { from: 0, to: view.state.doc.length, insert: next }
      current = next
      view.dispatch({ changes })
    }
  })
</script>

<div bind:this={host} class="h-full overflow-hidden {pad}"></div>
