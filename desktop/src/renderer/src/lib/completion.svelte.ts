/**
 * `{{name}}` completion for plain inputs: the names on offer, and the one menu every input
 * shares.
 *
 * Module-level `$state`, like the draft: one input has focus at a time, so one menu serves
 * them all, and `VariableMenu` (mounted once in the app) draws whatever this holds.
 */

import {
  applyCompletion,
  matchOptions,
  openPlaceholder,
  variableOptions,
  type Placeholder,
  type VariableOption
} from './completion'
import { runtime } from './runtime.svelte'
import { secretNames } from './secrets.svelte'
import { variables } from './vars.svelte'

/** Every name the active collection, environment, runtime and secrets offer. */
export function currentOptions(): VariableOption[] {
  return variableOptions({
    resolved: variables.resolved,
    environment: variables.environmentVariables
      .filter((row) => row.enabled)
      .map((row) => row.name.trim()),
    runtime: runtime.names,
    secrets: secretNames.names
  })
}

export const MENU_ID = 'variable-completion'

export const menu = $state({
  open: false,
  options: [] as VariableOption[],
  active: 0,
  /** Where to draw it, in viewport coordinates: below the input, at its left edge. */
  left: 0,
  top: 0,
  width: 0
})

let owner: HTMLInputElement | null = null
let placeholder: Placeholder | null = null

export function optionId(index: number): string {
  return `${MENU_ID}-${index}`
}

function close(): void {
  menu.open = false
  if (owner) {
    owner.setAttribute('aria-expanded', 'false')
    owner.removeAttribute('aria-activedescendant')
  }
  owner = null
  placeholder = null
}

function sync(): void {
  if (!owner) {
    return
  }
  owner.setAttribute('aria-expanded', String(menu.open))
  if (menu.open) {
    owner.setAttribute('aria-activedescendant', optionId(menu.active))
  } else {
    owner.removeAttribute('aria-activedescendant')
  }
}

/** Opens, filters or closes the menu for what the input now holds around its cursor. */
function refresh(input: HTMLInputElement): void {
  const cursor = input.selectionStart ?? input.value.length
  const found = input.selectionStart === input.selectionEnd ? openPlaceholder(input.value, cursor) : null
  const options = found ? matchOptions(currentOptions(), found.query) : []
  if (!found || options.length === 0) {
    if (owner === input) {
      close()
    }
    return
  }
  if (owner && owner !== input) {
    close()
  }
  const rect = input.getBoundingClientRect()
  const sameList =
    menu.open &&
    owner === input &&
    options.length === menu.options.length &&
    options.every((option, index) => option.name === menu.options[index].name)
  owner = input
  placeholder = found
  menu.options = options
  menu.active = sameList ? Math.min(menu.active, options.length - 1) : 0
  menu.left = rect.left
  menu.top = rect.bottom + 4
  menu.width = rect.width
  menu.open = true
  sync()
}

/** Fills the placeholder with the chosen name, as though it had been typed. */
export function pick(index: number): void {
  const input = owner
  const option = menu.options[index]
  if (!input || !placeholder || !option) {
    close()
    return
  }
  const done = applyCompletion(input.value, placeholder, option.name)
  close()
  input.value = done.text
  input.setSelectionRange(done.cursor, done.cursor)
  // `bind:value` and `oninput` handlers both listen for this, so the draft follows.
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

export function highlight(index: number): void {
  menu.active = index
  sync()
}

/**
 * Offers `{{name}}` completion on a plain input. Keys the menu handles stop here, before
 * the form sees an Enter or the window sees a shortcut.
 */
export function completeVariables(input: HTMLInputElement): { destroy: () => void } {
  input.setAttribute('aria-autocomplete', 'list')
  input.setAttribute('aria-controls', MENU_ID)
  input.setAttribute('aria-expanded', 'false')

  const onInput = (event: Event): void => {
    // Our own dispatch after a pick; the placeholder is closed, so there is nothing to offer.
    if (!event.isTrusted) {
      return
    }
    refresh(input)
  }

  const onKeydown = (event: KeyboardEvent): void => {
    if (!menu.open || owner !== input) {
      return
    }
    const count = menu.options.length
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      highlight((menu.active + (event.key === 'ArrowDown' ? 1 : count - 1)) % count)
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      pick(menu.active)
    } else if (event.key === 'Escape') {
      close()
    } else {
      return
    }
    event.preventDefault()
    event.stopPropagation()
  }

  // Moving the cursor out of the placeholder closes the menu; moving within it refilters.
  const onMove = (event: Event): void => {
    if (owner !== input) {
      return
    }
    if (event instanceof KeyboardEvent && !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      return
    }
    refresh(input)
  }

  const onBlur = (): void => {
    if (owner === input) {
      close()
    }
  }

  input.addEventListener('input', onInput)
  input.addEventListener('keydown', onKeydown)
  input.addEventListener('keyup', onMove)
  input.addEventListener('click', onMove)
  input.addEventListener('blur', onBlur)
  return {
    destroy() {
      input.removeEventListener('input', onInput)
      input.removeEventListener('keydown', onKeydown)
      input.removeEventListener('keyup', onMove)
      input.removeEventListener('click', onMove)
      input.removeEventListener('blur', onBlur)
      if (owner === input) {
        close()
      }
    }
  }
}
