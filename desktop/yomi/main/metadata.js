'use strict'
// Metadata providers beyond AniList, ported from the mobile app (MALRepository.metaList / MangaBakaRepository.metaList /
// MetadataMatcher). Everything is turned into the same shape AniList returns, so Home, the hero, cards and the details page
// work on a MyAnimeList or MangaBaka title exactly like on an AniList one. No Electron imports: testable with a fake fetch.
const { similarity } = require('./anilist')

const MAL_API = 'https://api.myanimelist.net/v2'
const MAL_WEB = 'https://myanimelist.net'
const MB_API = 'https://api.mangabaka.org/v1'
const MB_WEB = 'https://mangabaka.org'
// The app's own public MAL client id (same one the mobile app ships). It only allows the public, read-only catalogue calls.
const MAL_CLIENT_ID = '6cd8e6349e9a36bc1fc1ab97703c9fd1'
const MIN = 60 * 1000

const NOVEL_TYPES = new Set(['novel', 'light_novel'])
const MAL_STATUS = { currently_airing: 'RELEASING', finished_airing: 'FINISHED', not_yet_aired: 'NOT_YET_RELEASED', currently_publishing: 'RELEASING', finished: 'FINISHED', not_yet_published: 'NOT_YET_RELEASED', on_hiatus: 'HIATUS' }
const MAL_FORMAT = { tv: 'TV', movie: 'MOVIE', ova: 'OVA', ona: 'ONA', special: 'SPECIAL', music: 'MUSIC', manga: 'MANGA', manhwa: 'MANGA', manhua: 'MANGA', one_shot: 'ONE_SHOT', doujinshi: 'MANGA', novel: 'NOVEL', light_novel: 'NOVEL', oel: 'MANGA' }
const MB_STATUS = { releasing: 'RELEASING', ongoing: 'RELEASING', completed: 'FINISHED', finished: 'FINISHED', cancelled: 'CANCELLED', hiatus: 'HIATUS', upcoming: 'NOT_YET_RELEASED' }
const COUNTRY = { manhwa: 'KR', manhua: 'CN' }

const text = (v) => (typeof v === 'string' && v.trim() ? v.trim() : null)
const uniq = (a) => [...new Set(a.filter(Boolean))]
const mediaOf = (provider, mode, id) => `meta-${provider}-${id}`

// Same helper the details page expects: an object shaped like an AniList media.
function shape({ provider, mode, rawId, title, names, cover, genres, adult, score10, url, description, format, status, year, episodes, chapters, country, tags = [], english, native }) {
  const novel = mode === 'novel'
  return {
    id: mediaOf(provider, mode, rawId), isMeta: true, meta: { provider, id: rawId, mode, names: uniq([title, ...names]), country: country || null, tags },
    type: mode === 'anime' ? 'ANIME' : 'MANGA', format: format || (mode === 'anime' ? 'TV' : novel ? 'NOVEL' : 'MANGA'), status: status || null, siteUrl: url,
    title: { romaji: title, english: english || title, native: native || null, userPreferred: title }, synonyms: uniq(names).filter((n) => n !== title),
    coverImage: { large: cover || '', extraLarge: cover || '' }, bannerImage: null, description: description || '', genres, tags: tags.map((t) => ({ name: t, rank: 0 })),
    averageScore: score10 ? Math.round(score10 * 10) : null, seasonYear: year || null, startDate: year ? { year } : null, isAdult: !!adult,
    episodes: mode === 'anime' ? episodes || null : null, chapters: mode === 'anime' ? null : chapters || null,
    studios: { nodes: [] }, characters: { edges: [] }, relations: { edges: [] }, recommendations: { nodes: [] },
  }
}

function fromMal(node, mode) {
  const alt = node.alternative_titles || {}
  const type = String(node.media_type || '')
  const names = [alt.en, alt.ja, ...(alt.synonyms || [])].filter(text)
  return shape({
    provider: 'mal', mode, rawId: node.id, title: node.title, names, english: text(alt.en), native: text(alt.ja),
    cover: node.main_picture?.large || node.main_picture?.medium, genres: (node.genres || []).map((g) => g.name).filter(Boolean), adult: node.nsfw === 'black',
    score10: node.mean > 0 ? node.mean : null, url: `${MAL_WEB}/${mode === 'anime' ? 'anime' : 'manga'}/${node.id}`, description: text(node.synopsis),
    format: MAL_FORMAT[type], status: MAL_STATUS[node.status], year: Number(String(node.start_date || '').slice(0, 4)) || null,
    episodes: node.num_episodes, chapters: node.num_chapters, country: COUNTRY[type] || (type === 'manga' || type === 'light_novel' || type === 'novel' ? 'JP' : null),
  })
}

