// Manga chapter workspace (desktop version of the Android chapter screen). Opened from the details page's Chapters entry.
// Tabs: Chapters (table) / Page Previews (thumbnail grid) / Bookmarks, plus a sticky live page inspector on the right.
import { h, icon, api, go, menu, toast, onLeave, titleOf, coverOf, bannerOf, totalOf, pretty, stripHtml, pageUrl, STATUS, STATUS_ICON } from '../core.js'
import { openTrackingSheet, openChaptersSheet } from '../overlays.js'
import { createSession, sourceChip, openSourcePicker } from '../source-session.js'
import { chapterOrder, setChapterOrder } from '../prefs.js'
import { when, sourceState, mins } from './shared.js'

const BM_KEY = 'ds.bookmarks'
const readBookmarks = () => { try { return JSON.parse(localStorage.getItem(BM_KEY) || '[]') } catch { return [] } }
const chLabel = (c) => c.name || `Chapter ${c.number ?? ''}`.trim()

export function chaptersPage({ media, mode, entry, local, autoplay = false }) {
  const title = titleOf(media)
  const total = totalOf(media)
  const session = createSession({ media, mode })
  let tab = 'chapters', order = chapterOrder(), filter = '', hideRead = false, inspected = -1, bookmarks = readBookmarks().filter((b) => b.title === title)
  const pagesCache = new Map() // chapter url -> page urls (urls, not indexes, so it survives group changes)
  let inspKey = null // the inspected chapter, remembered by url/number because indexes move when groups are toggled
  const progress = () => entry?.progress || 0
  const onProgress = (r) => { entry = { ...(entry || {}), ...r }; drawHero(); drawTable(); drawInspector() }

  // ---- pages for one chapter, loaded once and cached ----
  async function loadPages(i) {
    const c = session.chapters[i]
    if (!c || !session.source) return []
    if (pagesCache.has(c.url)) return pagesCache.get(c.url)
    const refs = await api('ext:pages', { sourceId: session.source.id, chapter: c })
    const urls = refs.map(pageUrl)
    pagesCache.set(c.url, urls)
    return urls
  }
  const read = (i, page) => session.status === 'ready' ? session.open(i, onProgress, page) : local ? openChaptersSheet({ media, mode, entry, local, onProgress }) : openSourcePicker(session, media)

  // ---- header strip ----
  const search = h('input', { class: 'input', placeholder: 'Search series, chapters, or jump to #…', oninput: (e) => setFilter(e.target.value) })
  const setFilter = (v) => { filter = v.toLowerCase().trim(); if (search.value !== v) search.value = v; if (filter.startsWith('#')) filter = filter.slice(1); drawTable() }
  const topbar = h('div', { class: 'cw-top' },
    h('button', { class: 'ibtn round', onclick: () => history.back(), 'aria-label': 'Back' }, icon('back', 18)),
    h('nav', { class: 'crumbs' }, h('a', { onclick: () => go('/library') }, 'Library'), h('i', null, '/'), h('a', { onclick: () => go('/discover/manga') }, 'Manga'), h('i', null, '/'), h('b', null, title)),
    h('div', { class: 'cw-search' }, icon('search', 16), search, h('kbd', null, 'Ctrl+K')),
    h('div', { class: 'cw-top-r' },
      h('button', { class: 'ibtn round', title: 'Copy link', onclick: () => { if (media.siteUrl) { navigator.clipboard?.writeText(media.siteUrl); toast('Link copied') } } }, icon('share', 16)),
      h('button', { class: 'ibtn round', title: 'More', onclick: (e) => menu(e.currentTarget, [
        { icon: 'play', label: `Resume reading${progress() ? ` (Ch. ${progress() + 1})` : ''}`, run: () => start() },
        { icon: 'check', label: total ? `Mark all ${total} chapters read` : 'Mark all read', run: markAll },
        { icon: 'link', label: 'Change source…', run: () => openSourcePicker(session, media) },
        { icon: 'sync', label: 'Re-check the source', run: () => session.resolve(session.source || undefined) },
        session.customGroups() && { icon: 'sync', label: 'Reset group choices to the preset', run: () => session.resetGroups() },
        media.siteUrl && { icon: 'external', label: 'Open on AniList', run: () => window.open(media.siteUrl) },
      ].filter(Boolean)) }, icon('dots', 16))))
  const key = (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); search.focus(); search.select() } }
  window.addEventListener('keydown', key)
  onLeave(() => window.removeEventListener('keydown', key))

  async function markAll() {
    if (!total) return toast('Total chapter count unknown', true)
    try { onProgress(await api('anilist:save', { mediaId: media.id, mode, status: 'COMPLETED', progress: total })) } catch (e) { toast(e.message, true) }
  }
  function start() {
    if (session.status === 'ready') return session.open(session.next(progress()), onProgress)
    if (session.status === 'resolving' || session.status === 'loading') return toast('Still finding your source…')
    read(0)
  }

  // ---- hero ----
  const hero = h('section', { class: 'cw-hero' })
  const srcChip = sourceChip(session, media)
  const desc = stripHtml(media.description) || 'No description.'
  function drawHero() {
    const n = session.chapters.length || total
    const nextI = session.chapters.length ? session.next(progress()) : 0
    const nextC = session.chapters[nextI]
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
            h('div', { class: 'metrics' }, icon('book', 16), h('span', null, `${n || '?'} chapters`, n ? h('em', null, ` (${mins(n * 5)})`) : null), h('i'),
              h('button', { class: hideRead ? 'on' : '', title: hideRead ? 'Show read chapters' : 'Hide read chapters', onclick: () => ((hideRead = !hideRead), drawHero(), drawTable()) }, icon(hideRead ? 'eyeoff' : 'eye', 16))))),
        h('button', { class: 'cw-start', onclick: start }, icon('play', 16), progress() && nextC?.number != null ? `Continue Ch. ${nextC.number}` : 'Start Reading Ch. 1')))
  }

  // ---- toolbar: tabs, group pills, sort ----
  const tabsEl = h('div', { class: 'cw-tabs' })
  const pillsEl = h('div', { class: 'cw-pills' })
  const sortBtn = h('button', { class: 'cw-sort', onclick: () => { order = order === 'desc' ? 'asc' : 'desc'; setChapterOrder(order); drawToolbar(); drawTable(); drawPreviews() } })
  const filterIn = h('input', { class: 'input', placeholder: 'Filter chapters…', oninput: (e) => setFilter(e.target.value) })
  const tools = h('div', { class: 'cw-tools' }, pillsEl, h('div', { class: 'cw-filter' }, icon('search', 14), filterIn), sortBtn)
  const toolbar = h('div', { class: 'cw-toolbar' }, tabsEl, tools)
  function drawToolbar() {
    const t = (id, ico, label, extra) => h('button', { class: 'cw-tab' + (tab === id ? ' on' : ''), onclick: () => switchTab(id) }, icon(ico, 16), label, extra)
    tabsEl.replaceChildren(
      t('chapters', 'list', `Chapters (${session.chapters.length || total || 0})`),
      t('previews', 'grid', `Page Previews${inspected >= 0 ? ` (${shortName(session.chapters[inspected])})` : ''}`),
      t('bookmarks', 'bookmark', 'Bookmarks', bookmarks.length ? h('span', { class: 'badge' }, bookmarks.length) : null))
    const g = session.groups()
    pillsEl.replaceChildren(...(g.size > 1 && tab === 'chapters' ? [
      ...[...g.entries()].slice(0, 6).map(([name, n]) => h('button', { class: 'gp' + (session.isOff(name) ? '' : ' on'), title: 'Show or hide this group', onclick: () => { if (!session.toggleGroup(name)) toast('Keep at least one group on', true) } }, !session.isOff(name) && icon('check', 14), name, h('em', null, n))),
    ].filter(Boolean) : []))
    tools.style.display = tab === 'chapters' ? '' : 'none'
    sortBtn.replaceChildren(icon(order === 'desc' ? 'sortDown' : 'sortUp', 16), order === 'desc' ? 'Desc' : 'Asc')
  }
  const shortName = (c) => (c ? (c.number != null ? `Ch. ${c.number}` : chLabel(c)) + (c.name && c.number != null && !/^chapter\s*\d+$/i.test(c.name) ? ` - ${c.name.replace(/^(ch(apter|\.)?\s*\d+(\.\d+)?\s*[-:–]?\s*)/i, '')}` : '') : '')
  function switchTab(id) { tab = id; if (id === 'previews' && inspected < 0 && session.chapters.length) inspect(session.next(progress())); drawToolbar(); show() }

  // ---- table ----
  const tbody = h('tbody')
  const tableWrap = h('div', { class: 'cw-table' }, h('table', null,
    h('thead', null, h('tr', null, ['Status', 'Chapter & Title', 'Group', 'Release Date', 'Pages', 'Actions'].map((x, i) => h('th', { class: i === 5 ? 'r' : i === 4 ? 'c' : '' }, x)))), tbody))
  const stateBox = h('div', { class: 'cw-state' })
  const rows = () => {
    const prog = progress()
    let r = session.chapters.map((c, i) => ({ c, i })).filter(({ c }) => (!filter || `${chLabel(c)} ${c.number ?? ''}`.toLowerCase().includes(filter)) && !(hideRead && c.number != null && c.number <= prog))
    return order === 'desc' ? r.reverse() : r
  }
  function drawTable() {
    const st = sourceState(session, { onRetry: () => session.resolve(session.source), goExt: () => go('/extensions'), openPicker: () => openSourcePicker(session, media) })
    stateBox.style.display = st ? '' : 'none'
    stateBox.replaceChildren(...(st ? [st, local ? h('div', { style: { textAlign: 'center', marginTop: '8px' } }, h('button', { class: 'btn', onclick: () => openChaptersSheet({ media, mode, entry, local, onProgress }) }, icon('folder', 16), `Open ${local.files.length} local files`)) : ''] : []))
    tableWrap.style.display = st ? 'none' : ''
    const prog = progress()
    const list = rows()
    tbody.replaceChildren(...(list.length || st ? list.map(({ c, i }) => {
      const done = c.number != null && c.number <= prog
      const pages = pagesCache.get(c.url)
      return h('tr', { class: (done ? 'done ' : '') + (i === inspected ? 'sel' : ''), onclick: () => read(i) },
        h('td', { class: 'st' }, h('span', { class: 'dot' + (done ? ' read' : '') })),
        h('td', { class: 'nm' }, h('b', null, shortName(c) || `#${i + 1}`), i === inspected ? h('span', { class: 'tag' }, 'Inspecting') : null),
        h('td', null, h('span', { class: 'grp' }, c.scanlator || 'official')),
        h('td', { class: 'dt' }, when(c.date) || '—'),
        h('td', { class: 'c dt' }, pages ? `${pages.length} pgs` : '—'),
        h('td', { class: 'r' }, h('div', { class: 'acts' },
          h('button', { class: 'btn sm', onclick: (e) => { e.stopPropagation(); inspect(i) } }, 'Preview'),
          c.number != null && h('button', { class: 'ibtn flat', title: 'Mark as read up to here', onclick: async (e) => { e.stopPropagation(); try { onProgress(await api('anilist:save', { mediaId: media.id, mode, status: total && c.number >= total ? 'COMPLETED' : 'CURRENT', progress: Math.floor(c.number) })) } catch (er) { toast(er.message, true) } } }, icon(done ? 'check' : 'download', 16)))))
    }) : [h('tr', null, h('td', { colSpan: 6, class: 'none' }, 'No chapters match your filters.'))]))
  }

  // ---- previews tab ----
  const previews = h('div', { class: 'cw-previews' })
  async function drawPreviews() {
    if (inspected < 0) return previews.replaceChildren(h('div', { class: 'empty small' }, h('h3', null, 'Pick a chapter'), h('p', null, 'Press Preview on any chapter to see its pages here.')))
    const select = h('select', { class: 'input cw-sel', onchange: (e) => inspect(+e.target.value) }, rows().length || session.chapters.length ? (order === 'desc' ? [...session.chapters.keys()].reverse() : [...session.chapters.keys()]).map((i) => h('option', { value: i, selected: i === inspected }, shortName(session.chapters[i]))) : [])
    const badge = h('span', { class: 'note' }, 'Loading pages…')
    const grid = h('div', { class: 'cw-grid' })
    previews.replaceChildren(h('div', { class: 'cw-prev-head' }, h('div', { class: 'l' }, select, badge),
      h('div', { class: 'r' }, h('button', { class: 'btn sm', onclick: () => inspect(inspected - 1) }, '← Previous Chapter'), h('button', { class: 'btn sm', onclick: () => inspect(inspected + 1) }, 'Next Chapter →'), h('button', { class: 'btn sm light', onclick: () => read(inspected) }, icon('fullscreen', 14), 'Expand Fullscreen'))), grid)
    grid.append(...Array.from({ length: 10 }, () => h('div', { class: 'skeleton pg' })))
    try {
      const urls = await loadPages(inspected)
      badge.textContent = `${urls.length} pages${session.chapters[inspected]?.scanlator ? ` • ${session.chapters[inspected].scanlator}` : ''}`
      grid.replaceChildren(...urls.map((u, k) => thumb(u, k, 'lg')))
    } catch (e) { badge.textContent = ''; grid.replaceChildren(h('div', { class: 'empty small' }, h('h3', null, 'Could not load pages'), h('p', null, e.message))) }
  }
  const thumb = (u, k, size) => h('button', { class: 'pgthumb ' + size, title: `Open at page ${k + 1}`, onclick: () => read(inspected, k) }, h('img', { src: u, alt: '', loading: 'lazy', draggable: false }), h('b', null, k + 1))

  // ---- bookmarks tab ----
  const bmBox = h('div', { class: 'cw-bm' })
  function drawBookmarks() {
    bookmarks = readBookmarks().filter((b) => b.title === title)
    drawToolbar()
    if (!bookmarks.length) return bmBox.replaceChildren(h('div', { class: 'cw-bm-empty' }, h('div', { class: 'ico-box' }, icon('bookmark', 30)), h('h3', null, 'No bookmarks yet'), h('p', null, 'You can create bookmark while reading manga'), h('button', { class: 'btn', onclick: start }, 'Open Reader to Add a Bookmark')))
    bmBox.replaceChildren(h('div', { class: 'cw-bm-grid' }, bookmarks.slice().reverse().map((b) => {
      const i = session.chapters.findIndex((c) => (b.number != null && c.number === b.number) || chLabel(c) === b.chapter)
      return h('div', { class: 'bm-card', onclick: () => (i >= 0 ? read(i, b.page - 1) : toast('Chapter not in this source', true)) },
        h('div', { class: 'bm-ico' }, icon('bookmark', 20)), h('b', null, b.chapter), h('span', null, `Page ${b.page}`), h('em', null, new Date(b.at).toLocaleDateString()),
        h('button', { class: 'ibtn flat', title: 'Remove', onclick: (e) => { e.stopPropagation(); try { localStorage.setItem(BM_KEY, JSON.stringify(readBookmarks().filter((x) => !(x.title === b.title && x.at === b.at)))) } catch {} drawBookmarks() } }, icon('trash', 16)))
    })))
  }

  // ---- inspector ----
  const inspector = h('aside', { class: 'cw-insp' })
  async function drawInspector() {
    const c = session.chapters[inspected]
    // ongoing series have no AniList total, so measure against the newest chapter number, not the row count (rows can include duplicates)
    const pr = progress(), tot = Math.floor(Math.max(total, session.latest()))
    const pct = tot ? Math.min(100, (pr / tot) * 100) : 0
    const pctTxt = pct >= 100 ? '100' : pct < 10 ? pct.toFixed(1) : String(Math.floor(pct))
    const grid = h('div', { class: 'cw-mini' })
    const quick = h('div', { class: 'cw-quick' })
    inspector.replaceChildren(
      h('div', { class: 'ih' }, h('div', null, h('span', { class: 'live' }, h('i'), 'Live Page Inspector'), h('h2', null, c ? shortName(c) : 'No chapter selected')),
        h('button', { class: 'btn sm', disabled: !c, onclick: () => switchTab('previews') }, 'All pages')),
      h('div', { class: 'ib' },
        h('div', { class: 'prog' }, h('div', null, h('span', null, 'Series Reading Progress'), h('b', null, `${pr}${tot ? ` / ${tot}` : ''} Ch.${tot ? ` (${pctTxt}%)` : ''}`)), h('div', { class: 'bar' }, h('i', { style: { width: Math.max(pct, pr ? 2 : 0) + '%' } }))),
        h('div', null, h('div', { class: 'mh' }, h('span', null, 'Page Thumbnails (Click to inspect)'), h('em', null, c ? [c.number != null ? `#${c.number}` : '', when(c.date)].filter(Boolean).join(' • ') : '')), grid),
        quick,
        h('button', { class: 'cw-launch', disabled: !c, onclick: () => read(inspected) }, icon('book', 18), 'Launch Fullscreen Reader')))
    if (!c) return grid.replaceChildren(h('p', { class: 'note', style: { gridColumn: '1/-1' } }, session.status === 'ready' ? 'Press Preview on a chapter to inspect its pages.' : 'Chapter pages show here once a source is linked.'))
    const mine = inspected
    grid.append(...Array.from({ length: 6 }, () => h('div', { class: 'skeleton pg' })))
    try {
      const urls = await loadPages(mine)
      if (mine !== inspected) return
      grid.replaceChildren(...urls.slice(0, 9).map((u, k) => thumb(u, k, 'sm')))
      if (urls.length > 9) grid.append(h('button', { class: 'pgthumb sm more', onclick: () => switchTab('previews') }, `+${urls.length - 9}`))
      const jumps = [1, 3, 5, urls.length].filter((p, k, a) => p >= 1 && p <= urls.length && a.indexOf(p) === k)
      quick.replaceChildren(h('span', null, 'Quick Jump:'), ...jumps.map((p) => h('button', { onclick: () => read(mine, p - 1) }, `P.${p}`)))
      drawTable()
    } catch (e) { grid.replaceChildren(h('p', { class: 'note', style: { gridColumn: '1/-1' } }, e.message)) }
  }
  function inspect(i) {
    if (i < 0 || i >= session.chapters.length) return toast(i < 0 ? 'This is the first chapter' : 'No more chapters')
    inspected = i; inspKey = { url: session.chapters[i].url, number: session.chapters[i].number }
    drawToolbar(); drawTable(); drawInspector()
    if (tab === 'previews') drawPreviews()
  }

  // ---- layout ----
  const panes = { chapters: h('div', { class: 'cw-pane' }, stateBox, tableWrap), previews: h('div', { class: 'cw-pane' }, previews), bookmarks: h('div', { class: 'cw-pane' }, bmBox) }
  function show() {
    for (const [k, el] of Object.entries(panes)) el.style.display = k === tab ? '' : 'none'
    if (tab === 'previews') drawPreviews()
    if (tab === 'bookmarks') drawBookmarks()
  }
  const workspace = h('section', { class: 'cw-work' }, toolbar, ...Object.values(panes))

  session.on(() => {
    if (autoplay && session.status === 'ready' && session.chapters.length) { autoplay = false; session.open(session.next(progress()), onProgress) } // Continue from the floating button
    if (inspKey && session.chapters.length) {
      const j = session.chapters.findIndex((c) => c.url === inspKey.url)
      inspected = j >= 0 ? j : inspKey.number != null ? session.chapters.findIndex((c) => c.number === inspKey.number) : -1
    }
    if (session.status === 'ready' && inspected < 0) inspected = session.next(progress())
    drawHero(); drawToolbar(); drawTable(); drawInspector()
    if (tab !== 'chapters') show()
  })
  drawHero(); drawToolbar(); drawTable(); drawInspector(); show()
  session.init()

  return h('div', { class: 'cw-page' }, topbar, hero, h('main', { class: 'cw-main' }, workspace, inspector))
}
