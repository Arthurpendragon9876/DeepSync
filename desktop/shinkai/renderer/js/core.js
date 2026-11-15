// Core runtime shared by every view. No framework: plain DOM + a tiny hyperscript helper.

// ---- DOM helper ------------------------------------------------------------------------------------------------
export function h(tag, props, ...kids) {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue
    if (k === 'class') el.className = v
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
    else if (k === 'html') el.innerHTML = v
    else if (k === 'ref') v(el)
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v)
    else if (k in el && k !== 'list') el[k] = v
    else el.setAttribute(k, v === true ? '' : v)
  }
  const add = (c) => {
    if (c == null || c === false) return
    if (Array.isArray(c)) c.forEach(add)
    else el.append(c.nodeType ? c : document.createTextNode(String(c)))
  }
  kids.forEach(add)
  return el
}
export const clear = (el) => (el.replaceChildren(), el)

// ---- icons (24px stroke set) --------------------------------------------------------------------------------------
function gear() {
  const n = 8, step = (Math.PI * 2) / n
  const pts = []
  for (let k = 0; k < n; k++) {
    const t = k * step
    for (const [da, r] of [[-0.31, 7.4], [-0.17, 9.8], [0.17, 9.8], [0.31, 7.4]]) pts.push([12 + r * Math.cos(t + da * step * 2), 12 + r * Math.sin(t + da * step * 2)])
  }
  return 'M' + pts.map((p) => p.map((v) => v.toFixed(2)).join(' ')).join('L') + 'zM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z'
}
const P = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  calendar: 'M7 3v3M17 3v3M4 9h16M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z',
  book: 'M12 6.5C10.5 5 8 4.5 4 4.5V18c4 0 6.5.5 8 2 1.5-1.5 4-2 8-2V4.5c-4 0-6.5.5-8 2zM12 6.5V20',
  list: 'M9 6h11M9 12h11M9 18h11M3.5 6.5l1.2 1.2L7 5.5M3.5 12.5l1.2 1.2L7 11.5M3.5 18.5l1.2 1.2L7 17.5',
  compass: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15.8 8.2l-1.9 5.7-5.7 1.9 1.9-5.7z',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  library: 'M4 4h4v16H4zM10 4h4v16h-4zM15.8 5.8l3.5-1 3 14.4-3.5 1z',
  rss: 'M5 11a8 8 0 0 1 8 8M5 4.5A14.5 14.5 0 0 1 19.5 19M5.5 19h.01',
  sync: 'M20 12a8 8 0 0 0-14-5.3L4 9M4 4v5h5M4 12a8 8 0 0 0 14 5.3L20 15M20 20v-5h-5',
  back: 'M19 12H5M11 5l-7 7 7 7',
  puzzle: 'M10 4.5a2 2 0 1 1 4 0V6h4a1 1 0 0 1 1 1v4h-1.5a2 2 0 1 0 0 4H19v4a1 1 0 0 1-1 1h-4v-1.5a2 2 0 1 0-4 0V20H6a1 1 0 0 1-1-1v-4h1.5a2 2 0 1 0 0-4H5V7a1 1 0 0 1 1-1h4z',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeoff: 'M3 3l18 18M10.6 6.1A9.7 9.7 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4M6.5 7.6C3.7 9.3 2 12 2 12s3.5 7 10 7c1.5 0 2.8-.3 4-.8M9.9 9.9a3 3 0 0 0 4.2 4.2',
  image: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 16l5-5 4 4 3-3 6 6M9 9.5h.01',
  novel: 'M4 5.5C6 4.5 9 4.5 12 6c3-1.5 6-1.5 8-.5V19c-2-1-5-1-8 .5-3-1.5-6-1.5-8-.5zM12 6v13.5M7 9.5h2.5M7 12.5h2.5M14.5 9.5H17M14.5 12.5H17',
  manga: 'M3 5h7v14H3zM14 5h7v14h-7zM3 12h7M14 9h7',
  playCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM10 8.8v6.4l5.2-3.2z',
  gear: gear(),
  play: 'M8 5.2v13.6a.6.6 0 0 0 .9.5l11-6.8a.6.6 0 0 0 0-1l-11-6.8a.6.6 0 0 0-.9.5z',
  pause: 'M8 5v14M16 5v14',
  plus: 'M12 5v14M5 12h14', minus: 'M5 12h14', x: 'M6 6l12 12M18 6 6 18', check: 'M5 12.5l4.5 4.5L19 7',
  chevL: 'M15 5l-7 7 7 7', chevR: 'M9 5l7 7-7 7', chevD: 'M5 9l7 7 7-7',
  filter: 'M4 6h16M7 12h10M10 18h4', sliders: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 4v6M9 14v6',
  dots: 'M12 5.5h.01M12 12h.01M12 18.5h.01',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01',
  star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8L3.5 9.7l5.9-.9z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  external: 'M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z', layers: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5',
  film: 'M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM10 9l5 3-5 3z',
  folder: 'M3 7a1 1 0 0 1 1-1h5l2 2h8a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13', fullscreen: 'M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5',
  text: 'M5 6V4h14v2M12 4v16M9 20h6', menu: 'M4 6h16M4 12h16M4 18h16', download: 'M12 4v11M7 11l5 5 5-5M5 20h14',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 20c1-4 4-6 8-6s7 2 8 6', link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3A4 4 0 0 0 11 18.7l1-1',
  bookmark: 'M6 4h12v17l-6-4-6 4z', heart: 'M12 21s-7-4.5-9.5-9A5.5 5.5 0 0 1 12 6.5 5.5 5.5 0 0 1 21.5 12c-2.5 4.5-9.5 9-9.5 9z', shuffle: 'M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5', chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2', bulb: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.4 1 2.5h6c0-1.1.3-1.8 1-2.5A6 6 0 0 0 12 3z', share: 'M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4', sortUp: 'M7 20V5M3 9l4-4 4 4M14 6h7M14 12h5M14 18h3', sortDown: 'M7 4v15M3 15l4 4 4-4M14 6h3M14 12h5M14 18h7',
  pauseFill: 'M7 4.8h3.6v14.4H7zM13.4 4.8H17v14.4h-3.6z',
  replay10: 'M4.5 12a7.5 7.5 0 1 0 2.4-5.5M4.5 4.5v4h4', fwd10: 'M19.5 12a7.5 7.5 0 1 1-2.4-5.5M19.5 4.5v4h-4',
  volume: 'M4 9.5v5h3.5l4.5 3.8V5.7L7.5 9.5zM15.5 9a4.2 4.2 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11', volumeLow: 'M4 9.5v5h3.5l4.5 3.8V5.7L7.5 9.5zM15.5 9a4.2 4.2 0 0 1 0 6', mute: 'M4 9.5v5h3.5l4.5 3.8V5.7L7.5 9.5zM16 9.5l5 5M21 9.5l-5 5',
  fsExit: 'M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5', speed: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 12l4-3.5M7.5 12h.01M12 7.5h.01M16.5 12h.01', pip: 'M3 5h18v14H3zM12 12h7v5h-7z', playFill: 'M7.5 4.8v14.4a.8.8 0 0 0 1.2.7l11.6-7.2a.8.8 0 0 0 0-1.4L8.7 4.1a.8.8 0 0 0-1.2.7z',
  skipB: 'M7 5v14M19 5l-9 7 9 7z', skipF: 'M17 5v14M5 5l9 7-9 7z', rtl: 'M20 6H10a4 4 0 0 0 0 8h2M16 3l-3 3 3 3M12 10v9M16 10v9', wifi: 'M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01',
}
export const icon = (name, size = 20) => h('span', { class: 'ico', html: `<svg width="${size}" height="${size}" viewBox="0 0 24 24"><path d="${P[name] || ''}"/></svg>` })

