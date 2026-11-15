import { h, icon, S, api, data, go, menu, titleOf, coverOf, bannerOf, unit, totalOf, pretty, stripHtml, STATUS, STATUS_ICON, toast } from '../core.js'
import { carousel, posterCard } from '../components.js'
import { openTrackingSheet, openEpisodesSheet, openChaptersSheet, openNovelReader, openPlayer } from '../overlays.js'
import { createSession, sourceChip, openSourcePicker } from '../source-session.js'
import { chapterOrder, setChapterOrder } from '../prefs.js'
import { findLocal, countdown, mins, when, waveBar, infoGrid, altTitles, sourceState } from './shared.js'
import { watchPage } from './watch.js'
import { isSrcId, buildSourceMedia, findOnTracker } from '../srcmedia.js'
import { isMetaId, buildMetaMedia } from '../metadata.js'
import { favMenu, isFav } from '../library-data.js'
import { chaptersPage } from './chapters.js'
import { novelChaptersPage } from './novel-chapters.js'

export { findLocal }

async function loadCtx(params) {
  const mode = params.type
  const media = isSrcId(params.id) ? await buildSourceMedia(params.id, mode) : isMetaId(params.id) ? await buildMetaMedia(params.id, mode) : await data('anilist:details', { id: +params.id })
  const [entryRes, lib] = await Promise.all([api('anilist:entry', { mediaId: media.id, mode }).catch(() => ({ data: null })), api('library:get').catch(() => null)])
  return { media, mode, entry: entryRes.data, lib, local: findLocal(lib, media, mode) }
}

// /media/:type/:id  The details page, same as the Android app: banner, description, info. Anime and manga never jump straight
// into their episode / chapter page; the Episodes / Chapters entry on this page opens it.
export async function details({ params }) {
  return classic(await loadCtx(params))
}
// /media/anime/:id/episodes  YouTube-style episode page (player + episode list). Opened from the details page.
export async function episodesPage({ params, query }) {
  const ctx = { ...(await loadCtx(params)), autoplay: !!query?.continue }
  return ctx.mode === 'anime' ? watchPage(ctx) : classic(ctx)
}
// /media/manga|novel/:id/chapters  Chapter workspace: Chapters / Page Previews / Bookmarks tabs and a live page inspector.
export async function chaptersRoute({ params, query }) {
  const ctx = { ...(await loadCtx(params)), autoplay: !!query?.continue }
  return ctx.mode === 'manga' ? chaptersPage(ctx) : ctx.mode === 'novel' ? novelChaptersPage(ctx) : classic(ctx)
}

