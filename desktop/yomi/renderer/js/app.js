import { mountFab } from './fab.js'
import { h, S, api, data, route, render, go, redirect, icon, closeOverlays, toast } from './core.js'
import { openMangaReader, openNovelReader } from './overlays.js'
import { buildSidebar } from './components.js'
import { applyTheme } from './theme.js'
import { dismissSplash, setStartupAnimation } from './splash.js'
import { home } from './views/home.js'
import { discover, search } from './views/browse.js'
import { lists, schedule, updates } from './views/lists.js'
import { favourites } from './views/favourites.js'
import { history } from './views/history.js'
import { stats } from './views/stats.js'
import { suggestions } from './views/suggestions.js'
import { details, episodesPage, chaptersRoute } from './views/details.js'
import { library } from './views/library.js'
import { settings } from './views/settings.js'
import { extensions } from './views/extensions.js'
import { source } from './views/source.js'

route('/', home)
route('/schedule', schedule)
route('/updates', updates)
route('/lists', lists)
route('/manga', () => redirect('/discover/manga'))
route('/discover/:mode', discover)
route('/search', search)
route('/library', library)
route('/media/:type/:id', details)
route('/media/:type/:id/episodes', episodesPage)
route('/media/:type/:id/chapters', chaptersRoute)
route('/favourites', favourites)
route('/history', history)
route('/stats', stats)
route('/suggestions', suggestions)
route('/settings', settings)
route('/settings/:tab', settings)
route('/extensions', extensions)
route('/source/:id', source)

const WIN = window.deepsync.platform === 'win32'

// Windows: the caption buttons are the OS's own (titleBarOverlay, so the maximise button shows Snap Layouts). The page only draws the drag area,
// and the main process tells it which window material (Mica / Acrylic / solid) and accent colour are active.
function applyWinInfo(i) {
  if (!i) return
  const b = document.body
  b.classList.add('win')
  b.classList.toggle('material', i.material !== 'none')
  b.dataset.material = i.material
  document.documentElement.style.setProperty('--tb-h', i.titlebarHeight + 'px')
  if (i.accent) { b.style.setProperty('--accent', i.accent.rgb); b.style.setProperty('--on-accent', i.accent.on) }
  else { b.style.removeProperty('--accent'); b.style.removeProperty('--on-accent') }
}

function titlebar() {
  const bar = document.getElementById('titlebar')
  if (WIN) {
    bar.classList.remove('hidden')
    bar.replaceChildren(h('img', { class: 'tb-ico', src: 'assets/icon.png', alt: '' }), h('span', null, 'Yomi'))
    return
  }
  bar.classList.toggle('hidden', !S.settings.customTitlebar)
  if (!S.settings.customTitlebar) return
  const w = window.deepsync.win
  bar.replaceChildren('Yomi', h('div', { class: 'wc' },
    h('button', { class: 'min', 'aria-label': 'Minimise', onclick: () => w.action('min') }), h('button', { class: 'max', 'aria-label': 'Maximise', onclick: () => w.action('max') }), h('button', { class: 'cls', 'aria-label': 'Close', onclick: () => w.action('close') })))
}

// Badge on the Schedule icon = episodes from your "Watching" list that aired in the last 24 h.
async function badge() {
  const el = document.getElementById('sched-badge')
  if (!el) return
  try {
    const [recent, list] = await Promise.all([data('anilist:recent'), data('anilist:list', { mode: 'anime' })])
    const mine = new Set((list || []).filter((e) => e.status === 'CURRENT').map((e) => e.mediaId))
    const n = recent.filter((r) => mine.has(r.media.id) && Date.now() / 1000 - r.airingAt < 86400).length
    el.textContent = n
    el.style.display = n ? '' : 'none'
  } catch { el.style.display = 'none' }
}

let pill
function offlinePill() {
  pill?.remove()
  if (!S.offline) return
  pill = h('div', { class: 'offline-pill' }, icon('wifi', 16), S.settings.demo ? 'Demo mode: sample data' : 'Offline: showing sample data')
  document.body.append(pill)
}

async function boot() {
  if (WIN) applyWinInfo(await window.deepsync.win.info().catch(() => null))
  S.settings = await api('settings:get')
  S.mode = S.settings.mode
  applyTheme(S.settings)
  setStartupAnimation(S.settings.startupAnimation)
  S.user = await api('anilist:viewer').catch(() => null)
  titlebar()
  buildSidebar()
  mountFab()
  S.offline = S.settings.demo
  offlinePill()
  badge()
}

window.addEventListener('hashchange', () => { closeOverlays(); render() })
window.addEventListener('ds:refresh', () => render())
window.addEventListener('ds:offline', offlinePill)
window.addEventListener('ds:boot', async () => { await boot(); render() })
window.addEventListener('ds:user', () => badge())

// Things the main process hands over on Windows: tray / jump-list routes, aniyomi:// links, and .cbz / .epub files opened from Explorer.
function winHandlers() {
  const w = window.deepsync.win
  w.onInfo(applyWinInfo)
  w.onNavigate((p) => go(p))
  w.onToast((m) => toast(m))
  w.onUpdate((e) => e.type === 'downloaded' && toast(`Yomi ${e.version} downloaded. It installs when you close the app.`))
  w.onOpenFile(({ path, kind }) => {
    const title = path.split(/[\\/]/).pop().replace(/\.[^.]+$/, '')
    if (kind === 'epub') return openNovelReader({ path, title })
    openMangaReader({ title, chapters: [{ number: 1, label: title, sub: '', load: () => api('library:pages', { file: { path } }) }] })
  })
  w.ready()
}

boot().then(() => {
  location.hash ? render() : go('/')
  if (WIN) winHandlers()
}).finally(() => dismissSplash())