// ---- app state ---------------------------------------------------------------------------------------------------
export const S = { settings: null, user: null, mode: 'anime', offline: false, library: null }

export async function api(channel, args) {
  const r = await window.deepsync.call(channel, args)
  if (!r.ok) {
    const e = new Error(r.error?.message || 'Request failed')
    e.code = r.error?.code
    throw e
  }
  return r.value
}
// Calls that may fall back to demo data return { data, offline }.
export async function data(channel, args) {
  const r = await api(channel, args)
  if (r && typeof r === 'object' && 'offline' in r) {
    if (r.offline !== S.offline) {
      S.offline = r.offline
      window.dispatchEvent(new Event('ds:offline'))
    }
    return r.data
  }
  return r
}
export async function saveSettings(patch) {
  S.settings = await api('settings:set', patch)
  return S.settings
}

// ---- helpers --------------------------------------------------------------------------------------------------------
export const b64 = (s) => btoa(String.fromCharCode(...new TextEncoder().encode(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
export const mediaUrl = (p) => `${window.deepsync.mediaBase}?p=${b64(p)}`
export const zipUrl = (p, e) => `${window.deepsync.zipBase}?p=${b64(p)}&e=${encodeURIComponent(e)}`
export const pageUrl = (ref) => (ref.kind === 'zip' ? zipUrl(ref.path, ref.entry) : ref.kind === 'file' ? mediaUrl(ref.path) : ref.url)

export function titleOf(m) {
  const t = m.title || {}
  const lang = S.settings?.titleLanguage
  return (lang === 'english' ? t.english : lang === 'native' ? t.native : t.romaji) || t.userPreferred || t.romaji || t.english || 'Untitled'
}
export const coverOf = (m) => m.coverImage?.extraLarge || m.coverImage?.large || ''
export const bannerOf = (m) => m.bannerImage || coverOf(m)
export const modeOf = (m) => (m.type === 'ANIME' ? 'anime' : m.format === 'NOVEL' ? 'novel' : 'manga')
export const unit = (mode) => (mode === 'anime' ? 'Episode' : 'Chapter')
export const totalOf = (m) => m.episodes || m.chapters || m.nextAiringEpisode?.episode - 1 || 0
export const verb = (mode) => (mode === 'anime' ? 'watching' : 'reading')
export const STATUS = {
  CURRENT: (mode) => (mode === 'anime' ? 'Watching' : 'Reading'), PLANNING: () => 'Planning', COMPLETED: () => 'Completed', PAUSED: () => 'Paused', DROPPED: () => 'Dropped', REPEATING: (mode) => (mode === 'anime' ? 'Rewatching' : 'Rereading'),
}
export const STATUS_ICON = { CURRENT: 'play', PLANNING: 'clock', COMPLETED: 'check', PAUSED: 'pause', DROPPED: 'x', REPEATING: 'sync' }
const ACRONYMS = new Set(['TV', 'OVA', 'ONA', 'TV_SHORT'])
export const pretty = (s) => (!s ? '' : ACRONYMS.has(s) ? s.replace('_', ' ') : s.replace(/_/g, ' ').toLowerCase().replace(/(^|\s)\S/g, (c) => c.toUpperCase()))
export const stripHtml = (s) => (s || '').replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
export const ago = (ts) => {
  const s = Math.max(1, Math.floor(Date.now() / 1000 - ts)), u = [[86400, 'd'], [3600, 'h'], [60, 'm']]
  for (const [n, l] of u) if (s >= n) return Math.floor(s / n) + l + ' ago'
  return 'just now'
}
export const fmtTime = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
export const norm = (s) => (s || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')

// ---- the global mode ----------------------------------------------------------------------------------------------------
// One mode (anime / manga / novel) for the whole app. Every page reads S.mode; the floating mode button, and the mode pickers
// on Discover / Search / My lists / Suggestions / Local library, all go through setMode, so changing it anywhere changes it everywhere.
export const MODES = ['anime', 'manga', 'novel']
const routeMode = (path, params, query) => (path.startsWith('/discover/') ? params.mode : ['/search', '/lists', '/suggestions'].includes(path) ? query.mode : path === '/library' ? query.tab : null)
// Opening a page for another mode (a link, Back, a bookmark) makes that mode the current one, so the button never disagrees with the page.
function adoptRouteMode(path, params, query) {
  const m = routeMode(path, params, query)
  if (!MODES.includes(m) || m === S.mode) return
  S.mode = m
  saveSettings({ mode: m })
  window.dispatchEvent(new Event('ds:mode'))
}
const navTo = (target) => (location.hash === '#' + target ? window.dispatchEvent(new Event('ds:refresh')) : go(target))
export async function setMode(mode) {
  if (!MODES.includes(mode)) return
  const [path, qs] = (location.hash.slice(1) || '/').split('?')
  const q = new URLSearchParams(qs || '')
  S.mode = mode
  window.dispatchEvent(new Event('ds:mode'))
  await saveSettings({ mode })
  if (path.startsWith('/discover/')) return navTo('/discover/' + mode)
  if (['/search', '/lists', '/suggestions'].includes(path)) {
    q.set('mode', mode); ['genre', 'format', 'status', 'page'].forEach((k) => q.delete(k))
    return navTo(`${path}?${q}`)
  }
  if (path === '/library') return navTo('/library?tab=' + mode)
  if (path.startsWith('/media/')) return navTo('/') // a title page belongs to one mode
  window.dispatchEvent(new Event('ds:refresh'))
}

// ---- navigation -------------------------------------------------------------------------------------------------------
const routes = []
export const route = (pattern, view) => {
  const keys = []
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '/?$')
  routes.push({ re, keys, view })
}
export const go = (path) => (location.hash = '#' + path)
export const redirect = (path) => location.replace('#' + path)
let leaveFns = []
export const onLeave = (fn) => leaveFns.push(fn)
export const currentPath = () => (location.hash.slice(1) || '/').split('?')[0]
let renderToken = 0

export async function render() {
  const token = ++renderToken
  leaveFns.forEach((f) => f())
  leaveFns = []
  const [path, qs] = (location.hash.slice(1) || '/').split('?')
  const query = Object.fromEntries(new URLSearchParams(qs || ''))
  const view = document.getElementById('view')
  for (const r of routes) {
    const m = r.re.exec(path)
    if (!m) continue
    const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]))
    adoptRouteMode(path, params, query)
    view.replaceChildren(h('div', { class: 'page' }, h('div', { class: 'skeleton', style: { height: '320px' } })))
    view.style.animation = 'none'; void view.offsetWidth; view.style.animation = ''
    let node
    try {
      node = await r.view({ params, query, path })
    } catch (e) {
      node = h('div', { class: 'empty' }, h('h3', null, 'Something went wrong'), h('p', null, e.message), h('button', { class: 'btn', onclick: render }, 'Retry'))
      console.error(e)
    }
    if (token !== renderToken) return
    view.replaceChildren(node)
    document.getElementById('main').scrollTop = 0
    window.dispatchEvent(new Event('ds:route'))
    return
  }
  redirect('/')
}

