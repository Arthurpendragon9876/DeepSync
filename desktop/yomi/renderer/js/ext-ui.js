// UI pieces for online sources run by Aniyomi/Mihon extensions (see main/extensions/).
import { h, icon, S, api, go, sheet, toast, titleOf, totalOf } from './core.js'
import { openMangaReader, openPlayer, openNovelReader } from './overlays.js'
import { recordHistory } from './library-data.js'

const ls = (k, d = '') => { try { return localStorage.getItem(k) ?? d } catch { return d } }
const lset = (k, v) => { try { localStorage.setItem(k, v) } catch {} }
export const srcLabel = (s) => (s.lang && s.lang !== 'all' ? `${s.name} (${s.lang})` : s.name)
export const installedSources = async (type) => (await api('ext:installed')).filter((s) => s.type === type)
const when = (ts) => (ts > 0 ? new Date(ts).toLocaleDateString() : '')
export const emptyBox = (head, text, ...extra) => h('div', { class: 'empty' }, h('h3', null, head), h('p', null, text), ...extra)

export function itemCard(it, onclick) {
  return h('div', { class: 'poster', onclick },
    h('div', { class: 'cover' }, it.cover && h('img', { src: it.cover, alt: '', loading: 'lazy', draggable: false })),
    h('div', { class: 'name' }, it.title || 'Untitled'))
}

// ---- tracking, the same rules as the local library: only ever moves forward, completes at the last chapter ----------------------------
async function track(media, mode, number, onProgress) {
  if (!media || number == null) return
  recordHistory({ media, mode, number, label: `${mode === 'anime' ? 'Episode' : 'Chapter'} ${number}` })
  const r = await api('track:progress', { mode, mediaId: media.id, progress: Math.floor(number), total: totalOf(media) }).catch(() => null)
  if (r?.saved) onProgress?.(r.saved)
}

// Opens chapter / episode `index` of a source title in the reader or the built-in player, tracking progress automatically.
export function openAt({ source, chapters, index, page, title, media, mode, onProgress }) {
  const at = chapters[index]
  if (at) recordHistory({ media, mode: mode || (source.type === 'anime' ? 'anime' : source.type === 'novel' ? 'novel' : 'manga'), number: at.number, label: at.name || `${source.type === 'anime' ? 'Episode' : 'Chapter'} ${at.number ?? ''}`.trim() })
  if (source.type === 'anime') {
    const files = chapters.map((c) => ({ key: `ext:${source.id}:${c.url}`, name: c.name, episode: c.number, videos: () => api('ext:videos', { sourceId: source.id, episode: c }) }))
    return openPlayer({ title, files, index, onWatched: (f) => track(media, mode || 'anime', f.episode, onProgress) })
  }
  if (source.type === 'novel') {
    const list = chapters.map((c) => ({ label: c.name || `Chapter ${c.number ?? ''}`.trim(), number: c.number, load: () => api('ext:chapterText', { sourceId: source.id, chapter: c }) }))
    return openNovelReader({ title, remote: { key: `${source.id}:${title}`, chapters: list, index, onRead: (ch) => track(media, mode || 'novel', ch.number, onProgress) } })
  }
  const refs = chapters.map((c) => ({ number: c.number, label: c.name || `Chapter ${c.number ?? ''}`.trim(), sub: c.scanlator, load: () => api('ext:pages', { sourceId: source.id, chapter: c }) }))
  return openMangaReader({ title, chapters: refs, index, page, onRead: (r) => track(media, mode || 'manga', refs.indexOf(r) >= 0 ? chapters[refs.indexOf(r)].number : r.number, onProgress) })
}

// Chapter / episode list for one source title.
export function chapterList({ source, chapters, title, media, mode, progress = 0, onProgress, onMark }) {
  if (!chapters.length) return emptyBox('Nothing listed', 'This source returned no chapters or episodes for the title.')
  const anime = source.type === 'anime'
  return h('div', null, chapters.map((c, i) => h('div', { class: 'ep-row' + (c.number != null && c.number <= progress ? ' done' : ''),
    onclick: () => openAt({ source, chapters, index: i, title, media, mode, onProgress }) },
    h('div', { class: 'th' }, c.number ?? '#'),
    h('div', { class: 'tx' }, h('b', null, c.name || `${anime ? 'Episode' : 'Chapter'} ${c.number ?? ''}`.trim()), h('span', null, [c.scanlator, when(c.date)].filter(Boolean).join(' · '))),
    h('div', { class: 'st' },
      anime && onMark && c.number != null && h('button', { class: 'ibtn', style: { background: 'var(--surface-3)' }, title: 'Mark watched up to here', onclick: (e) => (e.stopPropagation(), onMark(c)) }, icon('check', 18)),
      icon(anime ? 'play' : 'chevR', 18)))))
}

