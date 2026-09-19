<script lang="ts">
  import { onMount } from 'svelte'
  import { EditorState, type Extension } from '@codemirror/state'
  import { EditorView } from '@codemirror/view'
  import { basicSetup } from 'codemirror'
  import { json, jsonParseLinter } from '@codemirror/lang-json'
  import { linter } from '@codemirror/lint'
  import { oneDark } from '@codemirror/theme-one-dark'

  interface Props {
    value?: string
    language?: 'json' | 'plain'
    label?: string
  }

  let { value = $bindable(''), language = 'plain', label = 'Request body' }: Props = $props()

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

  function extensions(): Extension[] {
    const list: Extension[] = [
      basicSetup,
      oneDark,
      appearance,
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
    return list
  }

  onMount(() => {
    view = new EditorView({
      parent: host,
      state: EditorState.create({ doc: value, extensions: extensions() })
    })
    return () => view?.destroy()
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
