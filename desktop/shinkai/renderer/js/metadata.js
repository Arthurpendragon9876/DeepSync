// Metadata, the way the mobile app does it. One global choice of tracker (AniList / MyAnimeList / MangaBaka) drives Home, search and the
// info shown for a title opened from a source; "Source catalogue" swaps Home to what the installed sources list; "Covers & info from tracker"
// decides whether a title opened from a source shows the tracker's page or the source's. The same sheets as Yugen: Preferences,
// Metadata and Library filters.
import { h, icon, S, api, data, go, sheet, toast, saveSettings, switchEl, norm, modeOf } from './core.js'
import { installedSources, srcLabel } from './ext-ui.js'
import { srcId, openSourceTitle } from './srcmedia.js'

export const PROVIDERS = {
  anilist: { name: 'AniList', short: 'AL', desc: 'Browse and your list, signed out or in' },
  mal: { name: 'MyAnimeList', short: 'MAL', desc: 'Rankings and search, no sign-in needed' },
  mangabaka: { name: 'MangaBaka', short: 'MB', desc: 'Comics and novels catalogue' },
}
export const MODE_NAMES = { anime: 'Anime', manga: 'Manga', novel: 'Novel' }

export const supports = (provider, mode) => provider === 'anilist' || provider === 'mal' || (provider === 'mangabaka' && mode !== 'anime')
// The tracker actually in use for [mode]: the chosen one, or AniList when the chosen one has nothing for this mode (MangaBaka has no anime).
export const activeProvider = (mode = S.mode) => (supports(S.settings.metadata, mode) ? S.settings.metadata : 'anilist')
export const catalogueOn = () => !!S.settings.catalogue
export const fromTracker = () => S.settings.fromTracker !== false
export const catalogueSource = (mode = S.mode) => S.settings.catalogueSource?.[mode] || ''
export const filters = () => ({ include: S.settings.homeFilters?.include || [], exclude: S.settings.homeFilters?.exclude || [] })

// ---- titles that come from MyAnimeList / MangaBaka (no AniList id) ----------------------------------------------------------------
export const isMetaId = (id) => typeof id === 'string' && id.startsWith('meta-')
export async function buildMetaMedia(id, mode) {
  const [, provider, ...rest] = id.split('-')
  const media = await api('meta:byId', { provider, id: rest.join('-'), mode })
  if (!media) throw new Error(`${PROVIDERS[provider]?.name || 'The tracker'} has no such title`)
  return media
}

// Listing installed sources may have to start the extension runtime; never let that hold a sheet or a Home row hostage.
const safeSources = (mode, ms = 2500) => Promise.race([installedSources(mode).catch(() => []), new Promise((r) => setTimeout(() => r([]), ms))])

// A source's catalogue item, shaped like a tracker title so cards and the banner can show it.
function catalogMedia(src, it, mode) {
  const cover = it.cover || ''
  return {
    id: srcId({ s: src.id, u: it.url, t: it.title, c: cover }), isSource: true, catalog: { src, item: it }, type: mode === 'anime' ? 'ANIME' : 'MANGA',
    format: mode === 'anime' ? 'TV' : mode === 'novel' ? 'NOVEL' : 'MANGA', title: { romaji: it.title, english: it.title, native: null, userPreferred: it.title },
    coverImage: { large: cover, extraLarge: cover }, bannerImage: null, genres: [], averageScore: null, synonyms: [],
  }
}

// What every card / banner click does: a source title goes through the tracker match, a MyAnimeList / MangaBaka title opens its own page, an AniList one its page.
export function openMedia(m) {
  if (m.catalog) return openSourceTitle(m.catalog.src, m.catalog.item)
  return go(`/media/${modeOf(m)}/${m.id}`)
}