function fromMangaBaka(j, mode) {
  const labels = (a) => (Array.isArray(a) ? a : []).map((x) => (typeof x === 'string' ? x : x?.name)).filter((x) => text(x) && !String(x).startsWith('{'))
  const secondary = (Array.isArray(j.secondary_titles) ? j.secondary_titles : []).map((x) => (typeof x === 'string' ? x : x?.title)).filter(text)
  const title = text(j.title) || text(j.romanized_title) || text(j.native_title) || `#${j.id}`
  const rating = String(j.content_rating || '')
  const type = String(j.type || '')
  return shape({
    provider: 'mangabaka', mode, rawId: j.id, title, names: [j.romanized_title, j.native_title, ...secondary].filter(text), native: text(j.native_title),
    cover: j.cover?.x250?.x1 || j.cover?.raw?.url || null, genres: labels(j.genres), tags: labels(j.tags), adult: rating === 'pornographic' || rating === 'erotica',
    score10: j.rating > 0 ? j.rating / 10 : null, url: `${MB_WEB}/${j.id}`, description: text(j.description), format: type === 'novel' ? 'NOVEL' : 'MANGA',
    status: MB_STATUS[String(j.status || '').toLowerCase()], year: Number(j.year) || null, chapters: Number(j.final_chapter || j.total_chapters) || null,
    country: COUNTRY[type] || null,
  })
}

// ---- matching a source title to a tracker entry (MetadataMatcher) -----------------------------------------------------------------
const hangul = /[\u1100-\u11FF\u3130-\u318F\uAC00-\uD7AF]/
const kana = /[\u3040-\u30FF]/
const BL = /boys'? love|\bbl\b|yaoi|shounen ai/i
const GL = /girls'? love|\bgl\b|yuri|shoujo ai/i

// Facts that tell same-named titles apart: every name, country (KR manhwa / CN manhua / JP manga) and BL / GL.
function profileOf({ names = [], tags = [], sourceName = '' }) {
  const labels = tags.map((t) => String(t).trim().toLowerCase())
  const sn = String(sourceName).toLowerCase()
  const country = labels.some((l) => l === 'manhwa' || l === 'korean') || sn.includes('manhwa') ? 'KR'
    : labels.some((l) => l === 'manhua' || l === 'chinese' || l === 'donghua') || sn.includes('manhua') ? 'CN'
      : labels.some((l) => l === 'manga' || l === 'japanese') ? 'JP' : null
  const first = names[0] || ''
  return { names: uniq(names).slice(0, 8), country, scriptCountry: hangul.test(first) ? 'KR' : kana.test(first) ? 'JP' : null, boysLove: tags.some((t) => BL.test(t)), girlsLove: tags.some((t) => GL.test(t)), hasTags: tags.length > 0 }
}

function candidateInfo(m) {
  const names = uniq([m.title?.romaji, m.title?.english, m.title?.native, m.title?.userPreferred, ...(m.synonyms || [])])
  const tagNames = [...(m.genres || []), ...(m.tags || []).map((t) => t.name)]
  const co = m.countryOfOrigin || m.meta?.country || null
  const country = co === 'TW' || co === 'HK' ? 'CN' : co
  return { names, country, boysLove: tagNames.some((t) => BL.test(t)), girlsLove: tagNames.some((t) => GL.test(t)), labelled: tagNames.length > 0 }
}

const conflicts = (p, c) => {
  if (p.country && c.country && p.country !== c.country) return true
  if (p.boysLove && c.labelled && !c.boysLove) return true
  if (!p.boysLove && p.hasTags && c.boysLove) return true
  if (p.girlsLove && c.labelled && !c.girlsLove) return true
  if (!p.girlsLove && p.hasTags && c.girlsLove) return true
  return false
}

// Best candidate for the profile, or null. A name alone is not enough: the entry must also confirm the other names, and agree on kind.
function bestMatch(profile, candidates) {
  const ranked = candidates.map((m) => {
    const c = candidateInfo(m)
    if (!profile.names.length || !c.names.length) return null
    const scores = profile.names.map((n) => Math.max(...c.names.map((x) => similarity(n, x))))
    const nameScore = Math.max(...scores)
    if (nameScore < 0.85 || conflicts(profile, c)) return null
    const coverage = scores.filter((s) => s >= 0.95).length / profile.names.length
    let score = nameScore * 0.55 + coverage * 0.3
    if (profile.country && c.country === profile.country) score += 0.1
    else if (!profile.country && profile.scriptCountry && c.country === profile.scriptCountry) score += 0.05
    if (profile.boysLove && c.boysLove) score += 0.05
    return { m, score }
  }).filter(Boolean).sort((a, b) => b.score - a.score)
  return ranked[0]?.m || null
}

