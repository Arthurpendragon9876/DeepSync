'use strict'
// Pure helpers for the Windows layer. No Electron imports, so they are unit-tested on any OS.
const fs = require('fs')
const path = require('path')

// "10.0.22631" -> 22631. Windows 11 starts at build 22000; setBackgroundMaterial (Mica / Acrylic / Tabbed) needs 22H2 = 22621.
const windowsBuild = (release) => Number((/^\d+\.\d+\.(\d+)/.exec(String(release || '')) || [])[1]) || 0
const isWin11 = (release) => windowsBuild(release) >= 22000
const supportsMaterial = (release) => windowsBuild(release) >= 22621

// Settings.windows.material is 'auto' | 'mica' | 'acrylic' | 'tabbed' | 'none'.
// Returns the material to apply, or 'none' when the OS cannot draw it or the user switched transparency off in Windows.
function pickMaterial({ choice = 'auto', release, transparency = true, highContrast = false }) {
  if (!supportsMaterial(release) || !transparency || highContrast) return 'none'
  if (choice === 'auto') return 'mica'
  return ['mica', 'acrylic', 'tabbed', 'none'].includes(choice) ? choice : 'mica'
}

// Output of `reg query ...\Personalize /v EnableTransparency`. Missing value means transparency is on (the Windows default).
const parseTransparency = (out) => {
  const m = /EnableTransparency\s+REG_DWORD\s+0x([0-9a-f]+)/i.exec(String(out || ''))
  return m ? parseInt(m[1], 16) !== 0 : true
}

// 'rrggbbaa' from systemPreferences.getAccentColor() -> { rgb: '169 187 255', on: '#0b1233' } (same shape as the CSS variables).
function accentVars(hex) {
  const m = /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(String(hex || ''))
  if (!m) return null
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16))
  const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
  return { rgb: `${r} ${g} ${b}`, on: lum > 0.55 ? '#0b0b10' : '#ffffff' }
}

// Taskbar progress from the extension runtime's setup state. -1 hides it, 2 is the indeterminate "marquee" mode.
function progressFor(state) {
  if (!state) return -1
  if (state.phase === 'downloading') return state.progress > 0 && state.progress < 1 ? state.progress : 2
  if (state.phase === 'extracting') return 2
  return -1
}

const ROUTE_RE = /^\/[A-Za-z0-9_\-./:%]*$/
const FILE_KINDS = { '.cbz': 'comic', '.epub': 'epub' }

// Everything Windows can hand us on the command line: `--open=/route` (jump list / tray), an aniyomi:// link, or a file to open.
function parseLaunchArgs(argv, exists = fs.existsSync) {
  const out = { route: null, repo: null, file: null }
  for (const a of (argv || []).slice(1)) {
    const s = String(a)
    let m
    if ((m = /^--open=(.+)$/.exec(s))) {
      if (ROUTE_RE.test(m[1]) && !m[1].includes('..')) out.route = m[1]
    } else if (/^(aniyomi|tachiyomi|mihon):\/\/add-repo\?url=/i.test(s)) out.repo = s.replace(/\/+$/, '')
    else if (FILE_KINDS[path.extname(s).toLowerCase()] && !s.startsWith('-') && exists(s)) out.file = { path: path.resolve(s), kind: FILE_KINDS[path.extname(s).toLowerCase()] }
  }
  return out
}

const routeFor = (h) => (h && h.mode && h.id != null ? `/media/${h.mode}/${h.id}` : null)

// Jump list model: static tasks plus the five most recent titles. Pure data; integration.js turns it into Electron items.
function jumpListModel(history, max = 5) {
  const recent = (history || [])
    .filter((h) => routeFor(h) && h.title)
    .sort((a, b) => (b.at || 0) - (a.at || 0))
    .slice(0, max)
    .map((h) => ({ title: String(h.title).slice(0, 60), description: h.label ? `${h.label}` : `Open ${h.title}`.slice(0, 80), route: routeFor(h) }))
  const tasks = [
    { title: 'Local library', description: 'Open your scanned library', route: '/library' },
    { title: 'Extensions', description: 'Sources and the Java runtime', route: '/extensions' },
    { title: 'Settings', description: 'Open Yomi settings', route: '/settings' },
  ]
  return { recent, tasks }
}

// Keep a saved window rectangle fully reachable: pulled inside the work area (taskbar excluded) of the display it mostly sits on.
function fitToWorkArea(b, wa, min = { width: 900, height: 600 }) {
  const width = Math.max(min.width, Math.min(b.width, wa.width))
  const height = Math.max(min.height, Math.min(b.height, wa.height))
  const x = Math.min(Math.max(b.x, wa.x), wa.x + wa.width - width)
  const y = Math.min(Math.max(b.y, wa.y), wa.y + wa.height - height)
  return { x, y, width, height }
}

// What the main process needs from the renderer's theme: the colour scheme (for system menus / Mica), the solid window colour used when no
// material is drawn, and the caption-button symbol colour. Anything malformed falls back to the default dark Sky look.
const DEFAULT_LOOK = { mode: 'dark', solid: '#101418', symbol: '#dfe3e9' }
const HEX6 = /^#[0-9a-f]{6}$/i
function lookVars(l) {
  const o = l && typeof l === 'object' ? l : {}
  return {
    mode: ['dark', 'light', 'system'].includes(o.mode) ? o.mode : DEFAULT_LOOK.mode,
    solid: HEX6.test(o.solid) ? o.solid.toLowerCase() : DEFAULT_LOOK.solid,
    symbol: HEX6.test(o.symbol) ? o.symbol.toLowerCase() : DEFAULT_LOOK.symbol,
  }
}

module.exports = { lookVars, DEFAULT_LOOK, windowsBuild, isWin11, supportsMaterial, pickMaterial, parseTransparency, accentVars, progressFor, parseLaunchArgs, jumpListModel, fitToWorkArea, routeFor, FILE_KINDS }