// ---- Home rows ----------------------------------------------------------------------------------------------------------------------
async function catalogueItems(kind, mode, limit) {
  const all = await safeSources(mode, 8000)
  const wanted = catalogueSource(mode)
  const list = wanted ? all.filter((s) => s.id === wanted) : all.slice(0, 4)
  const latest = kind === 'RECENT'
  const lists = await Promise.all(list.map(async (src) => {
    if (latest && !src.supportsLatest) return []
    const r = await api('ext:browse', { sourceId: src.id, kind: latest ? 'latest' : 'popular', query: '', page: kind === 'POPULAR' ? 2 : 1 }).catch(() => null)
    return (r?.list || []).map((it) => catalogMedia(src, it, mode))
  }))
  const out = [], seen = new Set()
  for (let i = 0; out.length < limit && lists.some((l) => i < l.length); i++)
    for (const l of lists) if (l[i] && !seen.has(norm(titleKey(l[i]))) && seen.add(norm(titleKey(l[i])))) out.push(l[i])
  return out.slice(0, limit)
}
const titleKey = (m) => m.title.romaji

// One Home row (TRENDING feeds the banner and the Trending row, POPULAR, RECENT). Mirrors the mobile rules: with the source catalogue on the rows come from the
// sources; otherwise from the tracker, and when the tracker has nothing for a row (MyAnimeList has no recent manga) the sources fill in so it is never missing.
export async function homeRow(kind, mode, limit = 20) {
  const f = filters()
  if (catalogueOn()) return { items: await catalogueItems(kind, mode, limit).catch(() => []), from: 'sources' }
  const provider = activeProvider(mode)
  if (provider === 'anilist' && mode === 'anime' && kind === 'RECENT' && !f.include.length && !f.exclude.length)
    return { items: (await data('anilist:recent')).map((x) => x.media).slice(0, limit), from: provider }
  let items = await data('meta:list', { provider, kind, mode, perPage: limit, include: f.include, exclude: f.exclude }).catch(() => [])
  if (!items.length && provider !== 'anilist') {
    items = await catalogueItems(kind, mode, limit).catch(() => [])
    if (items.length) return { items, from: 'sources' }
    items = await data('meta:list', { provider: 'anilist', kind, mode, perPage: limit, include: f.include, exclude: f.exclude }).catch(() => [])
    return { items, from: 'anilist' }
  }
  return { items, from: provider }
}
export const providerLabel = (from) => (from === 'sources' ? 'your sources' : PROVIDERS[from]?.name || 'AniList')

// ---- Continue / last title (the floating mode button) -------------------------------------------------------------------------------
// The title to resume in [mode]: what you opened last (history), else your newest "Current" tracker entry.
export async function lastTitle(mode) {
  const hist = await api('history:list').catch(() => [])
  const e = (hist || []).find((x) => x.mode === mode)
  if (e) return { id: e.id, mode, title: e.title }
  const list = await data('anilist:list', { mode }).catch(() => [])
  const cur = (Array.isArray(list) ? list : []).filter((x) => x.status === 'CURRENT').sort((a, b) => b.updatedAt - a.updatedAt)[0]
  return cur ? { id: cur.mediaId ?? cur.media?.id, mode, title: cur.media?.title?.userPreferred } : null
}
export const continuePath = (t) => `/media/${t.mode}/${t.id}/${t.mode === 'anime' ? 'episodes' : 'chapters'}?continue=1`

// ---- sheets ---------------------------------------------------------------------------------------------------------------------------
const refreshHome = () => window.dispatchEvent(new Event('ds:refresh'))
const logo = (p) => h('div', { class: 'md-logo ' + p }, PROVIDERS[p].short)
function row({ lead, title, sub, selected, trailing, onclick, accent }) {
  return h('div', { class: 'md-row' + (selected ? ' on' : ''), onclick },
    lead, h('div', { class: 'tx' }, h('b', null, title), sub != null && h('span', { style: accent ? { color: accent } : null }, sub)), trailing || (selected ? icon('check', 20) : null))
}
const switchRow = ({ lead, title, sub, on, onChange }) => {
  const sw = switchEl(on, onChange)
  const r = row({ lead, title, sub, trailing: sw, onclick: (e) => !sw.contains(e.target) && sw.click() })
  return r
}

