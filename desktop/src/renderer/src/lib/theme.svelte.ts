/** Theme choice: follow the system, or force a concrete theme. Persisted in the renderer only. */

/** The themes that actually paint. The `rocket-*` themes carry a photograph behind the UI. */
export type ThemeName = 'light' | 'dark' | 'rocket-night' | 'rocket-daylight' | 'violet'
export type ThemeChoice = 'system' | ThemeName

const NAMES: ThemeName[] = ['light', 'dark', 'rocket-night', 'rocket-daylight', 'violet']

/** The palette's theme targets, with the name each shows. */
export const THEMES: { name: ThemeName; label: string }[] = NAMES.map((name) => ({
  name,
  label: {
    light: 'Light',
    dark: 'Dark',
    'rocket-night': 'Rocket Night',
    'rocket-daylight': 'Rocket Daylight',
    violet: 'Violet'
  }[name]
}))

/** Light follows dark so the original dark-to-light flip still works on the first press. */
const CYCLE: ThemeName[] = ['dark', 'light', 'rocket-night', 'rocket-daylight', 'violet']

export const theme = $state<{ choice: ThemeChoice; resolved: ThemeName }>({
  choice: 'system',
  resolved: 'dark'
})

/** Dark surfaces. rocket-daylight carries a photo but is light, so it is not listed. */
const DARK: ThemeName[] = ['dark', 'rocket-night', 'violet']

/** Whether the resolved theme wants dark syntax colours. Ask this, never compare against 'dark'. */
export function isDark(): boolean {
  return DARK.includes(theme.resolved)
}

const systemPreference = window.matchMedia('(prefers-color-scheme: light)')

function resolve(choice: ThemeChoice): ThemeName {
  if (choice === 'system') {
    return systemPreference.matches ? 'light' : 'dark'
  }
  return choice
}

export function applyTheme(): void {
  theme.resolved = resolve(theme.choice)
  document.documentElement.dataset.theme = theme.resolved
}

export function setTheme(choice: ThemeChoice): void {
  theme.choice = choice
  try {
    localStorage.setItem('ping.theme', choice)
  } catch {
    // A locked-down profile just means the choice is not remembered.
  }
  applyTheme()
}

/** The theme one cycle step away, so the palette can name where it is about to go. */
export function nextTheme(): ThemeName {
  return CYCLE[(CYCLE.indexOf(theme.resolved) + 1) % CYCLE.length]
}

export function cycleTheme(): void {
  setTheme(nextTheme())
}

export function loadTheme(): void {
  let saved: string | null = null
  try {
    saved = localStorage.getItem('ping.theme')
  } catch {
    saved = null
  }
  theme.choice =
    saved === 'system' || (saved !== null && NAMES.includes(saved as ThemeName))
      ? (saved as ThemeChoice)
      : 'system'
  applyTheme()

  systemPreference.addEventListener('change', () => {
    if (theme.choice === 'system') {
      applyTheme()
    }
  })
}
