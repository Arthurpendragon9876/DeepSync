'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { createMetadata, bestMatch, profileOf, fromMal, fromMangaBaka } = require('../main/metadata')

const json = (body) => ({ ok: true, status: 200, json: async () => body })
const fakeFetch = (routes, seen = []) => async (url, init) => {
  seen.push({ url: String(url), headers: init?.headers || {} })
  const hit = Object.entries(routes).find(([k]) => String(url).includes(k))
  return hit ? json(hit[1]) : { ok: false, status: 404, json: async () => ({}) }
}

const malNode = (o) => ({ id: 1, title: 'Frieren', media_type: 'tv', mean: 9.3, nsfw: 'white', genres: [{ name: 'Fantasy' }], main_picture: { large: 'https://x/c.jpg' }, alternative_titles: { en: 'Frieren: Beyond Journey’s End', ja: '葬送のフリーレン', synonyms: ['Sousou no Frieren'] }, start_date: '2023-09-29', status: 'currently_airing', num_episodes: 28, ...o })

test('MAL titles come out shaped like AniList ones, with ids that cannot collide', () => {
  const m = fromMal(malNode({}), 'anime')
  assert.equal(m.id, 'meta-mal-1'); assert.equal(m.type, 'ANIME'); assert.equal(m.format, 'TV'); assert.equal(m.status, 'RELEASING')
  assert.equal(m.averageScore, 93); assert.equal(m.seasonYear, 2023); assert.equal(m.episodes, 28); assert.equal(m.coverImage.large, 'https://x/c.jpg')
  assert.deepEqual(m.genres, ['Fantasy']); assert.ok(m.synonyms.includes('Sousou no Frieren')); assert.equal(m.title.english, 'Frieren: Beyond Journey’s End')
  const n = fromMal(malNode({ id: 5, media_type: 'light_novel', num_chapters: 12 }), 'novel')
  assert.equal(n.format, 'NOVEL'); assert.equal(n.type, 'MANGA'); assert.equal(n.chapters, 12)
})

test('MAL lists: right endpoint per mode and row, client id header, adult hidden, novels kept apart from manga', async () => {
  const seen = []
  const body = { data: [{ node: malNode({ id: 1, media_type: 'manga' }) }, { node: malNode({ id: 2, media_type: 'light_novel' }) }, { node: malNode({ id: 3, media_type: 'manga', nsfw: 'black' }) }] }
  const api = createMetadata({ fetchImpl: fakeFetch({ '/manga/ranking': body }, seen), getHideAdult: () => true })
  const manga = await api.list({ provider: 'mal', kind: 'TRENDING', mode: 'manga', limit: 10 })
  assert.deepEqual(manga.map((m) => m.id), ['meta-mal-1'], 'light novels and adult titles are filtered out of the manga list')
  assert.match(seen[0].url, /ranking_type=favorite/); assert.match(seen[0].url, /nsfw=false/); assert.ok(seen[0].headers['X-MAL-CLIENT-ID'])
  const novels = await createMetadata({ fetchImpl: fakeFetch({ '/manga/ranking': body }), getHideAdult: () => false }).list({ provider: 'mal', kind: 'POPULAR', mode: 'novel', limit: 10 })
  assert.deepEqual(novels.map((m) => m.id), ['meta-mal-2'])
  assert.deepEqual(await api.list({ provider: 'mal', kind: 'RECENT', mode: 'manga', limit: 10 }), [], 'MAL has no recent manga row, Home falls back to sources')
  const anime = []
  await createMetadata({ fetchImpl: fakeFetch({ '/anime/ranking': { data: [{ node: malNode({}) }] } }, anime) }).list({ provider: 'mal', kind: 'TRENDING', mode: 'anime', limit: 5 })
  assert.match(anime[0].url, /ranking_type=airing/)
})

test('MangaBaka: popularity list, no anime, novel split, adult hidden', async () => {
  const seen = []
  const series = [{ id: 7, title: 'Solo Leveling', type: 'manhwa', rating: 85, cover: { x250: { x1: 'https://mb/c.png' } }, genres: ['Action'], content_rating: 'safe', year: 2018 }, { id: 8, title: 'Adult', content_rating: 'erotica' }]
  const mb = createMetadata({ fetchImpl: fakeFetch({ '/series/search': { data: series } }, seen), getHideAdult: () => true })
  const rows = await mb.list({ provider: 'mangabaka', mode: 'manga', limit: 12 })
  assert.deepEqual(rows.map((m) => m.id), ['meta-mangabaka-7']); assert.equal(rows[0].averageScore, 85 / 10 * 10); assert.equal(rows[0].coverImage.large, 'https://mb/c.png')
  assert.match(seen[0].url, /sort_by=popularity_desc/); assert.match(seen[0].url, /type_not=novel/)
  assert.deepEqual(await mb.list({ provider: 'mangabaka', mode: 'anime', limit: 12 }), [])
  assert.equal(mb.supports('mangabaka', 'anime'), false); assert.equal(mb.supports('mangabaka', 'novel'), true); assert.equal(mb.supports('mal', 'anime'), true)
  assert.equal(fromMangaBaka(series[0], 'manga').meta.country, 'KR')
})

test('matcher: the same name is not enough, kind must agree', () => {
  const cand = (id, romaji, o = {}) => ({ id, title: { romaji, english: null, native: null }, synonyms: [], genres: [], tags: [], ...o })
  const love = cand(1, 'Love Quest', { genres: ["Boys' Love"], countryOfOrigin: 'JP' })
  const kr = cand(2, 'Love Quest', { genres: ['Romance'], countryOfOrigin: 'KR' })
  assert.equal(bestMatch(profileOf({ names: ['Love Quest'], tags: ['Manhwa', 'Romance'] }), [love, kr]).id, 2, 'a Korean romance source is the manhwa, not the BL manga')
  assert.equal(bestMatch(profileOf({ names: ['Love Quest'], tags: ["Boys' Love"] }), [kr, love]).id, 1)
  assert.equal(bestMatch(profileOf({ names: ['Totally Different Thing'] }), [love, kr]), null, 'no confident name match means no match')
  const syn = cand(3, 'Sousou no Frieren', { synonyms: ['Frieren: Beyond Journey’s End'] })
  assert.equal(bestMatch(profileOf({ names: ['Frieren: Beyond Journey’s End'] }), [syn]).id, 3, 'synonyms count as names')
})

test('mode fan: the thumb picks the mode it is closest to, and nothing near the button', async () => {
  const { pickMode, ANGLE } = await import('../renderer/js/fan-math.js')
  const R = 224, at = (deg, r = R) => [r * Math.cos((deg * Math.PI) / 180), r * Math.sin((deg * Math.PI) / 180)]
  assert.equal(pickMode(...at(ANGLE.anime), R), 'anime'); assert.equal(pickMode(...at(ANGLE.manga), R), 'manga'); assert.equal(pickMode(...at(ANGLE.novel), R), 'novel')
  assert.equal(pickMode(5, 5, R), null, 'inside the dead zone nothing is picked')
  assert.equal(pickMode(...at(80, 120), R), 'anime', 'between the button and a node the angle zone decides')
  assert.equal(pickMode(...at(10, 120), R), 'novel'); assert.equal(pickMode(...at(40, 120), R), 'manga')
})
