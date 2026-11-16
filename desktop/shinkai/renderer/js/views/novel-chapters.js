// Novel chapter workspace (same layout as the manga one): hero, Chapters table / Previews tabs and a live chapter inspector.
// "Previews" shows an excerpt of the chapter text plus every illustration found in it (when the chapter has any).
import { h, icon, api, go, menu, toast, onLeave, overlay, titleOf, coverOf, bannerOf, totalOf, pretty, stripHtml, STATUS, STATUS_ICON } from '../core.js'
import { openTrackingSheet, openNovelReader } from '../overlays.js'
import { createSession, sourceChip, openSourcePicker } from '../source-session.js'
import { chapterOrder, setChapterOrder } from '../prefs.js'
import { when, sourceState, mins } from './shared.js'

const label = (c, i) => c?.name || `Chapter ${c?.number ?? i + 1}`
const parseChapter = (html) => {
  const doc = new DOMParser().parseFromString(html || '', 'text/html')
  const imgs = [...new Set([...doc.images].map((i) => i.getAttribute('src') || i.dataset?.src).filter(Boolean))]
  const paras = [...doc.querySelectorAll('p,h1,h2,h3,blockquote')].map((e) => e.textContent.replace(/\s+/g, ' ').trim()).filter((t) => t.length > 1)
  const text = paras.length ? paras.join(' ') : (doc.body?.textContent || '').replace(/\s+/g, ' ').trim()
  const words = text ? text.split(' ').length : 0
  let excerpt = [], n = 0
  for (const p of paras.length ? paras : [text]) { excerpt.push(p); n += p.length; if (n > 900) break }
  return { imgs, words, excerpt }
}

function lightbox(urls, start) {
  let i = start
  const img = h('img', { src: urls[i], alt: '' }), cap = h('span', { class: 'lb-cap' }, `${i + 1} / ${urls.length}`)
  const step = (d) => { i = (i + d + urls.length) % urls.length; img.src = urls[i]; cap.textContent = `${i + 1} / ${urls.length}` }
  const key = (e) => { if (e.key === 'Escape') close(); else if (e.key === 'ArrowLeft') step(-1); else if (e.key === 'ArrowRight') step(1) }
  const box = h('div', { class: 'lightbox', onmousedown: (e) => e.target === box && close() },
    h('button', { class: 'ibtn lb-x', onclick: () => close(), 'aria-label': 'Close' }, icon('x')),
    urls.length > 1 && h('button', { class: 'ibtn lb-l', onclick: () => step(-1), 'aria-label': 'Previous' }, icon('chevL')), img,
    urls.length > 1 && h('button', { class: 'ibtn lb-r', onclick: () => step(1), 'aria-label': 'Next' }, icon('chevR')), cap)
  const close = overlay(box, { onClose: () => window.removeEventListener('keydown', key) })
  window.addEventListener('keydown', key)
}

