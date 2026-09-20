<script lang="ts">
  import { onMount } from 'svelte'
  import { Compartment, EditorState, type Extension } from '@codemirror/state'
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
  }

  let {
    value = $bindable(''),
    language = 'plain',
    label = 'Request body',
    readonly = false
  }: Props = $props()

  let host: HTMLDivElement
  let view: EditorView | undefined

  // oneDark supplies the token colours; this trims it to the app's panel surface.
  const appearance = EditorView.theme({
    '&': { height: '100%', fontSize: '13px', backgroundColor: 'transparent' },
    '.cm-scroller': {
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
      overflow: 'auto'
    },
    '.cm-content': { padding: '12px 0' },
    '.cm-gutters': { backgroundColor: 'transparent', border: 'none' }
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
          value = update.state.doc.toString()
        }
      })
    ]
    if (language === 'json') {
      list.push(json(), linter(jsonParseLinter()))
    }
    if (readonly) {
      list.push(EditorState.readOnly.of(true))
    }
    return list
  }

  onMount(() => {
    view = new EditorView({
      parent: host,
      state: EditorState.create({ doc: value, extensions: extensions() })
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
    if (view && next !== view.state.doc.toString()) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } })
    }
  })
</script>

<div bind:this={host} class="h-full overflow-hidden"></div>