// Standalone title sheet (used by the source browser): details + chapters, no tracker.
export async function titleSheet(source, item) {
  const body = h('div', h('div', { class: 'skeleton', style: { height: '160px' } }))
  sheet({ title: item.title, body })
  try {
    const d = await api('ext:detail', { sourceId: source.id, media: item })
    const by = [d.author, d.artist && d.artist !== d.author ? d.artist : ''].filter(Boolean).join(' · ')
    body.replaceChildren(
      h('div', { style: { display: 'flex', gap: '16px', marginBottom: '16px' } },
        (d.cover || item.cover) && h('img', { src: d.cover || item.cover, alt: '', style: { width: '110px', aspectRatio: '2/3', objectFit: 'cover', borderRadius: '10px', flex: 'none' } }),
        h('div', null, by ? h('div', { class: 'note' }, by) : null,
          h('div', { class: 'chips-row', style: { margin: '6px 0' } }, (d.genre || []).slice(0, 10).map((g) => h('span', { class: 'chip' }, g))),
          h('p', { class: 'note', style: { whiteSpace: 'pre-line', maxHeight: '140px', overflow: 'auto' } }, d.description || ''))),
      chapterList({ source, chapters: d.chapters, title: d.title || item.title }))
  } catch (e) { body.replaceChildren(emptyBox('Could not load', e.message)) }
}

// ---- automatic source matching (same idea as the Android app: tracker entry first, source found for it) ------------------------------
export const linkKey = (sourceId, mediaId) => `src:ext:${sourceId}:${mediaId}`
export const autoKey = (mediaId) => `src:ext:auto:${mediaId}`
export const getLinks = async () => (await api('library:get').catch(() => ({ links: {} }))).links || {}
export const setLink = (key, value) => api('library:link', { localKey: key, mediaId: value })

/**
 * Finds the source title for an AniList entry. Uses the saved link when there is one; otherwise searches the
 * installed sources, saves the best match and remembers which source it came from.
 * @returns {{ source, url, score?, auto?: boolean } | null}
 */
export async function resolveSource({ media, mode, sources, only }) {
  const links = await getLinks()
  const pool = only ? [only] : sources
  const savedAuto = !only && sources.find((s) => s.id === String(links[autoKey(media.id)] ?? ''))
  if (savedAuto && links[linkKey(savedAuto.id, media.id)]) return { source: savedAuto, url: links[linkKey(savedAuto.id, media.id)] }
  if (only && links[linkKey(only.id, media.id)]) return { source: only, url: links[linkKey(only.id, media.id)] }
  if (!only) for (const s of sources) if (links[linkKey(s.id, media.id)]) return { source: s, url: links[linkKey(s.id, media.id)] }
  const r = await api('ext:autoMatch', { media, mode, sourceIds: only ? [only.id] : undefined, preferred: ls('ds.extSource.' + (mode === 'anime' ? 'anime' : mode === 'novel' ? 'novel' : 'manga')) })
  if (!r.best) return { none: true, failed: r.failed, tried: r.tried }
  const source = pool.find((s) => s.id === r.best.sourceId) || sources.find((s) => s.id === r.best.sourceId)
  await setLink(linkKey(source.id, media.id), r.best.item.url)
  await setLink(autoKey(media.id), source.id)
  return { source, url: r.best.item.url, score: r.best.score, auto: true }
}

export const nextIndex = (chapters, progress) => {
  const i = chapters.findIndex((c) => c.number != null && c.number > progress)
  return i >= 0 ? i : progress > 0 ? chapters.length - 1 : 0
}

// For the details page's main button: opens the next chapter / episode straight from the matched source.
// Returns false when nothing can be done automatically (no runtime, no sources, no confident match) so the caller can show its sheet.
export async function continueWithExtension({ media, mode, progress = 0, onProgress }) {
  const type = mode === 'anime' ? 'anime' : mode === 'novel' ? 'novel' : 'manga'
  let sources
  try { sources = await installedSources(type) } catch { return false }
  if (!sources.length) return false
  toast('Finding it in your sources…')
  try {
    const r = await resolveSource({ media, mode, sources })
    if (!r || r.none) return false
    const d = await api('ext:detail', { sourceId: r.source.id, media: { title: titleOf(media), url: r.url, cover: '' } })
    if (!d.chapters.length) return false
    openAt({ source: r.source, chapters: d.chapters, index: nextIndex(d.chapters, progress), title: titleOf(media), media, mode, onProgress })
    return true
  } catch (e) { toast(e.message, true); return false }
}

