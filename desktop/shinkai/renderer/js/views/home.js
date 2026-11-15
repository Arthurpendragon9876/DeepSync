import { h, icon, S, api, data, go, errorBox, verb, unit, coverOf, menu, setMode, MODES } from '../core.js'
import { surprise } from './suggestions.js'
import { hero, wideCard, carousel, scroller, posterCard } from '../components.js'
import { homeRow, providerLabel, MODE_NAMES } from '../metadata.js'

export const CAROUSELS = [
  ['banner', 'Banner', 'Hero slideshow at the top of Home'],
  ['continue', 'Continue cards', 'Next episode / chapter of what you are tracking'],
  ['genres', 'Genre shortcuts', 'The row of genre links under the banner'],
  ['tracker', 'Currently watching / reading', 'Your AniList “Current” list'],
  ['local', 'Local library', 'Titles found in your library folders'],
  ['recent', 'Recently released', 'Newly aired or newly started titles'],
  ['trending', 'Trending', 'Trending on your metadata source'],
  ['popular', 'Popular', 'All-time popularity on your metadata source'],
]
const GENRES = ['Action', 'Adventure', 'Comedy', 'Drama', 'Ecchi', 'Fantasy', 'Mystery', 'Romance', 'Sci-Fi', 'Slice of Life', 'Supernatural']