// "Source catalogue" -> which source's catalogue Home shows.
async function openCataloguePicker() {
  const mode = S.mode
  const sources = await safeSources(mode)
  let close
  const pick = async (id) => { close(); await saveSettings({ catalogueSource: { [mode]: id } }); refreshHome() }
  const body = h('div', { class: 'md-list' },
    row({ lead: icon('grid', 24), title: 'All sources', sub: 'Mix the catalogues of your installed sources', selected: !catalogueSource(mode), onclick: () => pick('') }),
    ...sources.map((s) => row({ lead: icon('puzzle', 24), title: srcLabel(s), sub: s.supportsLatest ? 'Popular and latest' : 'Popular', selected: catalogueSource(mode) === s.id, onclick: () => pick(s.id) })),
    !sources.length && h('p', { class: 'note', style: { padding: '14px 24px' } }, `No ${mode} sources are installed yet. Add one in Extensions.`))
  close = sheet({ title: 'Catalogue source', body, side: 'center' })
}

export async function openMetadataSheet() {
  const mode = S.mode, name = MODE_NAMES[mode]
  const sources = await safeSources(mode)
  const picked = sources.find((s) => s.id === catalogueSource(mode))
  let close
  const apply = async (patch) => { close(); await saveSettings(patch); refreshHome() }
  const effective = activeProvider(mode)
  const body = h('div', { class: 'md-list' },
    h('p', { class: 'note', style: { padding: '0 24px 8px' } }, `Where ${name.toLowerCase()} info comes from`),
    ...Object.keys(PROVIDERS).filter((p) => supports(p, mode)).map((p) =>
      row({ lead: logo(p), title: PROVIDERS[p].name, sub: PROVIDERS[p].desc, selected: !catalogueOn() && effective === p, onclick: () => apply({ metadata: p, catalogue: false }) })),
    switchRow({ lead: icon('image', 24), title: 'Covers & info from tracker', sub: 'Use tracker covers and details', on: fromTracker(), onChange: (v) => apply({ fromTracker: v }) }),
    h('div', { class: 'md-sep' }),
    switchRow({
      lead: icon('puzzle', 24), title: 'Source catalogue', sub: 'Show installed source lists in Home', on: catalogueOn(),
      onChange: (v) => { if (v && !sources.length) toast(`No ${mode} sources are installed yet, so Home will fall back to the tracker`, true); apply({ catalogue: v }) },
    }),
    catalogueOn() && row({ lead: icon('chevR', 24), title: 'Catalogue source', sub: picked ? srcLabel(picked) : 'All sources', onclick: () => { close(); openCataloguePicker() } }))
  close = sheet({ title: `${name} metadata`, body, side: 'center' })
}

