'use strict'
// AniList client (public queries + authenticated list/progress sync), mirroring DeepSync's AniListRepository.
const ENDPOINT = 'https://graphql.anilist.co'

class AniListError extends Error {
  constructor(message, code) {
    super(message)
    this.code = code // OFFLINE | RATE | AUTH | GRAPHQL | BAD
  }
}

const CARD = `id idMal type format status season seasonYear episodes chapters volumes duration averageScore popularity isAdult genres bannerImage countryOfOrigin synonyms
  coverImage { extraLarge large color } title { romaji english native userPreferred }
  nextAiringEpisode { episode airingAt timeUntilAiring } startDate { year }`

const ARG_TYPES = {
  type: 'MediaType', sort: '[MediaSort]', status: 'MediaStatus', search: 'String', genre_in: '[String]', genre_not_in: '[String]',
  format: 'MediaFormat', format_not: 'MediaFormat', isAdult: 'Boolean', seasonYear: 'Int', season: 'MediaSeason', countryOfOrigin: 'CountryCode',
}

const MIN = 60 * 1000

// Which AniList query matches each DeepSync mode (novels are MANGA with format NOVEL).
const modeFilter = (mode) =>
  mode === 'anime' ? { type: 'ANIME' } : mode === 'novel' ? { type: 'MANGA', format: 'NOVEL' } : { type: 'MANGA', format_not: 'NOVEL' }

