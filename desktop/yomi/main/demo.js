'use strict'
// Offline / demo data shaped exactly like AniList responses. Artwork is generated SVG so nothing is downloaded.
const GENRES = ['Action', 'Adventure', 'Comedy', 'Drama', 'Ecchi', 'Fantasy', 'Mystery', 'Romance', 'Sci-Fi', 'Slice of Life', 'Supernatural']

// [title, type, format, episodes|chapters, status]
const RAW = [
  ['Starfall Academy', 'ANIME', 'TV', 12, 'RELEASING'], ['Iron Wolf Chronicles', 'ANIME', 'TV', 24, 'FINISHED'],
  ['Violet Hour', 'ANIME', 'TV', 12, 'RELEASING'], ['The Lantern Keeper', 'ANIME', 'TV', 13, 'FINISHED'],
  ['Crimson Raid Squad', 'ANIME', 'TV', 12, 'RELEASING'], ['Echoes of Tomorrow', 'ANIME', 'MOVIE', 1, 'FINISHED'],
  ['Neon District Zero', 'ANIME', 'TV', 12, 'RELEASING'], ['Tea Shop at the End of Time', 'ANIME', 'TV', 12, 'FINISHED'],
  ['Dragon Court Rebellion', 'ANIME', 'TV', 25, 'FINISHED'], ['Seaside Summer Days', 'ANIME', 'TV', 12, 'RELEASING'],
  ['Blade of the Hollow King', 'ANIME', 'TV', 12, 'RELEASING'], ['Moonlit Sorcerer II', 'ANIME', 'TV', 12, 'RELEASING'],
  ['Hollow Crown', 'MANGA', 'MANGA', 214, 'RELEASING'], ['Sword Saint Reborn', 'MANGA', 'MANGA', 98, 'RELEASING'],
  ['Midnight Ledger', 'MANGA', 'MANGA', 61, 'FINISHED'], ['Ashen Frontier', 'MANGA', 'MANGA', 143, 'RELEASING'],
  ['Little Witch Atelier Days', 'MANGA', 'MANGA', 77, 'RELEASING'], ['Steel Orchard', 'MANGA', 'MANGA', 120, 'FINISHED'],
  ['The Last Summoner', 'MANGA', 'MANGA', 33, 'RELEASING'], ['Paper Moon Café', 'MANGA', 'MANGA', 45, 'RELEASING'],
  ['Reader of the Fallen Star', 'MANGA', 'NOVEL', 551, 'RELEASING'], ['Archivist of Dust', 'MANGA', 'NOVEL', 310, 'FINISHED'],
  ['Ninth Gate Online', 'MANGA', 'NOVEL', 188, 'RELEASING'], ['Quiet Kingdom', 'MANGA', 'NOVEL', 96, 'FINISHED'],
]

const hash = (s) => [...s].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7)
const hsl = (h, s, l) => `hsl(${h % 360} ${s}% ${l}%)`
const uri = (svg) => 'data:image/svg+xml;utf8,' + encodeURIComponent(svg)
const esc = (s) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c])

function cover(title, i) {
  const h = hash(title)
  const a = hsl(h, 65, 38), b = hsl(h + 50, 70, 16)
  const words = title.split(' ').slice(0, 3)
  const lines = words.map((w, n) => `<text x="30" y="${470 + n * 54}" font-family="sans-serif" font-weight="800" font-size="46" fill="#fff" fill-opacity=".92">${esc(w.toUpperCase())}</text>`).join('')
  return uri(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 460 650"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient>
  <linearGradient id="f" x1="0" y1="0" x2="0" y2="1"><stop offset=".45" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".75"/></linearGradient></defs>
  <rect width="460" height="650" fill="url(#g)"/><circle cx="${120 + (h % 240)}" cy="170" r="${110 + (h % 60)}" fill="#fff" fill-opacity=".10"/>
  <path d="M0 ${300 + (h % 90)} L460 ${160 + (h % 120)} L460 650 L0 650Z" fill="#000" fill-opacity=".22"/><rect width="460" height="650" fill="url(#f)"/>${lines}</svg>`)
}

function banner(title) {
  const h = hash(title)
  const shapes = Array.from({ length: 7 }, (_, n) => {
    const x = (h * (n + 3)) % 1920, y = (h * (n + 5)) % 520, r = 60 + ((h >> n) % 190)
    return `<circle cx="${x}" cy="${y}" r="${r}" fill="${hsl(h + n * 33, 70, 55)}" fill-opacity="${0.05 + (n % 3) * 0.04}"/>`
  }).join('')
  return uri(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 520" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${hsl(h, 60, 30)}"/><stop offset=".6" stop-color="${hsl(h + 40, 65, 14)}"/><stop offset="1" stop-color="${hsl(h + 90, 55, 22)}"/></linearGradient></defs>
  <rect width="1920" height="520" fill="url(#g)"/>${shapes}<path d="M0 520 L1100 0 L1500 0 L600 520Z" fill="#000" fill-opacity=".18"/></svg>`)
}

const now = () => Math.floor(Date.now() / 1000)
const MEDIA = RAW.map(([title, type, format, count, status], i) => {
  const h = hash(title)
  const isAnime = type === 'ANIME'
  const next = isAnime && status === 'RELEASING' ? { episode: 1 + (h % 7), airingAt: now() + ((h % 6) + 1) * 3600 * 20, timeUntilAiring: 0 } : null
  return {
    id: 1000 + i, idMal: null, type, format, status, season: ['WINTER', 'SPRING', 'SUMMER', 'FALL'][h % 4], seasonYear: 2026 - (h % 3),
    episodes: isAnime ? count : null, chapters: isAnime ? null : count, volumes: null, duration: isAnime ? 24 : null,
    averageScore: 62 + (h % 34), popularity: 20000 + ((h * 7) % 90000), isAdult: false,
    genres: [GENRES[h % 11], GENRES[(h >> 3) % 11], GENRES[(h >> 5) % 11]].filter((g, n, a) => a.indexOf(g) === n),
    bannerImage: banner(title), coverImage: { extraLarge: cover(title, i), large: cover(title, i), color: hsl(h, 60, 45) },
    title: { romaji: title, english: title, native: title, userPreferred: title }, nextAiringEpisode: next, startDate: { year: 2026 - (h % 3) },
  }
})

