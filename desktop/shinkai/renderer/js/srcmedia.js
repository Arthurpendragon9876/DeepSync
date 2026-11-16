// Titles opened straight from a source (Extensions > Browse). Same details page, chapter page and episode page as a tracker title;
// the page data comes from the source instead of AniList unless "Metadata source" is set to Tracker and a confident AniList match exists.
import { api, data, go, h, icon, sheet, toast, norm, S, titleOf, coverOf, stripHtml } from './core.js'
import { linkKey, setLink, itemCard } from './ext-ui.js'
import { fromTracker, activeProvider } from './metadata.js'

const modeOf = (src) => (src.type === 'anime' ? 'anime' : src.type === 'novel' ? 'novel' : 'manga')
const toB64 = (o) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const fromB64 = (t) => JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(t.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))))

export const isSrcId = (id) => typeof id === 'string' && id.startsWith('src-')
export const srcId = (ref) => 'src-' + toB64(ref) // ref: { s: sourceId, u: url, t: title, c: cover }
export const srcRefOf = (id) => fromB64(String(id).slice(4))
export const srcPath = (mode, ref) => `/media/${mode}/${srcId(ref)}`

const STATUS = { 1: 'RELEASING', 2: 'FINISHED', 3: 'FINISHED', 4: 'FINISHED', 5: 'CANCELLED', 6: 'HIATUS', ongoing: 'RELEASING', completed: 'FINISHED', hiatus: 'HIATUS', cancelled: 'CANCELLED' }
const FORMAT = { manga: 'MANGA', anime: 'TV', novel: 'NOVEL' }

// A media object shaped like an AniList one, built from what the source says about the title.
export async function buildSourceMedia(id, mode) {
  const ref = srcRefOf(id)
  const d = await api('ext:detail', { sourceId: ref.s, media: { title: ref.t, url: ref.u, cover: ref.c || '' } }).catch(() => ({}))
  const title = d.title || ref.t || 'Untitled'
  const cover = d.cover || ref.c || ''
  const total = (d.chapters || []).length
  return {
    id, isSource: true, srcRef: ref, type: mode === 'anime' ? 'ANIME' : 'MANGA', format: FORMAT[mode], status: STATUS[String(d.status).toLowerCase()] || STATUS[d.status] || null,
    title: { romaji: title, english: title, native: null, userPreferred: title }, synonyms: [],
    coverImage: { large: cover, extraLarge: cover }, bannerImage: null, description: d.description || '', genres: d.genre || [], tags: [],
    averageScore: null, episodes: mode === 'anime' ? total || null : null, chapters: mode === 'anime' ? null : total || null,
    studios: { nodes: [d.author || d.artist ? { name: [d.author, d.artist && d.artist !== d.author ? d.artist : ''].filter(Boolean).join(' · ') } : null].filter(Boolean) },
    characters: { edges: [] }, relations: { edges: [] }, recommendations: { nodes: [] },
  }
}

// Click on a source title. With "Covers & info from tracker" on, the chosen metadata provider (AniList / MyAnimeList / MangaBaka) is searched by
// name and the best entry that also agrees on kind (manga / manhwa / manhua, Boys' Love) opens as the title's page; the page opens already
// linked to the source you came from. No confident match (or the toggle is off): the source's own page.
export async function openSourceTitle(src, item) {
  const mode = modeOf(src)
  const ref = { s: src.id, u: item.url, t: item.title, c: item.cover }
  if (fromTracker()) {
    const hit = await api('meta:resolve', { provider: activeProvider(mode), names: [item.title], sourceName: src.name, mode }).catch(() => null)
    if (hit) {
      await setLink(linkKey(src.id, hit.id), item.url)
      return go(`/media/${mode}/${hit.id}`)
    }
  }
  go(srcPath(mode, ref))
}

// "Find on tracker" for a source-only title: search AniList and open (and link) the chosen result.
export function findOnTracker({ media, mode }) {
  const q = h('input', { class: 'input', value: titleOf(media), placeholder: 'Search AniList…' })
  const out = h('div', { class: 'grid', style: { marginTop: '14px' } })
  let close
  const run = async () => {
    out.replaceChildren(h('div', { class: 'skeleton', style: { height: '120px', gridColumn: '1/-1' } }))
    const r = await data('anilist:browse', { mode, search: q.value.trim(), sort: 'SEARCH_MATCH', perPage: 12 }).catch(() => ({ items: [] }))
    out.replaceChildren(...(r.items.length ? r.items.map((m) => itemCard({ title: titleOf(m), cover: coverOf(m) }, async () => {
      if (media.srcRef) await setLink(linkKey(media.srcRef.s, m.id), media.srcRef.u)
      close(); toast('Linked to AniList'); go(`/media/${mode}/${m.id}`)
    })) : [h('div', { class: 'note' }, 'No results.')]))
  }
  q.addEventListener('keydown', (e) => e.key === 'Enter' && run())
  close = sheet({ title: 'Find this title on AniList', body: h('div', null, h('div', { style: { display: 'flex', gap: '8px' } }, q, h('button', { class: 'btn primary', onclick: run }, icon('search', 18))), h('p', { class: 'note', style: { marginTop: '8px' } }, 'Tracking, scores and lists need an AniList title. Reading progress is saved on this device either way.'), out) })
  run()
}