export function novelChaptersPage({ media, mode, entry, local, autoplay = false }) {
  const title = titleOf(media), total = totalOf(media)
  const session = createSession({ media, mode })
  let tab = 'chapters', order = chapterOrder(), filter = '', hideRead = false, inspected = -1, inspKey = null, epub = null
  const cache = new Map()
  const progress = () => entry?.progress || 0
  const onProgress = (r) => { entry = { ...(entry || {}), ...r }; drawHero(); drawTable(); drawInspector() }

  // ---- chapters: from the linked source, else the local EPUB's table of contents ----
  const chapters = () => (session.status === 'ready' ? session.chapters : epub || [])
  const isLocal = () => session.status !== 'ready' && !!epub
  const keyOf = (i) => { const c = chapters()[i]; return c ? (c.url ?? i) + '' : '' }
  async function loadEpub() {
    if (epub || !local?.files?.[0]) return
    try { const info = await api('epub:open', { path: local.files[0].path }); epub = info.chapters.map((c, i) => ({ name: c.title, number: i + 1, url: c.href, href: c.href })); afterChapters() } catch (e) { toast(e.message, true) }
  }
  async function content(i) {
    const k = keyOf(i)
    if (cache.has(k)) return cache.get(k)
    const c = chapters()[i]
    const raw = isLocal() ? await api('epub:chapter', { path: local.files[0].path, href: c.href }) : (await api('ext:chapterText', { sourceId: session.source.id, chapter: c })).html
    const r = parseChapter(raw); cache.set(k, r); return r
  }
  async function read(i) {
    if (session.status === 'ready') return session.open(i, onProgress)
    if (isLocal()) { await api('progress:set', { key: 'novel:' + local.files[0].path, value: { chapter: i, scroll: 0 } }).catch(() => {}); return openNovelReader({ path: local.files[0].path, title }) }
    if (session.status === 'resolving' || session.status === 'loading') return toast('Still finding your source…')
    openSourcePicker(session, media)
  }
  const nextIdx = () => (session.status === 'ready' ? session.next(progress()) : Math.min(progress(), Math.max(0, chapters().length - 1)))
  const start = () => read(nextIdx())

  // ---- top bar ----
  const search = h('input', { class: 'input', placeholder: 'Search chapters, or jump to #…', oninput: (e) => setFilter(e.target.value) })
  const setFilter = (v) => { filter = v.toLowerCase().trim(); if (search.value !== v) search.value = v; if (filter.startsWith('#')) filter = filter.slice(1); if (filterIn.value !== v) filterIn.value = v; drawTable() }
  async function markAll() {
    if (!total) return toast('Total chapter count unknown', true)
    try { onProgress(await api('anilist:save', { mediaId: media.id, mode, status: 'COMPLETED', progress: total })) } catch (e) { toast(e.message, true) }
  }
  const topbar = h('div', { class: 'cw-top' },
    h('button', { class: 'ibtn round', onclick: () => history.back(), 'aria-label': 'Back' }, icon('back', 18)),
    h('nav', { class: 'crumbs' }, h('a', { onclick: () => go('/library') }, 'Library'), h('i', null, '/'), h('a', { onclick: () => go('/discover/novel') }, 'Novels'), h('i', null, '/'), h('b', null, title)),
    h('div', { class: 'cw-search' }, icon('search', 16), search, h('kbd', null, 'Ctrl+K')),
    h('div', { class: 'cw-top-r' },
      h('button', { class: 'ibtn round', title: 'Copy link', onclick: () => { if (media.siteUrl) { navigator.clipboard?.writeText(media.siteUrl); toast('Link copied') } } }, icon('share', 16)),
      h('button', { class: 'ibtn round', title: 'More', onclick: (e) => menu(e.currentTarget, [
        { icon: 'play', label: `Resume reading${progress() ? ` (Ch. ${progress() + 1})` : ''}`, run: start },
        { icon: 'check', label: total ? `Mark all ${total} chapters read` : 'Mark all read', run: markAll },
        { icon: 'link', label: 'Change source…', run: () => openSourcePicker(session, media) },
        { icon: 'sync', label: 'Re-check the source', run: () => session.resolve(session.source || undefined) },
        media.siteUrl && { icon: 'external', label: 'Open on AniList', run: () => window.open(media.siteUrl) },
      ].filter(Boolean)) }, icon('dots', 16))))
  const key = (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); search.focus(); search.select() } }
  window.addEventListener('keydown', key); onLeave(() => window.removeEventListener('keydown', key))

  // ---- hero ----
  const hero = h('section', { class: 'cw-hero' }), srcChip = sourceChip(session, media), desc = stripHtml(media.description) || 'No description.'
  function drawHero() {
    const n = chapters().length || total, nextC = chapters()[nextIdx()]
    const add = () => openTrackingSheet({ media, mode, entry, done: (e) => ((entry = e), drawHero()) })
    hero.replaceChildren(
      h('div', { class: 'cw-hero-bg', style: { backgroundImage: `url("${bannerOf(media)}")` } }),
      h('div', { class: 'cw-hero-in' },
        h('div', { class: 'cw-cover', onclick: start }, h('img', { src: coverOf(media), alt: '' }), h('div', { class: 'cw-cover-hover' }, h('b', null, nextC?.number != null && progress() ? `Read Ch. ${nextC.number}` : 'Read Ch. 1'), h('span', null, 'Click to launch reader'))),
        h('div', { class: 'cw-meta' },
          h('div', { class: 'cw-title' }, h('h1', null, title), pretty(media.status) && h('span', { class: 'pillchip' }, pretty(media.status)), srcChip),
          h('p', { class: 'cw-author' }, [media.staff?.edges?.[0]?.node?.name?.full, media.title.native].filter(Boolean)[0] || ''),
          h('p', { class: 'cw-syn' }, desc),
          h('div', { class: 'cw-actions' },
            h('div', { class: 'split' + (entry ? ' on' : '') },
              h('button', { class: 'main', onclick: add }, icon(entry ? STATUS_ICON[entry.status] : 'plus', 18), entry ? STATUS[entry.status](mode) : 'Add to Library'),
              h('button', { class: 'drop', title: 'Reading status', onclick: add }, icon('chevD', 16))),
            h('button', { class: 'sq', title: 'Re-check the source', onclick: () => session.resolve(session.source || undefined) }, icon('sync', 20)),
            h('div', { class: 'metrics' }, icon('book', 16), h('span', null, `${n || '?'} chapters`, n ? h('em', null, ` (${mins(n * 14)})`) : null), h('i'),
              h('button', { class: hideRead ? 'on' : '', title: hideRead ? 'Show read chapters' : 'Hide read chapters', onclick: () => ((hideRead = !hideRead), drawHero(), drawTable()) }, icon(hideRead ? 'eyeoff' : 'eye', 16))))),
        h('button', { class: 'cw-start', onclick: start }, icon('play', 16), progress() && nextC?.number != null ? `Continue Ch. ${nextC.number}` : 'Start Reading Ch. 1')))
  }

  // ---- toolbar ----
  const tabsEl = h('div', { class: 'cw-tabs' })
  const sortBtn = h('button', { class: 'cw-sort', onclick: () => { order = order === 'desc' ? 'asc' : 'desc'; setChapterOrder(order); drawToolbar(); drawTable(); if (tab === 'previews') drawPreviews() } })
  const filterIn = h('input', { class: 'input', placeholder: 'Filter chapters…', oninput: (e) => setFilter(e.target.value) })
  const tools = h('div', { class: 'cw-tools' }, h('div', { class: 'cw-filter' }, icon('search', 14), filterIn), sortBtn)
  const toolbar = h('div', { class: 'cw-toolbar' }, tabsEl, tools)
  function drawToolbar() {
    const t = (id, ico, text) => h('button', { class: 'cw-tab' + (tab === id ? ' on' : ''), onclick: () => switchTab(id) }, icon(ico, 16), text)
    tabsEl.replaceChildren(t('chapters', 'list', `Chapters (${chapters().length || total || 0})`), t('previews', 'grid', `Previews${inspected >= 0 ? ` (${shortName(inspected)})` : ''}`))
    tools.style.display = tab === 'chapters' ? '' : 'none'
    sortBtn.replaceChildren(icon(order === 'desc' ? 'sortDown' : 'sortUp', 16), order === 'desc' ? 'Desc' : 'Asc')
  }
  const shortName = (i) => { const c = chapters()[i]; if (!c) return ''; const n = c.number != null ? `Ch. ${c.number}` : ''; const nm = (c.name || '').replace(/^(chapter|ch\.?)\s*\d+(\.\d+)?\s*[-:–]?\s*/i, '').trim(); return [n, nm].filter(Boolean).join(' - ') || label(c, i) }
  function switchTab(id) { tab = id; if (id === 'previews' && inspected < 0 && chapters().length) inspect(nextIdx()); drawToolbar(); show() }

  // ---- table ----
  const tbody = h('tbody')
  const tableWrap = h('div', { class: 'cw-table' }, h('table', null,
    h('thead', null, h('tr', null, ['Status', 'Chapter & Title', 'Source', 'Release Date', 'Words', 'Actions'].map((x, i) => h('th', { class: i === 5 ? 'r' : i === 4 ? 'c' : '' }, x)))), tbody))
  const stateBox = h('div', { class: 'cw-state' })
  const rows = () => {
    const prog = progress()
    const r = chapters().map((c, i) => ({ c, i })).filter(({ c, i }) => (!filter || `${label(c, i)} ${c.number ?? ''}`.toLowerCase().includes(filter)) && !(hideRead && c.number != null && c.number <= prog))
    return order === 'desc' ? r.reverse() : r
  }
  function drawTable() {
    const st = chapters().length ? null : sourceState(session, { onRetry: () => session.resolve(session.source), goExt: () => go('/extensions'), openPicker: () => openSourcePicker(session, media) })
    stateBox.style.display = st ? '' : 'none'; stateBox.replaceChildren(...(st ? [st] : []))
    tableWrap.style.display = st ? 'none' : ''
    const prog = progress(), list = rows()
    tbody.replaceChildren(...(list.length || st ? list.map(({ c, i }) => {
      const done = c.number != null && c.number <= prog, info = cache.get(keyOf(i))
      return h('tr', { class: (done ? 'done ' : '') + (i === inspected ? 'sel' : ''), onclick: () => read(i) },
        h('td', { class: 'st' }, h('span', { class: 'dot' + (done ? ' read' : '') })),
        h('td', { class: 'nm' }, h('b', null, label(c, i)), i === inspected ? h('span', { class: 'tag' }, 'Inspecting') : null),
        h('td', null, h('span', { class: 'grp' }, c.scanlator || (isLocal() ? 'EPUB' : 'official'))),
        h('td', { class: 'dt' }, when(c.date) || '—'),
        h('td', { class: 'c dt' }, info ? `${info.words.toLocaleString()}${info.imgs.length ? ` · ${info.imgs.length} img` : ''}` : '—'),
        h('td', { class: 'r' }, h('div', { class: 'acts' },
          h('button', { class: 'btn sm', onclick: (e) => { e.stopPropagation(); inspect(i) } }, 'Preview'),
          c.number != null && h('button', { class: 'ibtn flat', title: 'Mark as read up to here', onclick: async (e) => { e.stopPropagation(); try { onProgress(await api('anilist:save', { mediaId: media.id, mode, status: total && c.number >= total ? 'COMPLETED' : 'CURRENT', progress: Math.floor(c.number) })) } catch (er) { toast(er.message, true) } } }, icon(done ? 'check' : 'download', 16)))))
    }) : [h('tr', null, h('td', { colSpan: 6, class: 'none' }, 'No chapters match your filters.'))]))
  }

  // ---- previews tab: excerpt + illustrations ----
  const previews = h('div', { class: 'cw-previews' })
  async function drawPreviews() {
    if (inspected < 0) return previews.replaceChildren(h('div', { class: 'empty small' }, h('h3', null, 'Pick a chapter'), h('p', null, 'Press Preview on any chapter to see its text and illustrations here.')))
    const mine = inspected
    const select = h('select', { class: 'input cw-sel', onchange: (e) => inspect(+e.target.value) }, (order === 'desc' ? [...chapters().keys()].reverse() : [...chapters().keys()]).map((i) => h('option', { value: i, selected: i === inspected }, shortName(i))))
    const badge = h('span', { class: 'note' }, 'Loading chapter…'), body = h('div', { class: 'cw-nbody' })
    previews.replaceChildren(h('div', { class: 'cw-prev-head' }, h('div', { class: 'l' }, select, badge),
      h('div', { class: 'r' }, h('button', { class: 'btn sm', onclick: () => inspect(inspected - 1) }, '← Previous Chapter'), h('button', { class: 'btn sm', onclick: () => inspect(inspected + 1) }, 'Next Chapter →'), h('button', { class: 'btn sm light', onclick: () => read(inspected) }, icon('book', 14), 'Open in Reader'))), body)
    body.append(h('div', { class: 'skeleton', style: { height: '220px', borderRadius: '16px' } }))
    try {
      const info = await content(mine); if (mine !== inspected) return
      badge.textContent = `${info.words.toLocaleString()} words • ~${Math.max(1, Math.ceil(info.words / 230))} min read • ${info.imgs.length ? `${info.imgs.length} illustration${info.imgs.length > 1 ? 's' : ''}` : 'no illustrations'}`
      body.replaceChildren(
        h('div', { class: 'cw-excerpt' }, h('div', { class: 'ex-h' }, 'Text preview'), ...(info.excerpt.length ? info.excerpt.map((p) => h('p', null, p)) : [h('p', { class: 'note' }, 'This chapter has no readable text.')]), h('div', { class: 'ex-fade' }), h('button', { class: 'btn sm', onclick: () => read(mine) }, 'Continue in the reader', icon('chevR', 14))),
        h('div', { class: 'ex-h2' }, `Illustrations${info.imgs.length ? ` (${info.imgs.length})` : ''}`),
        info.imgs.length ? h('div', { class: 'cw-grid' }, info.imgs.map((u, k) => illus(u, k, info.imgs, 'lg'))) : h('p', { class: 'note' }, 'No illustrations in this chapter.'))
      drawTable()
    } catch (e) { badge.textContent = ''; body.replaceChildren(h('div', { class: 'empty small' }, h('h3', null, 'Could not load this chapter'), h('p', null, e.message))) }
  }
  const illus = (u, k, all, size) => h('button', { class: 'pgthumb ' + size, title: 'View illustration', onclick: () => lightbox(all, k) }, h('img', { src: u, alt: '', loading: 'lazy', draggable: false, onerror: (e) => e.target.closest('button')?.classList.add('broken') }), h('b', null, k + 1))

  // ---- inspector ----
  const inspector = h('aside', { class: 'cw-insp' })
  async function drawInspector() {
    const c = chapters()[inspected]
    const pr = progress(), tot = Math.floor(Math.max(total, session.latest?.() || 0, chapters().length)), pct = tot ? Math.min(100, (pr / tot) * 100) : 0
    const pctTxt = pct >= 100 ? '100' : pct < 10 ? pct.toFixed(1) : String(Math.floor(pct))
    const ex = h('div', { class: 'cw-mini-ex' }), stats = h('div', { class: 'cw-stats' }), grid = h('div', { class: 'cw-mini' })
    inspector.replaceChildren(
      h('div', { class: 'ih' }, h('div', null, h('span', { class: 'live' }, h('i'), 'Live Chapter Inspector'), h('h2', null, c ? shortName(inspected) : 'No chapter selected')), h('button', { class: 'btn sm', disabled: !c, onclick: () => switchTab('previews') }, 'Full preview')),
      h('div', { class: 'ib' },
        h('div', { class: 'prog' }, h('div', null, h('span', null, 'Series Reading Progress'), h('b', null, `${pr}${tot ? ` / ${tot}` : ''} Ch.${tot ? ` (${pctTxt}%)` : ''}`)), h('div', { class: 'bar' }, h('i', { style: { width: Math.max(pct, pr ? 2 : 0) + '%' } }))),
        h('div', null, h('div', { class: 'mh' }, h('span', null, 'Text Preview'), h('em', null, c ? [c.number != null ? `#${c.number}` : '', when(c.date)].filter(Boolean).join(' • ') : '')), ex),
        stats,
        h('div', null, h('div', { class: 'mh' }, h('span', null, 'Illustrations (click to enlarge)')), grid),
        h('button', { class: 'cw-launch', disabled: !c, onclick: () => read(inspected) }, icon('book', 18), 'Launch Reader')))
    if (!c) { ex.replaceChildren(h('p', { class: 'note' }, chapters().length ? 'Press Preview on a chapter to inspect it.' : 'Chapter previews show here once a source is linked.')); return grid.replaceChildren(h('p', { class: 'note', style: { gridColumn: '1/-1' } }, '—')) }
    const mine = inspected
    ex.replaceChildren(h('div', { class: 'skeleton', style: { height: '120px', borderRadius: '12px' } }))
    try {
      const info = await content(mine); if (mine !== inspected) return
      const first = info.excerpt.join(' ')
      ex.replaceChildren(h('p', null, first.length > 380 ? first.slice(0, 380).replace(/\s+\S*$/, '') + '…' : first || 'This chapter has no readable text.'))
      stats.replaceChildren(h('span', null, icon('book', 14), `${info.words.toLocaleString()} words`), h('span', null, icon('clock', 14), `~${Math.max(1, Math.ceil(info.words / 230))} min`), h('span', null, icon('grid', 14), `${info.imgs.length} illustration${info.imgs.length === 1 ? '' : 's'}`))
      grid.replaceChildren(...(info.imgs.length ? info.imgs.slice(0, 5).map((u, k) => illus(u, k, info.imgs, 'sm')) : [h('p', { class: 'note', style: { gridColumn: '1/-1' } }, 'No illustrations in this chapter.')]))
      if (info.imgs.length > 5) grid.append(h('button', { class: 'pgthumb sm more', onclick: () => switchTab('previews') }, `+${info.imgs.length - 5}`))
      drawTable()
    } catch (e) { ex.replaceChildren(h('p', { class: 'note' }, e.message)) }
  }
  function inspect(i) {
    if (i < 0 || i >= chapters().length) return toast(i < 0 ? 'This is the first chapter' : 'No more chapters')
    inspected = i; inspKey = { url: chapters()[i].url, number: chapters()[i].number }
    drawToolbar(); drawTable(); drawInspector()
    if (tab === 'previews') drawPreviews()
  }

  // ---- layout ----
  const panes = { chapters: h('div', { class: 'cw-pane' }, stateBox, tableWrap), previews: h('div', { class: 'cw-pane' }, previews) }
  function show() { for (const [k, el] of Object.entries(panes)) el.style.display = k === tab ? '' : 'none'; if (tab === 'previews') drawPreviews() }
  const workspace = h('section', { class: 'cw-work' }, toolbar, ...Object.values(panes))
  function afterChapters() {
    if (inspKey && chapters().length) {
      const j = chapters().findIndex((c) => c.url === inspKey.url)
      inspected = j >= 0 ? j : inspKey.number != null ? chapters().findIndex((c) => c.number === inspKey.number) : -1
    }
    if (inspected < 0 && chapters().length) inspected = nextIdx()
    drawHero(); drawToolbar(); drawTable(); drawInspector()
    if (tab !== 'chapters') show()
  }
  session.on(() => {
    if (autoplay && session.status === 'ready' && session.chapters.length) { autoplay = false; session.open(session.next(progress()), onProgress) } // Continue from the floating button
    if (session.status !== 'ready') loadEpub(); afterChapters()
  })
  drawHero(); drawToolbar(); drawTable(); drawInspector(); show()
  session.init()

  return h('div', { class: 'cw-page' }, topbar, hero, h('main', { class: 'cw-main' }, workspace, inspector))
}