export async function home(ctx) {
  const mode = S.mode
  const hidden = new Set(S.settings.hiddenCarousels)
  const on = (k) => !hidden.has(k)
  const page = h('div', { class: 'home' })
  const heroBox = h('div'), contBox = h('div'), genreBox = h('div'), rows = h('div')
  const slot = Object.fromEntries(['tracker', 'local', 'recent', 'trending', 'popular'].map((k) => [k, h('div')]))
  rows.append(...Object.values(slot))
  page.append(heroBox, contBox, genreBox, rows)
  const refresh = () => window.dispatchEvent(new Event('ds:refresh'))

  const MODE_ICON = { anime: 'playCircle', manga: 'manga', novel: 'novel' }
  const modePill = h('button', { class: 'mode-pill', title: 'Switch mode', onclick: () => menu(modePill, MODES.map((m) => ({ icon: MODE_ICON[m], label: MODE_NAMES[m], right: m === S.mode ? icon('check', 18) : undefined, run: () => m !== S.mode && setMode(m) }))) },
    icon(MODE_ICON[mode], 18), MODE_NAMES[mode].toUpperCase(), icon('chevD', 16))
  const tools = [
    modePill,
    h('button', { class: 'ibtn', title: 'Local library', onclick: () => go('/library') }, icon('library')),
    h('button', { class: 'ibtn', title: 'Suggestions', onclick: () => go('/suggestions?mode=' + mode) }, icon('bulb')),
    h('button', { class: 'ibtn', title: 'Surprise me', onclick: () => surprise(mode) }, icon('shuffle')),
    h('button', { class: 'btn ghost', onclick: async () => { await api('cache:clear'); refresh() } }, icon('sync', 18), 'Refresh'),
  ]

  // Every block loads on its own so one slow request never holds up the page (same idea as Yugen's HomeViewModel).
  const safe = async (box, fn) => {
    try { await fn() } catch (e) { box.replaceChildren(errorBox(e, () => window.dispatchEvent(new Event('ds:refresh')))) }
  }

  // Rows come from the chosen metadata source (AniList / MyAnimeList / MangaBaka) or the source catalogue, see metadata.js
  const trendingRow = homeRow('TRENDING', mode, 24)
  const trendingP = trendingRow.then((r) => r.items)
  const listP = data('anilist:list', { mode }).then((r) => r || [])
  const needsLogin = !S.user && !S.settings.demo

  // 1a) hero banner: always Trending (never reading history)
  safe(heroBox, async () => {
    const trending = await trendingP
    const from = (await trendingRow).from
    const items = trending.slice(0, 8).map((media, i) => ({ media, label: `#${i + 1} ${from === 'sources' ? 'Popular' : 'Trending'} on ${providerLabel(from)}` }))
    if (!items.length) return heroBox.replaceChildren(h('div', { class: 'empty', style: { paddingTop: '120px' } }, h('h3', null, 'Nothing here yet'), h('p', null, 'No titles could be loaded.'), h('div', { style: { marginTop: 16 } }, tools)))
    if (on('banner')) heroBox.replaceChildren(hero(items, { style: S.settings.bannerStyle, autoRotate: S.settings.autoRotate, tools }))
    else heroBox.replaceChildren(h('div', { class: 'page', style: { paddingBottom: 0, display: 'flex', justifyContent: 'space-between', alignItems: 'center' } }, h('h1', { class: 'page-title', style: { margin: 0 } }, 'Home'), h('div', { style: { display: 'flex', gap: 8 } }, tools)))
  })

  // 1b) Continue cards: your tracked titles, with arrows + drag-to-scroll
  safe(contBox, async () => {
    const list = await listP.catch(() => [])
    const current = (Array.isArray(list) ? list : []).filter((e) => e.status === 'CURRENT').sort((a, b) => b.updatedAt - a.updatedAt)
    if (current.length && on('continue')) contBox.replaceChildren(scroller(h('div', { class: 'row-wide' }, current.slice(0, 12).map(wideCard)), 'wide-car'))
    else if (!current.length && needsLogin && on('continue'))
      contBox.replaceChildren(h('div', { class: 'page', style: { paddingBottom: 0 } }, h('div', { class: 'card', style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 20 } },
        h('div', null, h('b', null, 'Track your progress'), h('div', { class: 'note' }, 'Sign in to AniList to see your Continue row, or add a library folder to play local files.')),
        h('div', { style: { display: 'flex', gap: 8 } }, h('button', { class: 'btn primary', onclick: () => go('/settings/trackers') }, 'Sign in'), h('button', { class: 'btn', onclick: () => go('/settings/library') }, 'Add library')))))
  })

  if (on('genres')) genreBox.append(h('div', { class: 'chips-row' }, GENRES.map((g) => h('a', { onclick: () => go(`/search?mode=${mode}&genre=${encodeURIComponent(g)}`) }, g))))

  // 2) rows
  const addRow = (key, node) => slot[key].replaceChildren(node)
  if (on('tracker')) safe(slot.tracker, async () => {
    const list = await listP
    const cur = (Array.isArray(list) ? list : []).filter((e) => e.status === 'CURRENT').sort((a, b) => b.updatedAt - a.updatedAt)
    if (cur.length) addRow('tracker', carousel(`Currently ${verb(mode)}`, cur, { more: () => go('/lists'), render: (e) => posterCard(e.media, { entry: e, sub: `${unit(mode)} ${e.progress + 1} next` }) }))
  })
  if (on('local')) api('library:get').then((lib) => {
    const items = lib[mode] || []
    if (items.length) addRow('local', carousel('Local library', items, { more: () => go('/library'), render: (x) => h('div', { class: 'poster', onclick: () => go('/library?tab=' + mode) }, h('div', { class: 'cover', style: { display: 'grid', placeItems: 'center', padding: 12, textAlign: 'center', fontWeight: 700 } }, x.title), h('div', { class: 'name' }, x.title), h('div', { class: 'sub' }, `${x.files.length} file${x.files.length === 1 ? '' : 's'}`)) }))
  }).catch(() => {})
  if (on('recent')) safe(slot.recent, async () => {
    const { items } = await homeRow('RECENT', mode, 20)
    if (items.length) addRow('recent', carousel('Recently released', items, { more: () => go('/updates') }))
  })
  trendingP.then((t) => {
    const skip = on('banner') ? 8 : 0
    if (on('trending') && t.length > skip) addRow('trending', carousel('Trending', t.slice(skip), { more: () => go(`/discover/${mode}`) }))
  }).catch(() => {})
  if (on('popular')) homeRow('POPULAR', mode, 20).then((r) => r.items.length && addRow('popular', carousel('Popular', r.items, { more: () => go(`/search?mode=${mode}&sort=POPULARITY_DESC`) }))).catch(() => {})

  return page
}
