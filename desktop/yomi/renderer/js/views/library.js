import { h, icon, S, api, data, go, titleOf, coverOf, segmented, setMode, toast } from '../core.js'
import { openLocalSheet } from '../overlays.js'

export async function library({ query }) {
  const tab = query.tab || S.mode
  const lib = await api('library:get')
  const paths = S.settings.libraryPaths
  const none = !Object.values(paths).some((p) => p.length)
  const rescan = async (btn) => { btn.disabled = true; await api('library:scan'); window.dispatchEvent(new Event('ds:refresh')) }
  const scanBtn = h('button', { class: 'btn', onclick: (e) => rescan(e.currentTarget) }, icon('sync', 18), 'Rescan')
  const head = h('h1', { class: 'page-title' }, 'Local library', segmented([['anime', `Anime (${lib.anime.length})`], ['manga', `Manga (${lib.manga.length})`], ['novel', `Novels (${lib.novel.length})`]], tab, (t) => setMode(t)), scanBtn,
    h('button', { class: 'btn', onclick: () => go('/settings/library') }, icon('folder', 18), 'Folders'))
  if (none) return h('div', { class: 'page' }, head, h('div', { class: 'empty' }, h('h3', null, 'No library folders yet'), h('p', null, 'Point Yomi at the folders that hold your anime, manga (.cbz or image folders) and novels (.epub).'), h('button', { class: 'btn primary', onclick: () => go('/settings/library') }, 'Add folders')))
  const items = lib[tab]
  if (!items.length) return h('div', { class: 'page' }, head, h('div', { class: 'empty' }, h('h3', null, 'Nothing found'), h('p', null, `No ${tab} files were found in your ${tab} folders.`)))

  const grid = h('div', { class: 'grid' })
  const cards = items.map((entry) => {
    let matched = null
    const cover = h('div', { class: 'cover', style: { display: 'grid', placeItems: 'center', padding: 12, textAlign: 'center', fontWeight: 700, background: 'linear-gradient(160deg,var(--surface-2),var(--surface))' } }, entry.title)
    const card = h('div', { class: 'poster', onclick: () => openLocalSheet(entry, { matched, onLinked: () => window.dispatchEvent(new Event('ds:refresh')) }) }, cover,
      h('div', { class: 'name' }, entry.title), h('div', { class: 'sub' }, tab === 'anime' ? `${entry.files.length} episodes` : tab === 'manga' ? `${entry.files.length} chapters` : entry.author || 'EPUB'))
    entry._set = (m) => { matched = m; cover.replaceChildren(h('img', { src: coverOf(m), alt: '' })); cover.removeAttribute('style') }
    return card
  })
  grid.append(...cards)

  // Match titles to AniList one at a time (gentle on the rate limit); results are remembered via links.
  ;(async () => {
    for (const entry of items) {
      if (!document.body.contains(grid)) return
      try {
        const id = lib.links[entry.key]
        const m = id ? await data('anilist:details', { id }) : (await api('library:match', { title: entry.title, mode: tab }))?.media
        if (m) { entry._set(m); if (!id) api('library:link', { localKey: entry.key, mediaId: m.id }) }
      } catch (e) { if (e.code === 'RATE') return }
    }
  })()
  return h('div', { class: 'page' }, head, grid)
}