function classic({ media, mode, entry, local }) {
  const total = totalOf(media)
  const session = createSession({ media, mode })
  const hero = h('div', { class: 'd-actions' })
  const progCard = h('div')
  const list = h('div', { class: 'chap-list' })
  const listHead = h('div', { class: 'chap-head' })
  const fab = h('div', { class: 'cta-wrap' })
  let order = chapterOrder(), filter = ''
  const anime = mode === 'anime'

  const onProgress = (r) => { entry = { ...(entry || {}), ...r }; draw() }
  const progress = () => entry?.progress || 0

  // ---- continue: the linked source is already resolved, so this opens the next chapter / episode straight away ----
  async function cont(fromStart) {
    const prog = fromStart ? 0 : progress()
    if (mode === 'novel' && session.status !== 'ready') return local ? openNovelReader({ path: local.files[0].path, title: local.title }) : toast('No local EPUB found. Add your novel folder in Settings > Library.')
    if (session.status === 'ready') return session.open(session.next(prog), onProgress)
    if (session.status === 'resolving' || session.status === 'loading') return toast('Still finding your source…')
    const next = prog + 1
    if (anime) {
      const files = local?.files.filter((f) => f.episode != null) || [], f = files.find((x) => x.episode === next)
      if (f) return openPlayer({ title: titleOf(media), files, index: files.indexOf(f), onWatched: async (x) => { const r = await api('track:progress', { mode, mediaId: media.id, progress: x.episode, total }).catch(() => null); if (r?.saved) onProgress(r.saved) } })
      return openEpisodesSheet({ media, entry, local, onProgress })
    }
    if (local?.files.some((f) => f.chapter === next)) return openChaptersSheet({ media, mode, entry, local, onProgress })
    if (session.status === 'nomatch') return openSourcePicker(session, media)
    openChaptersSheet({ media, mode, entry, local, onProgress })
  }

  const trackAction = () => (media.isSource || media.isMeta ? findOnTracker({ media, mode }) : openTrackingSheet({ media, mode, entry, done: (e) => ((entry = e), draw()) }))
  let faved = false
  isFav(media, mode).then((v) => { faved = v; draw() })
  function draw() {
    const prog = progress(), next = session.chapters.length ? session.chapters[session.next(prog)] : null
    const tracker = h('button', { class: 'squircle' + (entry ? ' tracked' : ''), title: entry ? STATUS[entry.status](mode) : 'Add to list', onclick: () => trackAction() }, icon(entry ? STATUS_ICON[entry.status] : 'plus', 22))
    hero.replaceChildren(...[
      h('button', { class: 'btn wide-btn' + (entry ? ' on' : ''), onclick: () => trackAction() }, icon(entry ? STATUS_ICON[entry.status] : 'plus', 18), media.isSource || media.isMeta ? 'Track on AniList' : entry ? STATUS[entry.status](mode) : 'Add to list'),
      h('button', { class: 'squircle', title: 'Re-check the source', onclick: () => (session.status === 'local' ? toast('Nothing to sync for a local novel') : session.resolve(session.source || undefined)) }, icon('sync', 20)),
      h('button', { class: 'squircle' + (faved ? ' tracked' : ''), title: faved ? 'In Favourites' : 'Add to Favourites', onclick: (e) => favMenu(e.currentTarget, media, mode, (v) => ((faved = v), draw())) }, icon('heart', 20)),
      media.siteUrl && h('button', { class: 'squircle', title: 'Open on AniList', onclick: () => window.open(media.siteUrl) }, icon('external', 20)),
      h('button', { class: 'list-pill', title: anime ? 'Open the episode list' : 'Open the chapter list', onclick: () => go(listPath) }, icon(anime ? 'film' : 'list', 18),
        session.chapters.length ? `${session.chapters.length} ${anime ? 'episodes' : 'chapters'}` : session.status === 'resolving' || session.status === 'loading' ? 'Loading…' : total ? `${total} ${anime ? 'episodes' : 'chapters'}` : anime ? 'Episodes' : 'Chapters'),
      local && h('span', { class: 'chip acc' }, icon('folder', 14), `${local.files.length} local`),
    ].filter(Boolean))
    void tracker

    // progress card, like "Chapter 132 of 348 (18 h 32 m) 37%"
    const tot = Math.floor(Math.max(total, session.latest()))
    const pct = tot ? Math.min(100, (prog / tot) * 100) : 0
    const pctTxt = pct >= 100 ? '100' : pct < 10 && pct > 0 ? pct.toFixed(1) : String(Math.floor(pct))
    const per = anime ? media.duration || 24 : mode === 'novel' ? 14 : 5
    progCard.replaceChildren(entry || prog ? h('div', { class: 'card prog-card' },
      h('div', { class: 'prog-top' }, icon(anime ? 'film' : 'book', 22), h('span', null, `${unit(mode)} ${prog}${tot ? ` of ${tot}` : ''}`, tot && tot > prog ? h('em', null, ` (~${mins((tot - prog) * per)} left)`) : null), h('b', null, tot ? `${pctTxt}%` : '')),
      waveBar(tot ? pct : 4)) : '')
    fab.replaceChildren(h('div', { class: 'cta' },
      h('button', { class: 'cta-main', onclick: () => cont(false) }, icon('play', 20), prog ? 'Continue' : anime ? 'Watch' : 'Read', next && prog ? h('span', null, next.name ? `· ${next.number != null ? unit(mode) + ' ' + next.number : next.name}` : '') : null),
      h('button', { class: 'cta-more', 'aria-label': 'More', onclick: (e) => menu(e.currentTarget, [
        { icon: 'play', label: prog ? `Continue ${unit(mode).toLowerCase()} ${prog + 1}` : 'Start', run: () => cont(false) },
        { icon: 'skipB', label: 'Start from the beginning', run: () => cont(true) },
        { icon: 'list', label: `Pick ${unit(mode).toLowerCase()}…`, run: () => go(listPath) },
        { icon: 'link', label: 'Change source…', run: () => openSourcePicker(session, media) },
      ]) }, icon('chevD', 18))))
  }

  // ---- chapter / episode list, loaded from the linked source ----
  const rowsOf = () => {
    const chs = session.chapters.map((c, i) => ({ c, i })).filter(({ c }) => !filter || `${c.name} ${c.number ?? ''}`.toLowerCase().includes(filter))
    return order === 'desc' ? chs.reverse() : chs
  }
  function drawList() {
    const prog = progress()
    const st = sourceState(session, { onRetry: () => session.resolve(session.source), goExt: () => go('/extensions'), openPicker: () => openSourcePicker(session, media) })
    if (mode === 'novel' && session.status === 'local') {
      listHead.replaceChildren(h('h3', null, 'Read'))
      return list.replaceChildren(local ? h('div', { class: 'ep-row', onclick: () => openNovelReader({ path: local.files[0].path, title: local.title }) }, h('div', { class: 'th', style: { width: '56px', aspectRatio: '1' } }, icon('book', 22)), h('div', { class: 'tx' }, h('b', null, local.title), h('span', null, local.author || 'Local EPUB')), h('div', { class: 'st' }, icon('chevR', 18))) : h('div', { class: 'empty small' }, h('h3', null, 'No local EPUB'), h('p', null, 'Add your novel folder under Settings > Library and it links to this title automatically.')))
    }
    listHead.replaceChildren(h('h3', null, session.chapters.length ? `${session.chapters.length} ${anime ? 'episodes' : 'chapters'}` : anime ? 'Episodes' : 'Chapters'),
      session.chapters.length ? h('div', { class: 'chap-tools' },
        h('input', { class: 'input', placeholder: 'Filter…', value: filter, oninput: (e) => ((filter = e.target.value.toLowerCase()), drawList()) }),
        h('button', { class: 'btn sm', onclick: () => ((order = order === 'desc' ? 'asc' : 'desc'), setChapterOrder(order), drawList()) }, icon('filter', 14), order === 'desc' ? 'Newest first' : 'Oldest first')) : '')
    if (st) return list.replaceChildren(st)
    list.replaceChildren(...rowsOf().map(({ c, i }) => h('div', { class: 'ep-row' + (c.number != null && c.number <= prog ? ' done' : ''), onclick: () => session.open(i, onProgress) },
      h('div', { class: 'th', style: { width: '56px', aspectRatio: '1' } }, c.number ?? '#'),
      h('div', { class: 'tx' }, h('b', null, c.name || `${unit(mode)} ${c.number ?? ''}`.trim()), h('span', null, [c.scanlator, when(c.date)].filter(Boolean).join(' · '))),
      h('div', { class: 'st' }, c.number != null && h('button', { class: 'ibtn', style: { background: 'var(--surface-3)' }, title: 'Mark as read up to here', onclick: async (e) => { e.stopPropagation(); try { onProgress(await api('anilist:save', { mediaId: media.id, mode, status: total && c.number >= total ? 'COMPLETED' : 'CURRENT', progress: Math.floor(c.number) })); drawList() } catch (er) { toast(er.message, true) } } }, icon('check', 18)), icon(anime ? 'play' : 'chevR', 18)))))
  }
  // Anime and manga: the list lives on its own page (like the Android Episodes / Chapters tab); this card is the way in.
  const listPath = `/media/${mode}/${media.id}/${anime ? 'episodes' : 'chapters'}`
  session.on(() => draw()); draw()
  session.init()

  const chips = [pretty(media.status) && h('span', { class: 'chip acc' }, pretty(media.status)), pretty(media.format) && h('span', { class: 'chip' }, pretty(media.format)), media.seasonYear && h('span', { class: 'chip' }, `${pretty(media.season)} ${media.seasonYear}`.trim()),
    media.nextAiringEpisode && h('span', { class: 'chip' }, `Ep ${media.nextAiringEpisode.episode} in ${countdown(media.nextAiringEpisode.airingAt - Date.now() / 1000)}`)].filter(Boolean)
  const byline = [media.studios?.nodes?.[0]?.name, media.title.native].filter(Boolean).join(' · ')
  const desc = h('div', { class: 'desc clamp' }, stripHtml(media.description) || 'No description.')
  const more = h('button', { class: 'btn sm', style: { marginTop: 10 }, onclick: () => { desc.classList.toggle('clamp'); more.textContent = desc.classList.contains('clamp') ? 'Read more' : 'Show less' } }, 'Read more')
  const recs = (media.recommendations?.nodes || []).map((n) => n.mediaRecommendation).filter(Boolean)
  const rel = media.relations?.edges || []
  const chars = media.characters?.edges || []
  const tags = (media.tags || []).filter((t) => !t.isMediaSpoiler).slice(0, 10)

  return h('div', { class: 'd-page' },
    h('div', { class: 'd-hero' },
      h('div', { class: 'bgimg', style: { backgroundImage: `url("${bannerOf(media)}")` } }),
      h('button', { class: 'ibtn back', onclick: () => history.back(), 'aria-label': 'Back' }, icon('back')),
      h('div', { class: 'd-top' },
        h('div', { class: 'd-cover' }, h('img', { src: coverOf(media), alt: '' })),
        h('div', { class: 'd-info' }, h('h1', null, titleOf(media)), byline ? h('div', { class: 'alt' }, byline) : null,
          h('div', { class: 'd-chips' }, ...chips, sourceChip(session, media)), hero))),
    h('div', { class: 'd-body' },
      h('div', null, progCard,
        h('div', { class: 'card', style: { marginTop: 14 } }, h('div', { class: 'desc-head' }, h('div', { class: 'h3', style: { margin: 0 } }, 'Description'), media.averageScore ? h('span', { class: 'chip' }, icon('star', 14), (media.averageScore / 10).toFixed(1)) : null), desc, stripHtml(media.description).length > 380 ? more : null,
          h('div', { class: 'd-chips', style: { marginTop: 16 } }, (media.genres || []).map((g) => h('span', { class: 'chip btn-like', onclick: () => go(`/search?mode=${mode}&genre=${encodeURIComponent(g)}`) }, g))),
          tags.length ? h('div', { class: 'd-chips', style: { marginTop: 10 } }, tags.map((t) => h('span', { class: 'chip', style: { opacity: 0.7 } }, `${t.name} ${t.rank}%`))) : null),
        chars.length ? [h('div', { class: 'h3' }, 'Characters'), h('div', { class: 'chars' }, chars.map((c) => h('div', { class: 'c' }, h('img', { src: c.node.image?.large || '', alt: '', loading: 'lazy' }), h('div', null, c.node.name.userPreferred))))] : null),
      h('div', { class: 'd-side' }, infoGrid(media, mode, total), altTitles(media))),
    rel.length ? carousel('Related', rel, { render: (e) => posterCard(e.node, { sub: pretty(e.relationType) }) }) : null,
    recs.length ? carousel('Recommended', recs) : null,
    h('div', { style: { height: 90 } }), fab)
}