// ---- provider client --------------------------------------------------------------------------------------------------------------
function createMetadata({ fetchImpl = globalThis.fetch, cache, getHideAdult = () => true, getClientId = () => MAL_CLIENT_ID }) {
  const getJson = async (url, headers = {}) => {
    const key = 'meta:' + url
    const hit = cache?.get(key)
    if (hit) return hit
    const r = await fetchImpl(url, { headers: { accept: 'application/json', ...headers } })
    if (!r.ok) { const e = new Error(`${new URL(url).host} answered ${r.status}`); e.code = 'HTTP'; throw e }
    const j = await r.json()
    cache?.set(key, j, 15 * MIN)
    return j
  }
  const mal = (path, params) => getJson(`${MAL_API}${path}?${new URLSearchParams(params)}`, { 'X-MAL-CLIENT-ID': getClientId() || MAL_CLIENT_ID })

  const malList = async ({ kind, mode, limit, search }) => {
    const seg = mode === 'anime' ? 'anime' : 'manga', hideAdult = getHideAdult()
    const params = { limit: String(Math.min(limit, 100)), nsfw: String(!hideAdult), fields: 'alternative_titles,synopsis,genres,mean,nsfw,media_type,start_date,status,num_episodes,num_chapters' }
    let path
    if (search) { path = `/${seg}`; params.q = search }
    else if (mode === 'anime' && kind === 'RECENT') {
      const d = new Date(), season = ['winter', 'spring', 'summer', 'fall'][Math.floor(d.getMonth() / 3)]
      path = `/anime/season/${d.getFullYear()}/${season}`; params.sort = 'anime_num_list_users'
    } else if (kind === 'RECENT') return [] // MAL has no recently-started manga list
    else { path = `/${seg}/ranking`; params.ranking_type = mode === 'anime' ? (kind === 'TRENDING' ? 'airing' : 'bypopularity') : mode === 'novel' ? 'novels' : kind === 'TRENDING' ? 'favorite' : 'bypopularity' }
    const j = await mal(path, params)
    return (j.data || []).map((e) => e.node).filter(Boolean)
      .filter((n) => mode === 'anime' || NOVEL_TYPES.has(String(n.media_type || '')) === (mode === 'novel'))
      .map((n) => fromMal(n, mode)).filter((m) => !(hideAdult && m.isAdult))
  }
  const malById = async (id, mode) => {
    const j = await mal(`/${mode === 'anime' ? 'anime' : 'manga'}/${id}`, { fields: 'alternative_titles,synopsis,genres,mean,nsfw,media_type,start_date,status,num_episodes,num_chapters,main_picture' })
    return j?.id ? fromMal(j, mode) : null
  }

  const mbList = async ({ mode, limit, search }) => {
    if (mode === 'anime') return []
    const hideAdult = getHideAdult()
    const params = new URLSearchParams(search ? { q: search } : { sort_by: 'popularity_desc' })
    params.set(mode === 'novel' ? 'type' : 'type_not', 'novel'); params.set('limit', String(limit))
    const j = await getJson(`${MB_API}/series/search?${params}`)
    return (j.data || []).map((x) => fromMangaBaka(x, mode)).filter((m) => !(hideAdult && m.isAdult))
  }
  const mbById = async (id, mode) => {
    const j = await getJson(`${MB_API}/series/${id}`)
    const x = j?.data || (j?.id ? j : null)
    return x ? fromMangaBaka(x, mode) : null
  }

  return {
    supports: (provider, mode) => provider === 'anilist' || provider === 'mal' || (provider === 'mangabaka' && mode !== 'anime'),
    list: ({ provider, ...o }) => (provider === 'mal' ? malList(o) : provider === 'mangabaka' ? mbList(o) : Promise.resolve([])),
    byId: ({ provider, id, mode }) => (provider === 'mal' ? malById(id, mode) : provider === 'mangabaka' ? mbById(id, mode) : Promise.resolve(null)),
  }
}

module.exports = { createMetadata, bestMatch, profileOf, fromMal, fromMangaBaka, MAL_CLIENT_ID }