// ---- overlays: sheets, menus, toasts ------------------------------------------------------------------------------------
const stack = []
export function overlay(node, { onClose } = {}) {
  const root = document.getElementById('overlay')
  const entry = { node, onClose }
  stack.push(entry)
  root.append(node)
  window.dispatchEvent(new Event('ds:overlay'))
  entry.close = () => {
    const i = stack.indexOf(entry)
    if (i < 0) return
    stack.splice(i, 1)
    node.remove()
    onClose?.()
    window.dispatchEvent(new Event('ds:overlay'))
  }
  return entry.close
}
export const overlayCount = () => stack.length
// Closes every open sheet / player / reader (newest first). Called when the route changes, so nothing keeps playing behind the next page.
export const closeOverlays = () => [...stack].reverse().forEach((o) => o.close())
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && stack.length) {
    const top = stack[stack.length - 1]
    if (top.node.dataset.noEsc) return
    top.close()
  }
})

export function sheet({ title, body, side = 'bottom', actions }) {
  let close
  const scrim = h('div', { class: `scrim ${side}`, onmousedown: (e) => e.target === scrim && close() },
    h('div', { class: 'sheet' },
      h('div', { class: 'sh-head' }, h('h3', null, title), h('div', { style: { display: 'flex', gap: '8px' } }, actions, h('button', { class: 'ibtn', style: { background: 'var(--surface-3)' }, onclick: () => close() }, icon('x')))),
      h('div', { class: 'sh-body' }, body)))
  close = overlay(scrim)
  return close
}