// Panel inside the AniList details sheets: the matched source's chapters / episodes, found automatically.
export function extPanel({ media, mode, progress = 0, onProgress, close }) {
  const type = mode === 'anime' ? 'anime' : mode === 'novel' ? 'novel' : 'manga'
  const root = h('div')
  const note = (t) => h('p', { class: 'note', style: { margin: '0 0 12px' } }, t)
  const setup = (msg) => root.replaceChildren(emptyBox('No online source yet', msg, h('button', { class: 'btn primary', onclick: () => (close?.(), go('/extensions')) }, 'Open Extensions')))
  const prog = (r) => { progress = r.progress; onProgress?.(r) }

  async function start() {
    root.replaceChildren(h('div', { class: 'skeleton', style: { height: '120px' } }))
    let sources
    try { sources = await installedSources(type) } catch (e) { return e.code === 'NO_RUNTIME' ? setup(e.message) : root.replaceChildren(emptyBox('Extensions unavailable', e.message)) }
    if (!sources.length) return setup(`Install a ${type} extension to read or watch from online sources.`)
    const links = await getLinks()
    let cur = sources.find((s) => s.id === String(links[autoKey(media.id)] ?? '')) || sources.find((s) => s.id === ls('ds.extSource.' + type)) || sources[0]
    const pick = h('select', { class: 'input', 'aria-label': 'Source', style: { maxWidth: '260px' } }, sources.map((s) => h('option', { value: s.id, selected: s.id === cur.id }, srcLabel(s))))
    pick.onchange = () => { cur = sources.find((s) => s.id === pick.value); lset('ds.extSource.' + type, cur.id); show(cur) }
    const area = h('div')
    root.replaceChildren(h('div', { style: { marginBottom: '12px' } }, pick), area)

    async function show(only) {
      area.replaceChildren(h('div', { class: 'skeleton', style: { height: '120px' } }), h('p', { class: 'note' }, only ? `Looking for it in ${srcLabel(only)}…` : 'Looking for it in your sources…'))
      let r
      try { r = await resolveSource({ media, mode, sources, only }) } catch (e) { return area.replaceChildren(emptyBox('Search failed', e.message)) }
      if (!r || r.none) return search(only || cur, r?.failed || [])
      cur = r.source; pick.value = cur.id; lset('ds.extSource.' + type, cur.id)
      try {
        const d = await api('ext:detail', { sourceId: cur.id, media: { title: titleOf(media), url: r.url, cover: '' } })
        area.replaceChildren(
          h('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', marginBottom: '8px' } },
            h('span', { class: 'note' }, r.auto ? `Matched automatically on ${srcLabel(cur)}.` : ''),
            h('button', { class: 'btn sm', onclick: async () => { await setLink(linkKey(cur.id, media.id), null); await setLink(autoKey(media.id), null); search(cur, []) } }, 'Change series')),
          chapterList({ source: cur, chapters: d.chapters, title: titleOf(media), media, mode, progress, onProgress: prog,
            onMark: async (c) => { try { const x = await api('anilist:save', { mediaId: media.id, mode, status: 'CURRENT', progress: Math.floor(c.number) }); prog(x); show(cur) } catch (e) { toast(e.message, true) } } }))
      } catch (e) {
        area.replaceChildren(emptyBox('Could not load', e.message, h('button', { class: 'btn', onclick: async () => { await setLink(linkKey(cur.id, media.id), null); await setLink(autoKey(media.id), null); search(cur, []) } }, 'Pick another series')))
      }
    }

    async function search(src, failed) {
      const input = h('input', { class: 'input', value: titleOf(media), placeholder: 'Search this source…' })
      const out = h('div')
      const run = async () => {
        out.replaceChildren(h('div', { class: 'skeleton', style: { height: '90px' } }))
        try {
          const r = await api('ext:browse', { sourceId: src.id, kind: 'search', query: input.value.trim() })
          out.replaceChildren(...(r.list.length ? r.list.slice(0, 20).map((it) => h('div', { class: 'ep-row', onclick: async () => { await setLink(linkKey(src.id, media.id), it.url); await setLink(autoKey(media.id), src.id); show(src) } },
            h('div', { class: 'th', style: { width: '56px', aspectRatio: '2/3' } }, it.cover && h('img', { src: it.cover, alt: '' })), h('div', { class: 'tx' }, h('b', null, it.title)))) : [emptyBox('No results', 'Try a different spelling, or another source.')]))
        } catch (e) { out.replaceChildren(emptyBox('Search failed', e.message)) }
      }
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') run() })
      area.replaceChildren(
        note(failed.length ? `No confident match. ${failed.length} source${failed.length > 1 ? 's' : ''} could not be searched (${failed[0].error}). Pick the series yourself:` : 'No confident automatic match. Pick the matching series:'),
        h('div', { style: { display: 'flex', gap: '8px', marginBottom: '12px' } }, input, h('button', { class: 'btn', onclick: run }, icon('search', 18))), out)
      run()
    }
    show()
  }
  start()
  return root
}
