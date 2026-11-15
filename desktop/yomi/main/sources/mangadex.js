'use strict'
// Built-in online manga source using MangaDex's public API.
// Source interface (so more sources can be added): id, name, search(q), chapters(id), pages(chapterId).
const API = 'https://api.mangadex.org'

function create({ fetchImpl = globalThis.fetch, getHideAdult = () => true } = {}) {
  async function get(path, params = []) {
    const qs = params.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
    let res
    try {
      res = await fetchImpl(`${API}${path}${qs ? '?' + qs : ''}`, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(15000) })
    } catch {
      const e = new Error('Cannot reach MangaDex')
      e.code = 'OFFLINE'
      throw e
    }
    if (!res.ok) throw new Error(`MangaDex returned HTTP ${res.status}`)
    return res.json()
  }
  const ratings = () => (getHideAdult() ? ['safe', 'suggestive'] : ['safe', 'suggestive', 'erotica', 'pornographic']).map((r) => ['contentRating[]', r])

  return {
    id: 'mangadex',
    name: 'MangaDex',
    async search(query) {
      const j = await get('/manga', [['title', query], ['limit', 20], ['includes[]', 'cover_art'], ['order[relevance]', 'desc'], ...ratings()])
      return j.data.map((m) => {
        const cover = m.relationships?.find((r) => r.type === 'cover_art')?.attributes?.fileName
        const t = m.attributes.title
        return {
          id: m.id,
          title: t.en || Object.values(t)[0] || 'Untitled',
          cover: cover ? `https://uploads.mangadex.org/covers/${m.id}/${cover}.256.jpg` : null,
          status: m.attributes.status,
          year: m.attributes.year,
        }
      })
    },
    async chapters(mangaId) {
      const out = []
      for (let offset = 0; offset < 5000; offset += 500) {
        const j = await get(`/manga/${mangaId}/feed`, [['translatedLanguage[]', 'en'], ['order[chapter]', 'asc'], ['limit', 500], ['offset', offset], ['includeExternalUrl', 0], ...ratings()])
        out.push(...j.data)
        if (offset + 500 >= j.total) break
      }
      const seen = new Set()
      return out
        .filter((c) => c.attributes.pages > 0 && !c.attributes.externalUrl)
        .map((c) => ({ id: c.id, chapter: parseFloat(c.attributes.chapter) || 0, volume: c.attributes.volume, title: c.attributes.title || '', pages: c.attributes.pages, publishAt: c.attributes.publishAt }))
        .filter((c) => (seen.has(c.chapter) ? false : seen.add(c.chapter)))
        .sort((a, b) => a.chapter - b.chapter)
    },
    async pages(chapterId) {
      const j = await get(`/at-home/server/${chapterId}`)
      return j.chapter.data.map((f) => ({ kind: 'url', url: `${j.baseUrl}/data/${j.chapter.hash}/${f}` }))
    },
  }
}

module.exports = { create }
