import { h, icon, S, api, data, go, toast, titleOf, segmented, setMode } from '../core.js'
import { posterCard } from '../components.js'
import { isSrcId } from '../srcmedia.js'

// Genres from what you read or watched lately and what you favourited (AniList titles only), most common first.
async function tasteOf(mode) {
  const [hist, fav] = await Promise.all([api('history:list'), api('fav:list')])
  const ids = [...new Set([...hist.filter((x) => x.mode === mode), ...fav.items.filter((x) => x.mode === mode)].map((x) => x.id).filter((id) => !isSrcId(String(id))))]
  const known = new Set(ids)
  const counts = new Map(), basis = []
  for (const id of ids.slice(0, 8)) {
    const m = await data('anilist:details', { id }).catch(() => null)
    if (!m) continue
    basis.push(titleOf(m))
    for (const g of m.genres || []) counts.set(g, (counts.get(g) || 0) + 1)
  }
  return { genres: [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map((x) => x[0]), basis, known }
}

// /suggestions  Popular titles in the genres you spend the most time on.
export async function suggestions({ query }) {
  const mode = query.mode || S.mode
  const out = h('div', { class: 'grid' })
  const lead = h('p', { class: 'note' }, 'Looking at what you read and watch…')
  ;(async () => {
    const t = await tasteOf(mode)
    const r = await data('anilist:browse', { mode, ...(t.genres.length ? { genre_in: t.genres } : {}), sort: t.genres.length ? 'POPULARITY_DESC' : 'TRENDING_DESC', perPage: 40 })
    const list = r.items.filter((m) => !t.known.has(m.id))
    lead.textContent = t.genres.length ? `Because of ${t.basis.slice(0, 3).join(', ')}: ${t.genres.join(', ')}.` : 'Read or watch a few things and this gets personal. For now, here is what is trending.'
    out.replaceChildren(...list.map((m) => posterCard(m)))
  })().catch((e) => { lead.textContent = e.message })
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, 'Suggestions', h('div', { style: { marginLeft: 'auto', display: 'flex', gap: '8px' } },
      segmented([['anime', 'Anime'], ['manga', 'Manga'], ['novel', 'Novels']], mode, (m) => setMode(m)),
      h('button', { class: 'btn', onclick: () => surprise(mode) }, icon('shuffle', 18), 'Surprise me'))),
    lead, out)
}

// Opens a random popular title of the current type.
export async function surprise(mode = S.mode) {
  try {
    let r = await data('anilist:browse', { mode, sort: 'POPULARITY_DESC', perPage: 25, page: 1 + Math.floor(Math.random() * 12) })
    if (!r.items.length) r = await data('anilist:browse', { mode, sort: 'POPULARITY_DESC', perPage: 25, page: 1 })
    const m = r.items[Math.floor(Math.random() * r.items.length)]
    if (!m) throw new Error('Nothing found, try again')
    go(`/media/${mode}/${m.id}`)
  } catch (e) { toast(e.message, true) }
}
