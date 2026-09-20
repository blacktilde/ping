/**
 * Theme choice: follow the system, or force one of the concrete themes. Persisted in the
 * renderer only.
 */

/** The themes that actually paint. The two `rocket-*` themes carry a photograph behind the UI. */
export type ThemeName = 'light' | 'dark' | 'rocket-night' | 'rocket-daylight'
export type ThemeChoice = 'system' | ThemeName

const NAMES: ThemeName[] = ['light', 'dark', 'rocket-night', 'rocket-daylight']

/** The theme targets the palette lists, in a stable order, each with the name it shows. */
export const THEMES: { name: ThemeName; label: string }[] = NAMES.map((name) => ({
  name,
  label: {
    light: 'Light',
    dark: 'Dark',
    'rocket-night': 'Rocket Night',
    'rocket-daylight': 'Rocket Daylight'
  }[name]
}))

/*
 * The order the palette cycles through. Light comes straight after dark so the long-standing
 * dark-to-light flip still works on the first press; the photo themes follow it.
 */
const CYCLE: ThemeName[] = ['dark', 'light', 'rocket-night', 'rocket-daylight']

export const theme = $state<{ choice: ThemeChoice; resolved: ThemeName }>({
  choice: 'system',
  resolved: 'dark'
})

/*
 * Which themes paint a dark surface. This is not the same question as "is it a photo
 * theme": rocket-daylight carries an image but is light, so it belongs with light here.
 */
const DARK: ThemeName[] = ['dark', 'rocket-night']

/**
 * Whether the resolved theme wants light-on-dark syntax colours. Anything keying off
 * darkness must ask this rather than compare against 'dark', or a new dark theme silently
 * gets the light editor — and a new light one gets the dark editor on a white page.
 */
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

export function cycleTheme(): void {
  const next = CYCLE[(CYCLE.indexOf(theme.resolved) + 1) % CYCLE.length]
  setTheme(next)
}

/** The theme one cycle step away, so the palette can name where it is about to go. */
export function nextTheme(): ThemeName {
  return CYCLE[(CYCLE.indexOf(theme.resolved) + 1) % CYCLE.length]
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
