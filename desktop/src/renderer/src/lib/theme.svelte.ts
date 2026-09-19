/** Theme choice: follow the system, or force light or dark. Persisted in the renderer only. */

export type ThemeChoice = 'system' | 'light' | 'dark'

export const theme = $state<{ choice: ThemeChoice; resolved: 'light' | 'dark' }>({
  choice: 'system',
  resolved: 'dark'
})

const systemPreference = window.matchMedia('(prefers-color-scheme: light)')

function resolve(choice: ThemeChoice): 'light' | 'dark' {
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
  setTheme(theme.resolved === 'dark' ? 'light' : 'dark')
}

export function loadTheme(): void {
  let saved: string | null = null
  try {
    saved = localStorage.getItem('ping.theme')
  } catch {
    saved = null
  }
  theme.choice = saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system'
  applyTheme()

  systemPreference.addEventListener('change', () => {
    if (theme.choice === 'system') {
      applyTheme()
    }
  })
}