export function menu(anchor, items) {
  const r = anchor.getBoundingClientRect()
  const m = h('div', { class: 'menu' })
  const close = () => (m.remove(), document.removeEventListener('mousedown', away, true))
  const away = (e) => !m.contains(e.target) && close()
  for (const it of items) {
    if (it.sep) continue
    m.append(h('div', { class: 'mi', onclick: () => { if (!it.keep) close(); it.run?.(m) } }, h('span', { class: 'l' }, it.icon && icon(it.icon, 18), it.label), it.right))
  }
  ;(anchor.closest?.('.player') || document.body).append(m) // inside the player so it is visible in fullscreen
  const w = m.offsetWidth
  m.style.top = (r.bottom + 8 + m.offsetHeight > innerHeight - 10 ? Math.max(10, r.top - m.offsetHeight - 8) : r.bottom + 8) + 'px'
  m.style.left = Math.max(10, Math.min(r.right - w, innerWidth - w - 10)) + 'px'
  setTimeout(() => document.addEventListener('mousedown', away, true))
  return close
}

export function toast(msg, err = false) {
  const t = h('div', { class: 'toast' + (err ? ' err' : '') }, msg)
  document.body.append(t)
  setTimeout(() => t.remove(), err ? 5000 : 2600)
}

export const switchEl = (on, onChange) => {
  const s = h('button', { class: 'switch' + (on ? ' on' : ''), role: 'switch', 'aria-checked': String(!!on) })
  s.onclick = async () => {
    on = !on
    s.classList.toggle('on', on)
    s.setAttribute('aria-checked', String(on))
    await onChange(on)
  }
  return s
}
export const segmented = (options, value, onChange) => {
  const el = h('div', { class: 'seg' })
  for (const [v, label] of options) {
    const b = h('button', { class: v === value ? 'on' : '', onclick: () => { el.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); onChange(v) } }, label)
    el.append(b)
  }
  return el
}
export const errorBox = (e, retry) => h('div', { class: 'empty' }, h('h3', null, e.code === 'AUTH' ? 'Tracker sign-in required' : 'Could not load'), h('p', null, e.message), retry && h('button', { class: 'btn', onclick: retry }, 'Retry'))
