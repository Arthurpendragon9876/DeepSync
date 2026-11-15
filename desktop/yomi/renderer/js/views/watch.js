// YouTube-inspired anime page: player on top (waiting state until an episode is picked), title + action row, expandable
// description and notes below, and the episode list in a sidebar. Same layout idea as the Android app's anime page.
import { h, icon, api, go, onLeave, menu, toast, titleOf, coverOf, bannerOf, totalOf, pretty, stripHtml, STATUS, STATUS_ICON } from '../core.js'
import { carousel, posterCard } from '../components.js'
import { openTrackingSheet } from '../overlays.js'
import { createPlayer } from '../overlays.js'
import { createSession, sourceChip, openSourcePicker } from '../source-session.js'
import { chapterOrder, setChapterOrder, getNotes, saveNotes } from '../prefs.js'
import { when, sourceState, countdown } from './shared.js'

const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`

export function watchPage({ media, mode, entry, local, autoplay = false }) {
  const total = totalOf(media)
  const session = createSession({ media, mode })
  const eps = new Map((media.streamingEpisodes || []).map((e) => [Number((/episode\s*(\d+)/i.exec(e.title) || [])[1]), e]))
  let order = chapterOrder(), filter = '', cur = -1, files = []
  const progress = () => entry?.progress || 0

  const track = async (n) => {
    if (n == null) return
    const r = await api('track:progress', { mode, mediaId: media.id, progress: Math.floor(n), total }).catch(() => null)
    if (r?.saved) { entry = { ...(entry || {}), ...r.saved }; drawActions(); drawList() }
  }
  const player = createPlayer({ title: titleOf(media), files: [], index: -1, inline: true, poster: bannerOf(media), onWatched: (f) => track(f.episode), onIndex: (i) => { cur = i; drawList(); drawTitle() } })

  // ---- episodes: from the linked source, else local files, else numbered placeholders ----
  const items = () => {
    if (session.status === 'ready') return session.chapters.map((c, i) => ({ i, n: c.number, name: c.name, date: c.date, scan: c.scanlator }))
    if (local) return files.map((f, i) => ({ i, n: f.episode, name: f.name, local: true }))
    return []
  }
  const buildFiles = () => {
    if (session.status === 'ready') files = session.chapters.map((c) => ({ key: `ext:${session.source.id}:${c.url}`, name: c.name, episode: c.number, videos: () => api('ext:videos', { sourceId: session.source.id, episode: c }) }))
    else files = local?.files.filter((f) => f.episode != null) || []
    player.setFiles(files, nextIdx())
  }
  const nextIdx = () => { const i = files.findIndex((f) => f.episode != null && f.episode > progress()); return i >= 0 ? i : Math.max(0, files.length - 1) }
  const play = (i) => (files[i] ? player.load(i) : session.status === 'ready' || local ? toast('That episode is not available') : openSourcePicker(session, media))

  // ---- title block ----
  const epTitle = h('h1', { class: 'yt-title' }, titleOf(media))
  const epSub = h('div', { class: 'yt-sub' })
  function drawTitle() {
    const f = files[cur]
    epTitle.textContent = f ? `${titleOf(media)} · ${f.episode != null ? 'Episode ' + f.episode : f.name}` : 'Episode Selection Pending'
    epSub.replaceChildren(h('span', null, titleOf(media)), ' • ', h('span', null, f?.name && f.name !== `Episode ${f.episode}` ? f.name : 'Select an episode to begin playback'))
  }
  const actions = h('div', { class: 'yt-actions' })
  function drawActions() {
    const trk = h('button', { class: 'btn' + (entry ? ' on' : ' primary'), onclick: () => openTrackingSheet({ media, mode, entry, done: (e) => ((entry = e), drawActions()) }) }, icon(entry ? STATUS_ICON[entry.status] : 'plus', 18), entry ? STATUS[entry.status](mode) : 'Add to list')
    actions.replaceChildren(
      h('div', { class: 'yt-chan' }, h('img', { src: coverOf(media), alt: '' }), h('div', null, h('b', null, titleOf(media)), h('span', null, [pretty(media.format), total ? `${total} episodes` : '', entry ? `${progress()} watched` : ''].filter(Boolean).join(' • ')))),
      trk,
      h('div', { class: 'yt-pills' },
        h('button', { class: 'pill', title: 'Previous episode (P)', onclick: () => play(cur - 1) }, icon('skipB', 16)), h('button', { class: 'pill', title: 'Next episode (N)', onclick: () => play(cur < 0 ? nextIdx() : cur + 1) }, icon('skipF', 16)),
        h('button', { class: 'pill', title: 'Fullscreen (F)', onclick: () => player.el.requestFullscreen?.() }, icon('fullscreen', 16)),
        media.siteUrl && h('button', { class: 'pill', title: 'Open on AniList', onclick: () => window.open(media.siteUrl) }, icon('external', 16)),
        h('button', { class: 'pill', title: 'More', onclick: (e) => menu(e.currentTarget, [
          { icon: 'link', label: 'Change source…', run: () => openSourcePicker(session, media) },
          { icon: 'sync', label: 'Re-check the source', run: () => session.resolve(session.source || undefined) },
          { icon: 'grid', label: 'Back to details', run: () => history.back() },
        ]) }, icon('dots', 16))))
  }

  // ---- description card (chips like ★ 5.8 | 7 Eps | Adventure | Comedy) ----
  const desc = h('div', { class: 'desc clamp' }, stripHtml(media.description) || 'No description.')
  const readMore = h('button', { class: 'yt-more', onclick: () => { desc.classList.toggle('clamp'); readMore.replaceChildren('', desc.classList.contains('clamp') ? 'Read more' : 'Show less', icon(desc.classList.contains('clamp') ? 'chevD' : 'chevR', 16)) } }, 'Read more', icon('chevD', 16))
  const descCard = h('div', { class: 'yt-card' },
    h('div', { class: 'yt-chips' }, media.averageScore ? h('span', { class: 'chip warn' }, icon('star', 14), (media.averageScore / 10).toFixed(1)) : null, total ? h('span', { class: 'chip' }, `${total} Eps`) : null,
      ...(media.genres || []).slice(0, 5).map((g) => h('span', { class: 'chip btn-like', onclick: () => go(`/search?mode=anime&genre=${encodeURIComponent(g)}`) }, g)),
      media.nextAiringEpisode ? h('span', { class: 'chip acc' }, `Ep ${media.nextAiringEpisode.episode} in ${countdown(media.nextAiringEpisode.airingAt - Date.now() / 1000)}`) : null),
    desc, readMore)

  // ---- notes & annotations: timestamped, per series ----
  let notes = getNotes(mode, media.id), notesOpen = false
  const notesBox = h('div', { class: 'yt-card notes' })
  function drawNotes() {
    const input = h('textarea', { class: 'input', rows: 2, placeholder: 'Add a note at the current time…' })
    const add = () => {
      const text = input.value.trim(); if (!text) return
      const f = files[cur]
      notes.unshift({ id: Date.now(), ep: f?.episode ?? null, t: f ? player.video.currentTime || 0 : 0, text })
      saveNotes(mode, media.id, notes); drawNotes()
    }
    notesBox.replaceChildren(
      h('button', { class: 'notes-head', onclick: () => ((notesOpen = !notesOpen), drawNotes()) }, icon('menu', 20), h('b', null, 'Notes & Annotations'), h('span', { class: 'count' }, notes.length), icon(notesOpen ? 'chevD' : 'chevR', 18)),
      notesOpen ? h('div', { class: 'notes-body' }, input, h('button', { class: 'btn sm primary', style: { marginTop: '8px' }, onclick: add }, icon('plus', 16), 'Add note'),
        ...notes.map((n) => h('div', { class: 'note-row' },
          h('button', { class: 'ts', title: 'Jump to this moment', onclick: () => { const i = files.findIndex((f) => f.episode === n.ep); if (i < 0) return toast('Open that episode first'); const seek = () => (player.video.currentTime = n.t); if (cur === i) seek(); else (player.load(i, false), player.video.addEventListener('loadedmetadata', seek, { once: true })) } }, n.ep != null ? `Ep ${n.ep} · ${clock(n.t)}` : 'General'),
          h('p', null, n.text), h('button', { class: 'ibtn', 'aria-label': 'Delete note', onclick: () => ((notes = notes.filter((x) => x.id !== n.id)), saveNotes(mode, media.id, notes), drawNotes()) }, icon('trash', 16))))) : '')
  }

  // ---- sidebar: episode list ----
  const chip = sourceChip(session, media)
  const list = h('div', { class: 'yt-eps' })
  const head = h('div', { class: 'yt-side-head' })
  function drawList() {
    const all = items(), prog = progress()
    const st = sourceState(session, { onRetry: () => session.resolve(session.source), goExt: () => go('/extensions'), openPicker: () => openSourcePicker(session, media) })
    head.replaceChildren(h('div', { class: 'yt-side-top' }, h('h3', null, `${all.length || total || 0} Episodes`), h('span', { class: 'chip' + (cur < 0 ? ' acc' : '') }, cur < 0 ? 'Select below' : `Playing ${files[cur]?.episode ?? ''}`), chip),
      h('div', { class: 'yt-side-tools' }, h('input', { class: 'input', placeholder: 'Filter episodes…', value: filter, oninput: (e) => ((filter = e.target.value.toLowerCase()), drawList()) }),
        h('button', { class: 'ibtn', title: order === 'desc' ? 'Newest first' : 'Oldest first', onclick: () => ((order = order === 'desc' ? 'asc' : 'desc'), setChapterOrder(order), drawList()) }, icon('filter', 18))))
    if (!all.length) {
      if (st) return list.replaceChildren(st)
      return list.replaceChildren(...Array.from({ length: Math.min(total || 12, 60) }, (_, k) => k + 1).map((n) => h('div', { class: 'yt-ep', onclick: () => openSourcePicker(session, media) }, thumb(n), h('div', { class: 'yt-ep-tx' }, h('small', null, `EPISODE ${n}`), h('b', null, titleOf(media)), h('span', null, eps.get(n)?.title || '')))))
    }
    let rows = all.filter((x) => !filter || `${x.name} ${x.n ?? ''}`.toLowerCase().includes(filter))
    if (order === 'desc') rows = rows.reverse()
    list.replaceChildren(...rows.map((x) => h('div', { class: 'yt-ep' + (x.i === cur ? ' now' : '') + (x.n != null && x.n <= prog ? ' done' : ''), onclick: () => play(x.i) },
      thumb(x.n, x.i === cur),
      h('div', { class: 'yt-ep-tx' }, h('small', null, x.n != null ? `EPISODE ${x.n}` : 'EPISODE'), h('b', null, titleOf(media)), h('span', null, [x.name && x.name !== `Episode ${x.n}` ? x.name : '', when(x.date)].filter(Boolean).join(' · '))),
      x.n != null && x.n <= prog ? icon('check', 20) : null)))
    list.querySelector('.now')?.scrollIntoView({ block: 'nearest' })
  }
  const thumb = (n, playing) => h('div', { class: 'yt-th' }, h('img', { src: eps.get(n)?.thumbnail || coverOf(media), alt: '', loading: 'lazy' }), h('i', null, icon(playing ? 'pause' : 'play', 16)))

  // "Continue" from the floating button: start the next episode as soon as the list is there.
  const autoStart = () => { if (autoplay && files.length) { autoplay = false; player.load(nextIdx()) } }
  session.on(() => { buildFiles(); drawList(); drawTitle(); autoStart() })
  // The saved link / automatic match is resolved as soon as the page opens, so episodes are already there.
  buildFiles()
  autoStart()
  session.init()

  drawActions(); drawTitle(); drawNotes(); drawList()

  // Keys: the player itself handles them (same remappable table as the full-screen player; Settings → Player).
  onLeave(() => player.destroy())

  const rel = media.relations?.edges || [], recs = (media.recommendations?.nodes || []).map((n) => n.mediaRecommendation).filter(Boolean)
  return h('div', { class: 'yt-page' },
    h('button', { class: 'ibtn yt-back', onclick: () => history.back(), 'aria-label': 'Back' }, icon('back')),
    h('div', { class: 'yt' },
      h('div', { class: 'yt-main' }, player.el, epTitle, epSub, actions, descCard, notesBox),
      h('aside', { class: 'yt-side' }, head, list)),
    rel.length ? carousel('Related', rel, { render: (e) => posterCard(e.node, { sub: pretty(e.relationType) }) }) : null,
    recs.length ? carousel('Recommended', recs) : null, h('div', { style: { height: 40 } }))
}