const matches = (m, o) => {
  if (o.type && m.type !== o.type) return false
  if (o.format && m.format !== o.format) return false
  if (o.format_not && m.format === o.format_not) return false
  if (o.status && m.status !== o.status) return false
  if (o.search && !m.title.romaji.toLowerCase().includes(o.search.toLowerCase())) return false
  if (o.genre_in?.length && !o.genre_in.every((g) => m.genres.includes(g))) return false
  return true
}
const SORTS = {
  TRENDING_DESC: (a, b) => b.popularity - a.popularity, POPULARITY_DESC: (a, b) => b.popularity - a.popularity,
  SCORE_DESC: (a, b) => b.averageScore - a.averageScore, START_DATE_DESC: (a, b) => b.seasonYear - a.seasonYear || b.id - a.id,
}

function browse({ page = 1, perPage = 24, sort = 'TRENDING_DESC', ...o } = {}) {
  const key = Array.isArray(sort) ? sort[0] : sort
  const all = MEDIA.filter((m) => matches(m, o)).sort(SORTS[key] || SORTS.TRENDING_DESC)
  return { items: all.slice((page - 1) * perPage, page * perPage), hasNext: all.length > page * perPage }
}

function details(id) {
  const m = MEDIA.find((x) => x.id === id) || MEDIA[0]
  const others = MEDIA.filter((x) => x.id !== m.id && x.type === m.type)
  return {
    ...m, countryOfOrigin: 'JP', source: 'ORIGINAL', synonyms: [], siteUrl: '', trailer: null,
    description: `${m.title.romaji} is demo data shown while AniList is unreachable.<br><br>Reconnect to the internet (or switch Demo mode off in Settings) to see real titles, covers and scores. Everything else (library, trackers, readers) works exactly the same.`,
    studios: { nodes: [{ name: 'Demo Studio' }] },
    tags: ['Ensemble Cast', 'Coming of Age', 'Urban Fantasy', 'Betrayal'].map((name, n) => ({ name, rank: 80 - n * 12, isMediaSpoiler: false })),
    streamingEpisodes: m.type === 'ANIME' ? Array.from({ length: m.episodes || 0 }, (_, n) => ({ title: `Episode ${n + 1} - Chapter ${n + 1}`, thumbnail: null })) : [],
    relations: { edges: others.slice(0, 3).map((n, k) => ({ relationType: ['SEQUEL', 'SIDE_STORY', 'ADAPTATION'][k], node: { id: n.id, type: n.type, format: n.format, title: n.title, coverImage: n.coverImage } })) },
    characters: { edges: ['Aiko', 'Ren', 'Sora', 'Mika', 'Kaito', 'Yuna'].map((n, k) => ({ role: k < 2 ? 'MAIN' : 'SUPPORTING', node: { id: k, name: { userPreferred: n }, image: { large: cover(n, k) } } })) },
    recommendations: { nodes: others.slice(3, 9).map((n) => ({ mediaRecommendation: n })) },
  }
}

function schedule(from, to) {
  const out = []
  const base = new Date(from * 1000)
  MEDIA.filter((m) => m.type === 'ANIME' && m.status === 'RELEASING').forEach((m, i) => {
    for (let w = 0; w < 7; w++) {
      if ((hash(m.title.romaji) % 7) !== w) continue
      const d = new Date(base); d.setDate(d.getDate() + w); d.setHours(8 + ((i * 3) % 14), (i * 17) % 60, 0, 0)
      const t = Math.floor(d / 1000)
      if (t > from && t < to) out.push({ airingAt: t, episode: 1 + (hash(m.title.romaji) % 8), media: m })
    }
  })
  return out.sort((a, b) => a.airingAt - b.airingAt)
}

const viewer = () => ({ id: 1, name: 'DemoUser', avatar: { large: cover('DU', 3), medium: cover('DU', 3) }, bannerImage: null, options: { titleLanguage: 'ROMAJI' } })

function userList(type) {
  const statuses = ['CURRENT', 'CURRENT', 'CURRENT', 'CURRENT', 'PLANNING', 'COMPLETED', 'COMPLETED', 'PAUSED', 'DROPPED', 'CURRENT']
  return MEDIA.filter((m) => m.type === type).map((media, i) => {
    const total = media.episodes || media.chapters || 12
    const status = statuses[i % statuses.length]
    const progress = status === 'COMPLETED' ? total : status === 'PLANNING' ? 0 : Math.max(1, Math.floor(total * (0.2 + ((i * 13) % 60) / 100)))
    const eps = media.type === 'ANIME' ? Array.from({ length: total }, (_, n) => ({ title: `Episode ${n + 1} - ${['Overture', 'First Blood', 'Purpose', 'Departure', 'Crossroads', 'Reunion'][n % 6]}`, thumbnail: null })) : []
    return { id: 5000 + media.id, mediaId: media.id, status, progress, score: status === 'COMPLETED' ? 7 + (i % 3) : 0, updatedAt: now() - i * 7200, media: { ...media, streamingEpisodes: eps } }
  })
}

module.exports = { MEDIA, GENRES, browse, details, schedule, viewer, userList, cover, banner }