const GENRES = ['Action', 'Adventure', 'Comedy', 'Drama', 'Ecchi', 'Fantasy', 'Horror', 'Mahou Shoujo', 'Mecha', 'Music', 'Mystery', 'Psychological', 'Romance', 'Sci-Fi', 'Slice of Life', 'Sports', 'Supernatural', 'Thriller']
export function openFiltersSheet() {
  const inc = new Set(filters().include), exc = new Set(filters().exclude)
  let close
  const chips = h('div', { class: 'md-chips' })
  const draw = () => chips.replaceChildren(...GENRES.map((g) => h('button', {
    class: 'md-chip' + (inc.has(g) ? ' inc' : exc.has(g) ? ' exc' : ''), title: inc.has(g) ? 'Included (click to exclude)' : exc.has(g) ? 'Excluded (click to clear)' : 'Click to include',
    onclick: () => { inc.has(g) ? (inc.delete(g), exc.add(g)) : exc.has(g) ? exc.delete(g) : inc.add(g); draw() },
  }, inc.has(g) ? '+ ' + g : exc.has(g) ? '− ' + g : g)))
  draw()
  const adult = switchRow({ lead: icon('eye', 24), title: 'Show adult content', sub: 'Includes adult titles in Home and search', on: !S.settings.hideAdult, onChange: (v) => saveSettings({ hideAdult: !v }) })
  const body = h('div', { class: 'md-list' },
    h('p', { class: 'note', style: { padding: '0 24px 10px' } }, 'Click a genre to include it, again to exclude it, again to clear it. Applies to the Home banner and rows.'), chips, adult,
    h('div', { style: { display: 'flex', gap: 10, justifyContent: 'flex-end', padding: '16px 24px 6px' } },
      h('button', { class: 'btn', onclick: () => (inc.clear(), exc.clear(), draw()) }, 'Clear'),
      h('button', { class: 'btn primary', onclick: async () => { close(); await saveSettings({ homeFilters: { include: [...inc], exclude: [...exc] } }); refreshHome() } }, 'Apply')))
  close = sheet({ title: 'Library filters', body, side: 'center' })
}

const BANNERS = { spotlight: 'Spotlight', card: 'Card', panels: 'Panels' }
// "Yugen preferences": the floating preferences button's sheet.
export async function openPrefsSheet() {
  const { CAROUSELS } = await import('./views/home.js')
  const mode = S.mode, f = filters(), n = f.include.length + f.exclude.length
  const on = CAROUSELS.length - (S.settings.hiddenCarousels || []).length
  const incognito = switchEl(S.settings.incognito, async (v) => { await saveSettings({ incognito: v }); window.dispatchEvent(new Event('ds:route')) })
  let close
  const card = ({ ico, color, title, sub, trailing, onclick }) => h('div', { class: 'pf-card', onclick },
    h('div', { class: 'pf-ico', style: { color, background: `color-mix(in srgb, ${color} 16%, transparent)` } }, icon(ico, 22)), h('div', { class: 'tx' }, h('b', null, title), h('span', { style: color && sub.accent ? { color } : null }, sub.text ?? sub)), trailing)
  const body = h('div', { class: 'pf-list' },
    card({ ico: 'eyeoff', color: '#c084fc', title: 'Incognito mode', sub: 'Pause reading history tracking', trailing: incognito, onclick: (e) => !incognito.contains(e.target) && incognito.click() }),
    card({ ico: 'filter', color: '#60a5fa', title: 'Library filters', sub: 'Genre and release status filters', trailing: n ? h('span', { class: 'pf-badge' }, `${n} active`) : null, onclick: () => { close(); openFiltersSheet() } }),
    card({ ico: 'puzzle', color: '#34d399', title: 'Metadata source', sub: { text: `Active: ${catalogueOn() ? 'Source catalogue' : PROVIDERS[activeProvider(mode)].name}`, accent: true }, trailing: h('span', { class: 'note' }, 'Configure ›'), onclick: () => { close(); openMetadataSheet() } }),
    card({ ico: 'sliders', color: '#fbbf24', title: 'Home layout', sub: { text: `${BANNERS[S.settings.bannerStyle] || 'Spotlight'} · ${on} carousels on`, accent: true }, trailing: icon('chevR', 20), onclick: () => { close(); go('/settings/home') } }),
    h('div', { class: 'pf-foot' },
      h('button', { class: 'btn', onclick: () => { close(); go('/library') } }, icon('library', 18), 'Local library'),
      h('button', { class: 'btn', onclick: async () => { close(); await api('cache:clear'); refreshHome() } }, icon('sync', 18), 'Refresh'),
      h('button', { class: 'btn', onclick: () => { close(); go('/settings') } }, icon('gear', 18), 'Settings')))
  close = sheet({ title: 'Shinkai preferences', body, side: 'center' })
}
