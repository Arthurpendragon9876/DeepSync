// Theme = one of the Android app's colour schemes (css/theme.css); mode = dark | light | system; amoled = pure-black dark background.
import { THEMES, DEFAULT_THEME } from './theme-data.js'

export const MODES = [['dark', 'Dark'], ['light', 'Light'], ['system', 'System']]
const dark = matchMedia('(prefers-color-scheme: dark)')
let current = { theme: DEFAULT_THEME, colorMode: 'dark', amoled: false }

const resolve = (m) => (m === 'system' ? (dark.matches ? 'dark' : 'light') : m === 'light' ? 'light' : 'dark')

export function applyTheme(s = {}) {
  const theme = THEMES.some((t) => t[0] === s.theme) ? s.theme : DEFAULT_THEME
  const colorMode = ['dark', 'light', 'system'].includes(s.colorMode) ? s.colorMode : 'dark'
  current = { theme, colorMode, amoled: !!s.amoled }
  const d = document.documentElement, mode = resolve(colorMode)
  d.dataset.theme = theme
  d.dataset.mode = mode
  if (current.amoled) d.dataset.amoled = '1'
  else delete d.dataset.amoled
  d.style.colorScheme = mode
  try {
    localStorage.setItem('ds.theme', theme)
    localStorage.setItem('ds.colorMode', colorMode)
    localStorage.setItem('ds.amoled', current.amoled ? '1' : '0')
  } catch {}
  window.dispatchEvent(new Event('ds:theme'))
}

// "System" follows the OS live
dark.addEventListener('change', () => current.colorMode === 'system' && applyTheme(current))
