import { h, icon, S, data, go, errorBox, segmented, clear, setMode } from '../core.js'
import { carousel, posterCard } from '../components.js'
import { homeRow, activeProvider, catalogueOn, providerLabel, PROVIDERS } from '../metadata.js'

const MODES = [['anime', 'Anime'], ['manga', 'Manga'], ['novel', 'Novels']]
const GENRES = ['Action', 'Adventure', 'Comedy', 'Drama', 'Ecchi', 'Fantasy', 'Horror', 'Mahou Shoujo', 'Mecha', 'Music', 'Mystery', 'Psychological', 'Romance', 'Sci-Fi', 'Slice of Life', 'Sports', 'Supernatural', 'Thriller']
const FORMATS = { anime: ['TV', 'TV_SHORT', 'MOVIE', 'OVA', 'ONA', 'SPECIAL'], manga: ['MANGA', 'ONE_SHOT'], novel: [] }
const SORTS = [['TRENDING_DESC', 'Trending'], ['POPULARITY_DESC', 'Popularity'], ['SCORE_DESC', 'Score'], ['START_DATE_DESC', 'Newest'], ['SEARCH_MATCH', 'Best match']]
const STATUSES = [['RELEASING', 'Releasing'], ['FINISHED', 'Finished'], ['NOT_YET_RELEASED', 'Upcoming'], ['HIATUS', 'Hiatus']]
const label = (s) => s.replace(/_/g, ' ').replace(/\b(\w)(\w*)/g, (_, a, b) => a + b.toLowerCase())

export async function discover({ params }) {
  const mode = params.mode
  const page = h('div', { class: 'page', style: { paddingLeft: 0, paddingRight: 0 } })
  const head = h('div', { style: { padding: '0 var(--pad)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 } },
    h('h1', { class: 'page-title', style: { margin: 0 } }, mode === 'anime' ? 'Discover' : mode === 'manga' ? 'Manga' : 'Light novels'),
    h('div', { style: { display: 'flex', gap: 10 } }, segmented(MODES, mode, (m) => setMode(m)), h('button', { class: 'btn', onclick: () => go(`/search?mode=${mode}`) }, icon('search', 18), 'Search & filter')))
  const defs = [
    ['Trending', { sort: 'TRENDING_DESC' }], [mode === 'anime' ? 'Airing now' : 'Releasing now', { sort: 'POPULARITY_DESC', status: 'RELEASING' }],
    ['Popular', { sort: 'POPULARITY_DESC' }], ['Top rated', { sort: 'SCORE_DESC' }], ['Upcoming', { sort: 'POPULARITY_DESC', status: 'NOT_YET_RELEASED' }],
  ]
  // With MyAnimeList / MangaBaka (or the source catalogue) as the metadata source, Discover shows that catalogue's rows instead of AniList's.
  if (catalogueOn() || activeProvider(mode) !== 'anilist') {
    const rows = [['Trending', 'TRENDING'], [mode === 'anime' ? 'Airing now' : 'Releasing now', 'RECENT'], ['Popular', 'POPULAR']]
    const slots = rows.map(() => h('div'))
    page.append(head, h('p', { class: 'note', style: { padding: '0 var(--pad) 8px' } }, `From ${catalogueOn() ? 'your source catalogue' : PROVIDERS[activeProvider(mode)].name}. Change it with the preferences button in the corner.`), ...slots)
    rows.forEach(([title, kind], i) => homeRow(kind, mode, 20).then((r) => r.items.length && slots[i].replaceChildren(carousel(title, r.items, { more: () => go(`/search?mode=${mode}`) }))).catch((e) => slots[i].replaceChildren(errorBox(e))))
    return page
  }
  const slots = defs.map(() => h('div'))
  page.append(head, ...slots)
  defs.forEach(([title, q], i) => data('anilist:browse', { mode, perPage: 20, ...q }).then((r) => r.items.length && slots[i].replaceChildren(carousel(title, r.items, { more: () => go(`/search?mode=${mode}&sort=${q.sort}${q.status ? '&status=' + q.status : ''}`) }))).catch((e) => slots[i].replaceChildren(errorBox(e))))
  return page
}

export async function search({ query }) {
  const f = { mode: query.mode || S.mode, q: query.q || '', genre: query.genre || '', format: query.format || '', status: query.status || '', sort: query.sort || (query.q ? 'SEARCH_MATCH' : 'POPULARITY_DESC') }
  let pageNo = 1
  const grid = h('div', { class: 'grid' })
  const more = h('div', { style: { textAlign: 'center', margin: '26px 0' } })
  const state = h('div', { class: 'note', style: { marginBottom: 14 } })
  const sel = (key, opts, any) => {
    const el = h('select', { class: 'input', 'aria-label': any }, h('option', { value: '' }, any), opts.map(([v, l]) => h('option', { value: v, selected: f[key] === v }, l)))
    el.onchange = () => ((f[key] = el.value), run(true))
    return el
  }
  const q = h('input', { class: 'input', placeholder: 'Search titles…', value: f.q, autofocus: true })
  let t
  q.oninput = () => (clearTimeout(t), (t = setTimeout(() => ((f.q = q.value.trim()), f.q && (f.sort = 'SEARCH_MATCH'), run(true)), 350)))
  const filters = h('div', { class: 'filters' }, q, sel('genre', GENRES.map((g) => [g, g]), 'Any genre'),
    FORMATS[f.mode].length ? sel('format', FORMATS[f.mode].map((x) => [x, label(x)]), 'Any format') : h('div'), sel('status', STATUSES, 'Any status'), sel('sort', SORTS, 'Sort'))
  async function run(reset) {
    if (reset) { pageNo = 1; clear(grid); history.replaceState(null, '', '#/search?' + new URLSearchParams(Object.entries(f).filter(([, v]) => v))) }
    state.textContent = 'Loading…'
    try {
      const prov = activeProvider(f.mode)
      if (prov !== 'anilist' && !catalogueOn() && !f.genre && !f.format && !f.status) { // MyAnimeList / MangaBaka search (no paging, no AniList filters)
        const items = await data(f.q ? 'meta:search' : 'meta:list', f.q ? { provider: prov, query: f.q, mode: f.mode, perPage: 30 } : { provider: prov, kind: 'POPULAR', mode: f.mode, perPage: 30 })
        grid.append(...items.map((m) => posterCard(m)))
        state.textContent = grid.children.length ? `From ${PROVIDERS[prov].name}` : 'No results.'
        more.replaceChildren()
        return
      }
      const r = await data('anilist:browse', { mode: f.mode, search: f.q || undefined, genre_in: f.genre ? [f.genre] : undefined, format: f.format || undefined, status: f.status || undefined, sort: f.sort, page: pageNo, perPage: 30 })
      grid.append(...r.items.map((m) => posterCard(m)))
      state.textContent = grid.children.length ? '' : 'No results.'
      more.replaceChildren(r.hasNext ? h('button', { class: 'btn', onclick: () => (pageNo++, run(false)) }, 'Load more') : '')
    } catch (e) { state.textContent = ''; grid.replaceChildren(errorBox(e, () => run(true))) }
  }
  run(true)
  return h('div', { class: 'page' }, h('h1', { class: 'page-title' }, 'Search', segmented(MODES, f.mode, (m) => setMode(m))), filters, state, grid, more)
}