function createClient({ getToken, cache, fetchImpl = globalThis.fetch }) {
  async function request(query, variables = {}, { auth = false, ttl = 0 } = {}) {
    const key = ttl ? JSON.stringify([query, variables]) : null
    if (key) {
      const hit = cache.get(key)
      if (hit) return hit
    }
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json' }
    if (auth) {
      const token = getToken()
      if (!token) throw new AniListError('Not signed in to AniList', 'AUTH')
      headers.Authorization = 'Bearer ' + token
    }
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 12000)
    let res
    try {
      res = await fetchImpl(ENDPOINT, { method: 'POST', headers, body: JSON.stringify({ query, variables }), signal: ctl.signal })
    } catch {
      const stale = key && cache.get(key, { stale: true })
      if (stale) return stale
      throw new AniListError('Cannot reach AniList', 'OFFLINE')
    } finally {
      clearTimeout(timer)
    }
    if (res.status === 429) throw new AniListError('AniList rate limit reached, try again in a minute', 'RATE')
    let json
    try {
      json = await res.json()
    } catch {
      throw new AniListError('Unexpected response from AniList', 'BAD')
    }
    if (json.errors?.length) {
      const msg = json.errors[0].message || 'AniList error'
      throw new AniListError(msg, res.status === 401 || /invalid token/i.test(msg) ? 'AUTH' : 'GRAPHQL')
    }
    if (key) cache.set(key, json.data, ttl)
    return json.data
  }

  // Build a Page query containing only the filters that were actually given.
  async function browse({ page = 1, perPage = 24, ...filters } = {}) {
    const vars = { page, perPage }
    const decl = ['$page: Int', '$perPage: Int']
    const args = []
    for (const [k, v] of Object.entries(filters)) {
      if (!ARG_TYPES[k] || v == null || v === '' || (Array.isArray(v) && !v.length)) continue
      decl.push(`$${k}: ${ARG_TYPES[k]}`)
      args.push(`${k}: $${k}`)
      vars[k] = v
    }
    const q = `query (${decl.join(', ')}) { Page(page: $page, perPage: $perPage) { pageInfo { hasNextPage } media(${args.join(', ')}) { ${CARD} } } }`
    const d = await request(q, vars, { ttl: 15 * MIN })
    return { items: d.Page.media, hasNext: d.Page.pageInfo.hasNextPage }
  }

  const details = async (id) =>
    (
      await request(
        `query ($id: Int) { Media(id: $id) { ${CARD} description(asHtml: false) countryOfOrigin source synonyms siteUrl trailer { id site }
          studios(isMain: true) { nodes { name } } tags { name rank isMediaSpoiler }
          streamingEpisodes { title thumbnail }
          relations { edges { relationType node { id type format title { userPreferred } coverImage { large } } } }
          characters(sort: [ROLE, FAVOURITES_DESC], perPage: 14) { edges { role node { id name { userPreferred } image { large } } } }
          recommendations(sort: RATING_DESC, perPage: 12) { nodes { mediaRecommendation { ${CARD} } } } } }`,
        { id },
        { ttl: 60 * MIN },
      )
    ).Media

  async function schedule(from, to) {
    const out = []
    for (let page = 1; page <= 4; page++) {
      const d = await request(
        `query ($from: Int, $to: Int, $page: Int) { Page(page: $page, perPage: 50) { pageInfo { hasNextPage }
          airingSchedules(airingAt_greater: $from, airingAt_lesser: $to, sort: TIME) { airingAt episode media { ${CARD} } } } }`,
        { from, to, page },
        { ttl: 30 * MIN },
      )
      out.push(...d.Page.airingSchedules)
      if (!d.Page.pageInfo.hasNextPage) break
    }
    return out
  }

  const viewer = async () =>
    (await request(`query { Viewer { id name avatar { large medium } bannerImage options { titleLanguage displayAdultContent } } }`, {}, { auth: true })).Viewer

  async function userList(userId, type) {
    const d = await request(
      `query ($userId: Int, $type: MediaType) { MediaListCollection(userId: $userId, type: $type) { lists { name status entries {
        id mediaId status progress score(format: POINT_10_DECIMAL) updatedAt media { ${CARD} streamingEpisodes { title thumbnail } } } } } }`,
      { userId, type },
      { auth: true, ttl: 2 * MIN },
    )
    const seen = new Set()
    const entries = []
    for (const l of d.MediaListCollection.lists)
      for (const e of l.entries) {
        if (seen.has(e.id)) continue
        seen.add(e.id)
        entries.push(e)
      }
    return entries
  }

  const entryFor = async (mediaId) =>
    (await request(`query ($id: Int) { Media(id: $id) { mediaListEntry { id status progress score(format: POINT_10_DECIMAL) } } }`, { id: mediaId }, { auth: true })).Media.mediaListEntry

  async function saveEntry({ mediaId, status, progress, score }) {
    const vars = { mediaId }
    const decl = ['$mediaId: Int']
    const args = ['mediaId: $mediaId']
    if (status) (decl.push('$status: MediaListStatus'), args.push('status: $status'), (vars.status = status))
    if (progress != null) (decl.push('$progress: Int'), args.push('progress: $progress'), (vars.progress = Math.max(0, Math.floor(progress))))
    if (score != null) (decl.push('$scoreRaw: Int'), args.push('scoreRaw: $scoreRaw'), (vars.scoreRaw = Math.round(score * 10)))
    const d = await request(
      `mutation (${decl.join(', ')}) { SaveMediaListEntry(${args.join(', ')}) { id status progress score(format: POINT_10_DECIMAL) } }`,
      vars,
      { auth: true },
    )
    cache.clear() // list queries must not serve pre-change data
    return d.SaveMediaListEntry
  }

  async function deleteEntry(id) {
    const d = await request(`mutation ($id: Int) { DeleteMediaListEntry(id: $id) { deleted } }`, { id }, { auth: true })
    cache.clear()
    return d.DeleteMediaListEntry.deleted
  }

  // Best AniList match for a local title: Dice coefficient over bigrams of normalised names.
  async function match(title, mode) {
    const { items } = await browse({ ...modeFilter(mode), search: title, sort: 'SEARCH_MATCH', perPage: 5 })
    let best = null
    for (const m of items) {
      const names = [m.title.romaji, m.title.english, m.title.native, m.title.userPreferred].filter(Boolean)
      const score = Math.max(...names.map((n) => similarity(title, n)))
      if (!best || score > best.score) best = { media: m, score }
    }
    return best && best.score >= 0.55 ? best : null
  }

  return { request, browse, details, schedule, viewer, userList, entryFor, saveEntry, deleteEntry, match }
}

function similarity(a, b) {
  const n = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
  a = n(a)
  b = n(b)
  if (!a || !b) return 0
  if (a === b) return 1
  const grams = (s) => {
    const m = new Map()
    for (let i = 0; i < s.length - 1; i++) m.set(s.slice(i, i + 2), (m.get(s.slice(i, i + 2)) || 0) + 1)
    return m
  }
  const ga = grams(a)
  const gb = grams(b)
  let inter = 0
  for (const [g, c] of ga) inter += Math.min(c, gb.get(g) || 0)
  const dice = (2 * inter) / (Math.max(a.length - 1, 0) + Math.max(b.length - 1, 0) || 1)
  // "Show" vs "Show: Subtitle Arc": a whole-word prefix match is a strong signal even though Dice is low.
  const [short, long] = a.length <= b.length ? [a, b] : [b, a]
  const prefix = short.length >= 4 && long.startsWith(short + ' ') ? 0.6 + 0.4 * (short.length / long.length) : 0
  return Math.max(dice, prefix)
}

module.exports = { createClient, AniListError, modeFilter, similarity, CARD }
