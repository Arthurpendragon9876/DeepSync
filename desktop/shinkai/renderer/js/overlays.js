import { extPanel } from './ext-ui.js'
import { pickSubtitle, langOf } from './subs.js'
import { actionFor, tokenOf } from './keys.js'
import { skipDuplicates } from './prefs.js'
import { mangaQuickSettings, novelQuickSettings, listenBar, mangaPrefs, saveMangaPrefs, novelPrefs, saveNovelPrefs, MANGA_DEFAULTS, hasTitlePrefs, NOVEL_DEFAULTS, NOVEL_THEMES, NOVEL_FONTS } from './reader-settings.js'
import { h, icon, S, api, data, go, overlay, sheet, menu, toast, titleOf, coverOf, modeOf, unit, totalOf, STATUS, STATUS_ICON, pageUrl, mediaUrl, norm, segmented, clear } from './core.js'

const store = (k, d) => { try { return { ...d, ...JSON.parse(localStorage.getItem(k) || '{}') } } catch { return d } }
const persist = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)) } catch {} }
const ICONS = { CURRENT: 'play', PLANNING: 'clock', COMPLETED: 'check', PAUSED: 'pause', DROPPED: 'x' }

// ---- tracking sheet (status, progress editor, score) ----------------------------------------------------------------------
export function openTrackingSheet({ media, mode, entry, done }) {
  const total = totalOf(media)
  const state = { status: entry?.status || 'CURRENT', progress: entry?.progress ?? 0, score: entry?.score || 0 }
  const body = h('div')
  let close
  if (!S.user) {
    body.append(h('div', { class: 'empty', style: { padding: '24px 0' } }, h('h3', null, 'Sign in to track'), h('p', null, 'Connect AniList to sync status, progress and score.'),
      h('button', { class: 'btn primary', onclick: () => (close(), go('/settings/trackers')) }, 'Open tracker settings')))
    close = sheet({ title: 'Tracking', body, side: 'center' })
    return
  }
  const num = h('input', { class: 'input', type: 'number', min: 0, max: total || 99999, value: state.progress, oninput: () => (state.progress = Math.max(0, parseInt(num.value || '0', 10))) })
  const setProg = (v) => { state.progress = Math.max(0, total ? Math.min(total, v) : v); num.value = state.progress }
  const opts = h('div', { class: 'opt-list' })
  const drawOpts = () => opts.replaceChildren(...Object.keys(STATUS).filter((k) => k !== 'REPEATING').map((k) =>
    h('div', { class: 'opt' + (state.status === k ? ' on' : ''), onclick: () => { state.status = k; if (k === 'COMPLETED' && total) setProg(total); drawOpts() } }, icon(ICONS[k], 18), STATUS[k](mode))))
  drawOpts()
  const score = h('input', { class: 'input', type: 'number', min: 0, max: 10, step: 0.5, value: state.score || '', placeholder: '–', oninput: () => (state.score = parseFloat(score.value) || 0) })
  body.append(
    h('div', { class: 'h3', style: { marginTop: 0 } }, 'Status'), opts,
    h('div', { class: 'h3' }, mode === 'anime' ? 'Episodes watched' : 'Chapters read'),
    h('div', { class: 'stepper' }, h('button', { class: 'ibtn', onclick: () => setProg(state.progress - 1), 'aria-label': 'Minus one' }, icon('minus')), num,
      h('button', { class: 'ibtn', onclick: () => setProg(state.progress + 1), 'aria-label': 'Plus one' }, icon('plus')), total ? h('span', { class: 'note' }, `of ${total}`) : null),
    h('div', { class: 'h3' }, 'Score (0–10)'), h('div', { style: { width: '120px' } }, score),
    h('div', { style: { display: 'flex', gap: '10px', marginTop: '26px' } },
      h('button', { class: 'btn primary', style: { flex: 1 }, onclick: async () => {
        try {
          const saved = await api('anilist:save', { mediaId: media.id, mode, status: state.status, progress: state.progress, score: state.score || undefined })
          toast('Saved to AniList'); done?.({ ...(entry || {}), ...saved, mediaId: media.id }); close()
        } catch (e) { toast(e.message, true) }
      } }, 'Save'),
      entry && h('button', { class: 'btn danger', onclick: async () => {
        try { await api('anilist:remove', { entryId: entry.id, mediaId: media.id, mode }); toast('Removed from list'); done?.(null); close() } catch (e) { toast(e.message, true) }
      } }, icon('trash', 18), 'Remove')))
  close = sheet({ title: titleOf(media), body, side: 'center' })
}

// ---- episodes sheet -------------------------------------------------------------------------------------------------------
export function openEpisodesSheet({ media, entry, local, onProgress }) {
  const total = totalOf(media) || media.streamingEpisodes?.length || (entry?.progress || 0) + 12
  const body = h('div')
  const byEp = new Map((local?.files || []).map((f) => [f.episode, f]))
  let progress = entry?.progress || 0
  const draw = () => {
    body.replaceChildren()
    for (let n = 1; n <= total; n++) {
      const se = media.streamingEpisodes?.find((e) => new RegExp(`episode\\s*${n}\\b`, 'i').test(e.title))
      const file = byEp.get(n)
      const title = se?.title?.replace(/^.*?episode\s*\d+\s*[-–:]?\s*/i, '').trim()
      body.append(h('div', { class: 'ep-row' + (n <= progress ? ' done' : ''), onclick: () => file ? playFiles(local, file, media, entry, onProgress) : toast(`No local file for episode ${n}. Add the folder in Settings > Library.`) },
        h('div', { class: 'th' }, se?.thumbnail ? h('img', { src: se.thumbnail, alt: '', loading: 'lazy' }) : n),
        h('div', { class: 'tx' }, h('b', null, `Episode ${n}`), h('span', null, title || (file ? file.name : 'No local file'))),
        h('div', { class: 'st' }, file && h('span', { class: 'chip acc' }, icon('play', 12), 'Local'),
          h('button', { class: 'ibtn', style: { background: n <= progress ? 'rgb(var(--accent) / .2)' : 'var(--surface-3)' }, title: n <= progress ? 'Watched' : 'Mark watched up to here', onclick: async (e) => {
            e.stopPropagation()
            try { const r = await api('anilist:save', { mediaId: media.id, mode: 'anime', status: total && n >= total ? 'COMPLETED' : 'CURRENT', progress: n }); progress = n; onProgress?.(r); draw() } catch (er) { toast(er.message, true) }
          } }, icon('check', 18))))) }
  }
  draw()
  const wrap = h('div')
  const tabs = h('div', { class: 'tabs' })
  const panel = h('div')
  let closeSheet
  const showTab = (t) => {
    tabs.replaceChildren(...[['eps', 'Episodes'], ['ext', 'Extensions']].map(([k, l]) => h('div', { class: 'tab' + (k === t ? ' on' : ''), onclick: () => showTab(k) }, l)))
    panel.replaceChildren(t === 'eps' ? body : extPanel({ media, mode: 'anime', progress, onProgress: (r) => { progress = r.progress; onProgress?.(r) }, close: () => closeSheet?.() }))
  }
  showTab('eps')
  wrap.append(tabs, panel)
  closeSheet = sheet({ title: `${total} episodes`, body: wrap })
}

function playFiles(local, file, media, entry, onProgress) {
  const files = local.files.filter((f) => f.episode != null)
  openPlayer({ title: titleOf(media), files, index: files.indexOf(file), onWatched: async (f) => {
    const r = await api('track:progress', { mode: 'anime', mediaId: media.id, progress: f.episode, total: totalOf(media) }).catch(() => null)
    if (r?.saved) onProgress?.(r.saved)
  } })
}

// ---- chapters sheet (local files + MangaDex) --------------------------------------------------------------------------------
export function openChaptersSheet({ media, mode, entry, local, onProgress }) {
  const body = h('div')
  const total = totalOf(media)
  let progress = entry?.progress || 0
  const tabs = h('div', { class: 'tabs' })
  const list = h('div')
  const sources = []
  if (local?.files?.length) sources.push('local')
  if (mode === 'manga' && S.settings.sources?.mangadex) sources.push('mangadex')
  if (mode === 'manga') sources.push('ext')
  let active = sources[0]
  let closeSheet

  const openReader = (chs, i) => openMangaReader({
    title: titleOf(media), chapters: chs, index: i,
    onRead: async (c) => {
      if (!c.number) return
      const r = await api('track:progress', { mode, mediaId: media.id, progress: Math.floor(c.number), total }).catch(() => null)
      if (r?.saved) { progress = r.saved.progress; onProgress?.(r.saved) }
    },
  })
  const rows = (chs) => list.replaceChildren(...chs.map((c, i) => h('div', { class: 'ep-row' + (c.number && c.number <= progress ? ' done' : ''), onclick: () => openReader(chs, i) },
    h('div', { class: 'th' }, c.number ?? '#'), h('div', { class: 'tx' }, h('b', null, c.label), h('span', null, c.sub || '')), h('div', { class: 'st' }, icon('chevR', 18)))))

  async function show(src) {
    active = src
    drawTabs()
    list.replaceChildren(h('div', { class: 'skeleton', style: { height: '120px' } }))
    try {
      if (src === 'ext') list.replaceChildren(extPanel({ media, mode, progress, onProgress: (r) => { progress = r.progress; onProgress?.(r) }, close: () => closeSheet?.() }))
      else if (src === 'local') rows(local.files.map((f) => ({ number: f.chapter, label: `Chapter ${f.chapter}`, sub: f.name, load: () => api('library:pages', { file: f }) })))
      else await showMangaDex()
    } catch (e) { list.replaceChildren(h('div', { class: 'empty' }, h('h3', null, 'Could not load'), h('p', null, e.message))) }
  }
  async function showMangaDex() {
    const lib = await api('library:get')
    const linkKey = 'src:mangadex:' + media.id
    let id = lib.links[linkKey]
    if (!id) {
      const q = titleOf(media)
      const res = await api('sources:search', { source: 'mangadex', query: q })
      if (!res.length) return list.replaceChildren(h('div', { class: 'empty' }, h('h3', null, 'No results on MangaDex'), h('p', null, `Nothing found for “${q}”.`)))
      return list.replaceChildren(h('p', { class: 'note', style: { margin: '0 0 12px' } }, 'Pick the matching series:'), ...res.map((r) => h('div', { class: 'ep-row', onclick: async () => { await api('library:link', { localKey: linkKey, mediaId: r.id }); show('mangadex') } },
        h('div', { class: 'th', style: { width: '56px', aspectRatio: '2/3' } }, r.cover && h('img', { src: r.cover, alt: '' })), h('div', { class: 'tx' }, h('b', null, r.title), h('span', null, [r.status, r.year].filter(Boolean).join(' · '))))))
    }
    const chs = await api('sources:chapters', { source: 'mangadex', id })
    if (!chs.length) return list.replaceChildren(h('div', { class: 'empty' }, h('h3', null, 'No English chapters found')))
    rows(chs.map((c) => ({ number: c.chapter, label: `Chapter ${c.chapter}`, sub: c.title, load: () => api('sources:pages', { source: 'mangadex', id: c.id }) })))
    list.prepend(h('div', { style: { textAlign: 'right', marginBottom: '8px' } }, h('button', { class: 'btn sm', onclick: async () => { await api('library:link', { localKey: linkKey, mediaId: null }); show('mangadex') } }, 'Change series')))
  }
  const drawTabs = () => tabs.replaceChildren(...sources.map((s) => h('div', { class: 'tab' + (s === active ? ' on' : ''), onclick: () => show(s) }, s === 'local' ? 'Local files' : s === 'ext' ? 'Extensions' : 'MangaDex')))
  if (!sources.length) body.append(h('div', { class: 'empty' }, h('h3', null, 'No chapter source'), h('p', null, 'Add a library folder in Settings > Library, or enable an online source under Extensions.')))
  else { body.append(tabs, list); show(active) }
  closeSheet = sheet({ title: total ? `${total} chapters` : 'Chapters', body })
}

// ---- manga / webtoon reader -------------------------------------------------------------------------------------------------
// Modes: ltr (Standard), rtl, vertical (paged, wheel flips), webtoon. Settings are remembered per title (see reader-settings.js).
export function openMangaReader({ title, chapters, index = 0, page: startPage = 0, onRead }) {
  const prefs = mangaPrefs(title)
  let pages = [], page = 0, cur = index, reported = false, io, auto = null, qs = null, wheelAt = 0
  const root = h('div', { class: 'reader', tabindex: -1 })
  const sub = h('span')
  const counter = h('span')
  const range = h('input', { type: 'range', min: 0, max: 0, value: 0, 'aria-label': 'Page' })
  const body = h('div', { class: 'pages', tabindex: 0 })
  const web = () => prefs.mode === 'webtoon'
  // Like the phone app: on the first open of a title, sample pages at 25 / 50 / 75 % and switch to Webtoon when most are much taller than wide.
  const autoDetect = !hasTitlePrefs(title) && prefs.autoMode !== false && prefs.mode !== 'webtoon'
  let detected = false
  const tallVote = (u) => new Promise((res) => { const im = new Image(), t = setTimeout(() => res(null), 6000); im.onload = () => (clearTimeout(t), res(im.naturalWidth * 1.8 < im.naturalHeight)); im.onerror = () => (clearTimeout(t), res(null)); im.src = u })
  const detectWebtoon = async (urls) => {
    const n = urls.length
    const idx = n < 4 ? (n ? [Math.floor(n / 2)] : []) : [...new Set([0.25, 0.5, 0.75].map((f) => Math.round(n * f)))]
    const votes = (await Promise.all(idx.map((i) => (urls[i] ? tallVote(urls[i]) : null)))).filter((v) => v != null)
    return votes.length ? votes.filter(Boolean).length * 2 > votes.length : null
  }

  const bar = h('div', { class: 'bar' },
    h('button', { class: 'ibtn', onclick: () => close(), 'aria-label': 'Close reader' }, icon('x')),
    h('div', { class: 'ttl' }, h('b', null, title), sub),
    h('button', { class: 'ibtn', title: 'Previous chapter', onclick: () => load(cur - 1) }, icon('skipB')),
    h('button', { class: 'ibtn', title: 'Next chapter', onclick: () => load(cur + 1) }, icon('skipF')),
    h('button', { class: 'ibtn', 'data-qs': 1, title: 'Quick settings (S)', onclick: () => toggleQS() }, icon('sliders')),
    h('button', { class: 'ibtn', title: 'Fullscreen (F)', onclick: () => window.deepsync.win.action('fullscreen') }, icon('fullscreen')))
  const foot = h('div', { class: 'foot' }, counter, range)
  root.append(bar, body, foot)

  const applyLook = () => {
    const f = [`brightness(${prefs.bright}%)`, `contrast(${prefs.contrast}%)`, prefs.sepia ? `sepia(${prefs.sepia}%)` : '', prefs.gray ? 'grayscale(1)' : '', prefs.invert ? 'invert(1)' : ''].filter(Boolean).join(' ')
    root.style.setProperty('--rbg', prefs.bg); root.style.setProperty('--rf', f); root.style.setProperty('--gap', prefs.gap + 'px')
    body.style.setProperty('--w', Math.round(prefs.width * (prefs.zoom || 100) / 100) + 'px'); root.dataset.fit = prefs.fit
    foot.classList.toggle('hidden', !prefs.counter)
    body.style.setProperty('--z', (prefs.zoom || 100) / 100)
    body._size?.()
    keepAwake(); eyeTimer()
  }
  // keep the screen on while reading, and an optional break reminder
  let wake = null, eyeT = null
  const keepAwake = async () => { try { if (prefs.awake && !wake && navigator.wakeLock) { wake = await navigator.wakeLock.request('screen'); wake.addEventListener('release', () => (wake = null)) } else if (!prefs.awake && wake) { wake.release(); wake = null } } catch {} }
  const eyeTimer = () => { clearInterval(eyeT); if (prefs.eye) eyeT = setInterval(() => toast('Eye break: look at something far away for 20 seconds'), prefs.eye * 60000) }
  const dbl = () => prefs.double && (prefs.mode === 'ltr' || prefs.mode === 'rtl')
  const pairStart = (p) => (!dbl() ? p : prefs.coverAlone ? (p === 0 ? 0 : p % 2 === 1 ? p : p - 1) : p - (p % 2))
  const pairOf = (s) => (!dbl() || (prefs.coverAlone && s === 0) ? [s] : [s, s + 1 < pages.length ? s + 1 : null].filter((v) => v != null))
  const zoomBy = (d) => { const z = Math.max(50, Math.min(300, (prefs.zoom || 100) + d)); update({ zoom: z }); toast(`Zoom ${z}%`) }
  const update = (patch) => {
    const modeChanged = patch.mode && patch.mode !== prefs.mode
    const redraw = ['double', 'coverAlone', 'dpOrder'].some((k) => k in patch && patch[k] !== prefs[k])
    if ((modeChanged || (patch.fit && patch.fit !== prefs.fit)) && patch.zoom == null) patch = { ...patch, zoom: 100 }
    Object.assign(prefs, patch); saveMangaPrefs(title, prefs); applyLook()
    if (patch.fit || modeChanged) qs?.refresh?.()
    if (modeChanged || redraw) { const was = auto; haltAuto(); draw(page); if (was) startAuto() } else if (auto) { haltAuto(); startAuto() }
  }

  const markPage = (p) => {
    page = Math.max(0, Math.min(pages.length - 1, p))
    counter.textContent = pages.length ? `${page + 1} / ${pages.length}` : ''
    range.value = page
    if (!reported && pages.length && page >= pages.length - 1) { reported = true; onRead?.(chapters[cur]) }
  }
  function draw(start = 0) {
    io?.disconnect(); body._ro?.disconnect()
    clear(body)
    body.className = 'pages ' + (web() ? 'web' : 'single')
    root.querySelectorAll('.hit').forEach((x) => x.remove())
    applyLook()
    if (!pages.length) return
    if (web()) {
      const imgs = pages.map((u, i) => h('img', { class: 'pg', src: u, loading: i < 3 ? 'eager' : 'lazy', draggable: false, 'data-i': i, alt: `Page ${i + 1}` }))
      body.append(...imgs, h('div', { style: { padding: '40px', textAlign: 'center' } }, h('button', { class: 'btn primary', onclick: () => load(cur + 1) }, 'Next chapter', icon('chevR', 18))))
      io = new IntersectionObserver((es) => es.forEach((e) => e.isIntersecting && markPage(+e.target.dataset.i)), { root: body, threshold: 0.55 })
      imgs.forEach((i) => io.observe(i))
      requestAnimationFrame(() => imgs[start]?.scrollIntoView())
    } else {
      const holder = h('div', { class: dbl() ? 'spread' : 'one' })
      body.append(holder)
      // Exact sizes in JS: scale = fit-to-screen / width / height of the whole spread (small scans are enlarged), times zoom
      const place = () => {
        const imgs = [...holder.querySelectorAll('img')]
        if (!imgs.length || imgs.some((im) => !im.naturalWidth)) return
        const vw = body.clientWidth, vh = body.clientHeight, z = (prefs.zoom || 100) / 100
        const W = imgs.reduce((a, im) => a + im.naturalWidth, 0), H = Math.max(...imgs.map((im) => im.naturalHeight))
        const base = prefs.fit === 'width' ? vw / W : prefs.fit === 'height' ? vh / H : Math.min(vw / W, vh / H)
        const sc = base * z
        imgs.forEach((im) => { im.style.width = im.naturalWidth * sc + 'px'; im.style.height = im.naturalHeight * sc + 'px' })
        holder.style.width = W * sc + 'px'; holder.style.height = H * sc + 'px'; holder.style.visibility = 'visible'
        if (W * sc > vw) body.scrollLeft = (body.scrollWidth - vw) / 2
      }
      body._size = place
      body._ro?.disconnect(); body._ro = new ResizeObserver(place); body._ro.observe(body)
      if (prefs.mode === 'vertical') root.append(h('div', { class: 'hit t', onclick: () => step(-1) }), h('div', { class: 'hit b', onclick: () => step(1) }))
      else { const flip = (prefs.mode === 'rtl') !== prefs.tapInvert; root.append(h('div', { class: 'hit l', onclick: () => step(flip ? 1 : -1) }), h('div', { class: 'hit r', onclick: () => step(flip ? -1 : 1) })) }
      const showP = (p) => {
        const s = pairStart(Math.max(0, Math.min(pages.length - 1, p))), idx = pairOf(s)
        holder.replaceChildren(...(idx.length > 1 && (prefs.mode === 'rtl' || prefs.dpOrder !== 'ltr') ? idx.slice().reverse() : idx).map((i) => h('img', { src: pages[i], alt: '', draggable: false })))
        markPage(Math.max(...idx)); page = s
        if (idx.length > 1) counter.textContent = `${s + 1}-${s + 2} / ${pages.length}`
        range.value = s
        holder.classList.remove('fx-fade', 'fx-slide'); if (prefs.anim && prefs.anim !== 'none') { void holder.offsetWidth; holder.classList.add('fx-' + prefs.anim) }
        holder.style.visibility = 'hidden'
        holder.querySelectorAll('img').forEach((im) => (im.complete && im.naturalWidth ? 0 : im.addEventListener('load', place)))
        place(); body.scrollTop = 0
        for (const k of [1, 2, 3]) if (pages[s + k]) new Image().src = pages[s + k]
      }
      body._show = showP
      showP(start)
      if (dbl() && !prefs.dpAsked) askOrder()
    }
    markPage(start)
  }
  // First time double pages are used: explain the page order and let people pick (also in Settings → Readers).
  function askOrder() {
    update({ dpAsked: true })
    let close
    const pick = (v) => { update({ dpOrder: v }); close?.(); toast(v === 'rtl' ? 'Double pages read right to left' : 'Double pages read left to right') }
    const opt = (v, ttl, sub) => h('button', { class: 'btn' + (prefs.dpOrder === v ? ' primary' : ''), style: { display: 'block', width: '100%', textAlign: 'left', padding: '12px 14px', marginTop: '10px', height: 'auto' }, onclick: () => pick(v) }, h('b', null, ttl), h('div', { style: { fontWeight: 400, opacity: 0.8, marginTop: '2px' } }, sub))
    close = sheet({ title: 'How should double pages be ordered?', body: h('div', null,
      h('p', { class: 'note', style: { margin: 0 } }, 'Manga is traditionally read from right to left, so on a double page the right-hand page comes first. Western comics and most manhwa go left to right.'),
      h('p', { class: 'note' }, 'Only the position of the two pages changes. The arrow keys and the tap zones keep working left = back, right = forward. You can change this any time in the reader’s Extra tab or Settings → Readers.'),
      opt('rtl', 'Right to left (recommended for manga)', 'The right page is read first'),
      opt('ltr', 'Left to right', 'The left page is read first')) })
  }
  const step = (d) => {
    if (web()) return body.scrollBy({ top: d * body.clientHeight * 0.9, behavior: 'smooth' })
    let n = page + d
    if (dbl()) { const s = pairStart(page); n = d > 0 ? s + pairOf(s).length : s === 0 ? -1 : prefs.coverAlone ? (s <= 1 ? 0 : s - 2) : s - 2 }
    if (n < 0) return load(cur - 1)
    if (n >= pages.length) return load(cur + 1)
    body._show(n)
  }
  async function load(i, from = 0) {
    if (Math.abs(i - cur) === 1 && skipDuplicates()) { const d = i - cur, n = chapters[cur]?.number; while (n != null && chapters[i] && chapters[i].number === n) i += d }
    if (i < 0 || i >= chapters.length) return toast(i < 0 ? 'This is the first chapter' : 'No more chapters')
    cur = i; reported = false
    sub.textContent = chapters[i].label + ' · loading…'
    try {
      pages = (await chapters[i].load()).map(pageUrl)
      if (autoDetect && !detected && pages.length) {
        detected = true
        sub.textContent = chapters[i].label + ' · checking format…'
        const isWeb = await detectWebtoon(pages)
        if (isWeb != null) { if (isWeb) { prefs.mode = 'webtoon'; toast('Looks like a webtoon, switched to Webtoon mode') } saveMangaPrefs(title, prefs) }
      }
      sub.textContent = chapters[i].label
      range.max = Math.max(0, pages.length - 1)
      draw(Math.min(from, Math.max(0, pages.length - 1)))
      qs?.refresh?.()
    } catch (e) { toast(e.message, true); sub.textContent = chapters[i].label }
  }

  // automatic scroll: smooth scrolling in webtoon mode, timed page flips otherwise
  function haltAuto() { if (!auto) return; cancelAnimationFrame(auto.raf); clearInterval(auto.iv); auto = null }
  function startAuto() {
    haltAuto()
    if (web()) { let last = performance.now(); auto = {}; const tick = (t) => { body.scrollTop += (prefs.autoSpeed * 30 * (t - last)) / 1000; last = t; if (auto) auto.raf = requestAnimationFrame(tick) }; auto.raf = requestAnimationFrame(tick) }
    else auto = { iv: setInterval(() => step(1), Math.max(1200, (12 - prefs.autoSpeed) * 1100)) }
  }
  const toggleAuto = () => { if (auto) (haltAuto(), toast('Automatic scroll off')); else (startAuto(), toast('Automatic scroll on')) }

  async function savePage() {
    const src = pages[page]
    if (!src) return
    try {
      const blob = await (await fetch(src)).blob()
      const ext = (blob.type.split('/')[1] || 'jpg').replace('jpeg', 'jpg')
      const a = h('a', { href: URL.createObjectURL(blob), download: `${title} - ${chapters[cur].label} - p${page + 1}.${ext}`.replace(/[\\/:*?"<>|]/g, '_') })
      document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000)
      toast('Page saved to your downloads')
    } catch { toast('Could not save this page', true) }
  }
  const bookmark = () => {
    try {
      const all = JSON.parse(localStorage.getItem('ds.bookmarks') || '[]')
      all.push({ title, chapter: chapters[cur].label, number: chapters[cur].number ?? null, page: page + 1, at: Date.now() })
      localStorage.setItem('ds.bookmarks', JSON.stringify(all.slice(-300))); toast('Page bookmarked')
    } catch { toast('Could not bookmark', true) }
  }
  function closeQS() { qs?.remove(); qs = null; qsClosedAt = Date.now() }
  function toggleQS() {
    if (qs) return closeQS()
    qs = mangaQuickSettings({ prefs, update, onClose: closeQS, act: {
      save: savePage, fullscreen: () => window.deepsync.win.action('fullscreen'), toggleAuto, isAuto: () => !!auto,
      chapters: () => chapters, cur: () => cur, page: () => page, pages: () => pages.length,
      jump: (i) => load(i), prev: () => load(cur - 1), next: () => load(cur + 1), goPage: (p) => (web() ? body.querySelector(`[data-i="${p}"]`)?.scrollIntoView() : body._show(p)), bookmark,
      reset: () => { update({ ...MANGA_DEFAULTS }); toast('Reset to defaults') },
    } })
    root.append(qs)
  }

  // Click the page to hide / show the top bar and the page slider; they also peek out while the mouse is at the top or bottom edge.
  // They hide by themselves a few seconds after a chapter opens.
  let hideT, qsClosedAt = 0
  const showBars = () => { clearTimeout(hideT); root.classList.remove('hide') }
  const armHide = () => { clearTimeout(hideT); hideT = setTimeout(() => { if (!qs) root.classList.add('hide') }, 3500) }
  root.addEventListener('mousemove', (e) => root.classList.toggle('peek', e.clientY < 80 || e.clientY > innerHeight - 110))
  armHide()
  range.oninput = () => (web() ? body.querySelector(`[data-i="${range.value}"]`)?.scrollIntoView() : body._show(+range.value))
  body.addEventListener('click', (e) => { if (Date.now() - qsClosedAt < 300 || (web() && e.target.tagName !== 'IMG')) return; clearTimeout(hideT); root.classList.toggle('hide') })
  let over = 0
  body.addEventListener('wheel', (e) => {
    if (e.ctrlKey) { e.preventDefault(); return zoomBy(e.deltaY > 0 ? -10 : 10) }
    if (web() && prefs.autoNext !== false) {
      if (body.scrollTop + body.clientHeight >= body.scrollHeight - 4 && e.deltaY > 0) { over += e.deltaY; if (over > 700) { over = 0; load(cur + 1) } } else over = 0
    }
  }, { passive: false })
  body.addEventListener('wheel', (e) => { if (prefs.mode !== 'vertical') return; e.preventDefault(); if (Date.now() - wheelAt > 220) { wheelAt = Date.now(); step(e.deltaY > 0 ? 1 : -1) } }, { passive: false })
  root.addEventListener('mousedown', (e) => { if (qs && !qs.contains(e.target) && !e.target.closest('[data-qs]')) closeQS() })
  const key = (e) => {
    if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return
    const rtl = prefs.mode === 'rtl', k = e.key.toLowerCase()
    if (e.key === 'ArrowRight') step(rtl ? -1 : 1)
    else if (e.key === 'ArrowLeft') step(rtl ? 1 : -1)
    else if (e.key === 'ArrowDown' && prefs.mode === 'vertical') step(1)
    else if (e.key === 'ArrowUp' && prefs.mode === 'vertical') step(-1)
    else if (e.key === ' ' && !web()) (e.preventDefault(), step(e.shiftKey ? -1 : 1))
    else if (k === 'n') load(cur + 1)
    else if (k === 'p') load(cur - 1)
    else if (k === 'f') window.deepsync.win.action('fullscreen')
    else if (k === 's') toggleQS()
    else if (k === 'a') toggleAuto()
    else if (e.key === '+' || e.key === '=') zoomBy(10)
    else if (e.key === '-' || e.key === '_') zoomBy(-10)
    else if (e.key === '0') update({ zoom: 100 })
  }
  window.addEventListener('keydown', key)
  const close = overlay(root, { onClose: () => { window.removeEventListener('keydown', key); io?.disconnect(); haltAuto(); clearInterval(eyeT); clearTimeout(hideT); wake?.release?.(); wake = null } })
  applyLook()
  load(index, startPage)
  return close
}

// ---- novel (EPUB) reader -----------------------------------------------------------------------------------------------------
// Header: chapter title + "3 / 520 • 12% • 8 min". Bottom bar: Chapters / Listen / Settings, like the Android reader.
const bionicify = (rootEl) => {
  const w = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, { acceptNode: (n) => (n.parentElement.closest('script,style,b,strong,h1,h2,h3') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT) })
  const nodes = []
  while (w.nextNode()) nodes.push(w.currentNode)
  for (const n of nodes) {
    if (!n.nodeValue.trim()) continue
    const frag = document.createDocumentFragment()
    for (const t of n.nodeValue.split(/(\s+)/)) {
      const m = /^([^\p{L}\p{N}]*)([\p{L}\p{N}]+)([\s\S]*)$/u.exec(t)
      if (!m) { frag.append(t); continue }
      const k = Math.ceil(m[2].length / 2)
      frag.append(m[1], h('b', { class: 'bio' }, m[2].slice(0, k)), m[2].slice(k) + m[3])
    }
    n.replaceWith(frag)
  }
}

// Local EPUB ({ path }) or chapters from an online source ({ remote: { key, chapters: [{ label, number, load }], index, onRead } }).
export async function openNovelReader({ path, title, remote }) {
  const prefs = novelPrefs(title)
  const posKey = remote ? 'novelweb:' + remote.key : 'novel:' + path
  let info, cur = 0, saveT, raw = '', auto = null, qs = null, closeToc = null
  const tts = { on: false, i: 0, items: [], token: 0, bar: null }
  const content = h('div', { class: 'novel-content' })
  const scroll = h('div', { class: 'scroll', tabindex: 0 }, content)
  const head = h('b'), meta = h('span')
  const root = h('div', { class: 'reader' })
  const apply = () => {
    const [bg, fg] = NOVEL_THEMES[prefs.theme] || NOVEL_THEMES.dark
    root.style.cssText = `--nbg:${bg};--nfg:${fg};--nsize:${prefs.size}px;--nlh:${prefs.lh};--npara:${prefs.para}em;--nw:${prefs.width}px;--nz:${(prefs.zoom || 100) / 100};--nalign:${prefs.align};--nfont:${NOVEL_FONTS[prefs.font] || NOVEL_FONTS.serif}`
  }
  const update = (patch) => {
    const bio = patch.bionic != null && patch.bionic !== prefs.bionic
    Object.assign(prefs, patch); saveNovelPrefs(title, prefs); apply()
    if (bio) render()
    if (auto) (haltAuto(), startAuto())
  }
  const render = () => { const keep = pct(); content.innerHTML = raw; if (prefs.bionic) bionicify(content); content.append(h('div', { style: { textAlign: 'center', padding: '40px 0' } }, h('button', { class: 'btn', onclick: () => load(cur + 1) }, 'Next chapter', icon('chevR', 18)))); scroll.scrollTop = keep * (scroll.scrollHeight - scroll.clientHeight) }
  const pct = () => scroll.scrollTop / Math.max(1, scroll.scrollHeight - scroll.clientHeight)
  const words = () => (content.innerText || '').trim().split(/\s+/).length
  const updateMeta = () => {
    if (!info) return
    const p = Math.min(1, Math.max(0, pct()))
    meta.textContent = `${cur + 1} / ${info.chapters.length} • ${Math.round(p * 100)}% • ${Math.max(1, Math.ceil((words() * (1 - p)) / 230))} min`
  }

  function haltAuto() { if (!auto) return; cancelAnimationFrame(auto.raf); auto = null }
  function startAuto() { haltAuto(); let last = performance.now(); auto = {}; const tick = (t) => { scroll.scrollTop += (prefs.autoSpeed * 14 * (t - last)) / 1000; last = t; if (auto) auto.raf = requestAnimationFrame(tick) }; auto.raf = requestAnimationFrame(tick) }
  const toggleAuto = () => { if (auto) (haltAuto(), toast('Automatic scroll off')); else (startAuto(), toast('Automatic scroll on')) }

  // ---- Listen (text to speech) ----
  const items = () => [...content.querySelectorAll('p,h1,h2,h3,h4,li,blockquote')].filter((e) => e.textContent.trim().length > 1 && !e.querySelector('p'))
  function speak(i) {
    if (!tts.on) return
    const my = ++tts.token
    const el = tts.items[i]
    if (!el) { if (cur + 1 < info.chapters.length) return load(cur + 1).then(() => { tts.items = items(); speak(0) }); return stopListen() }
    tts.i = i
    tts.items.forEach((x) => x.classList.remove('tts-on')); el.classList.add('tts-on'); el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    const u = new SpeechSynthesisUtterance(el.textContent)
    u.rate = prefs.rate; u.pitch = prefs.pitch
    const v = speechSynthesis.getVoices().find((x) => x.name === prefs.voice); if (v) u.voice = v
    u.onend = () => my === tts.token && speak(i + 1)
    u.onerror = (e) => { if (my === tts.token && !['interrupted', 'canceled'].includes(e.error)) { toast(`Speech failed (${e.error}). On Linux install speech-dispatcher or espeak-ng.`, true); stopListen() } }
    speechSynthesis.cancel(); speechSynthesis.speak(u)
  }
  function startListen() {
    if (!('speechSynthesis' in window)) return toast('Text to speech is not available here', true)
    tts.on = true; tts.items = items()
    if (!tts.bar) { tts.bar = listenBar({ prefs, update, act: { toggle: () => { if (tts.on && !tts.paused) (tts.paused = true, tts.token++, speechSynthesis.cancel(), tts.bar.setPlaying(false)); else (tts.paused = false, tts.on = true, tts.bar.setPlaying(true), speak(tts.i)) }, skip: (d) => { tts.paused = false; tts.bar.setPlaying(true); speak(Math.max(0, tts.i + d)) }, stop: () => stopListen() } }); root.append(tts.bar) }
    tts.paused = false; tts.bar.setPlaying(true); tts.bar.classList.remove('hidden')
    const first = tts.items.findIndex((e) => e.getBoundingClientRect().top >= 90)
    speak(Math.max(0, first))
  }
  function stopListen() { tts.on = false; tts.paused = false; tts.token++; try { speechSynthesis.cancel() } catch {} ; tts.items.forEach((x) => x.classList.remove('tts-on')); tts.bar?.classList.add('hidden'); listenBtn?.classList.remove('on') }
  const toggleListen = () => { if (tts.on) stopListen(); else (startListen(), listenBtn.classList.add('on')) }

  // ---- panels ----
  function closeQS() { qs?.remove(); qs = null; setBtn?.classList.remove('on') }
  function toggleQS() {
    if (qs) return closeQS()
    qs = novelQuickSettings({ prefs, update, onClose: closeQS, act: { toggleAuto, isAuto: () => !!auto, reset: () => { update({ ...NOVEL_DEFAULTS, voice: prefs.voice }); toast('Reset to defaults') } } })
    root.append(qs); setBtn.classList.add('on')
  }
  function openToc() {
    const q = h('input', { class: 'input', placeholder: 'Search chapters…', style: { marginBottom: '12px' } })
    const list = h('div')
    const draw = () => list.replaceChildren(...info.chapters.map((c, i) => [c, i]).filter(([c]) => !q.value || c.title.toLowerCase().includes(q.value.toLowerCase())).map(([c, i]) =>
      h('div', { class: 'ep-row' + (i === cur ? ' cur' : ''), onclick: () => (closeToc?.(), load(i)) }, h('div', { class: 'th', style: { width: '44px', aspectRatio: '1' } }, i + 1), h('div', { class: 'tx' }, h('b', null, c.title)))))
    q.oninput = draw; draw()
    closeToc = sheet({ title: `Chapters (${info.chapters.length})`, body: h('div', null, q, list) })
    setTimeout(() => list.querySelector('.cur')?.scrollIntoView({ block: 'center' }))
  }

  let listenBtn, setBtn
  const navBtn = (ic, label, fn) => h('button', { class: 'nb', onclick: fn }, icon(ic, 20), h('span', null, label))
  listenBtn = h('button', { class: 'nb listen-btn', onclick: toggleListen }, icon('play', 20), h('span', null, 'Listen'))
  setBtn = navBtn('sliders', 'Settings', toggleQS)
  const nbar = h('div', { class: 'nbar', 'data-qs': 1 }, navBtn('menu', 'Chapters', () => info && openToc()), listenBtn, setBtn)
  const bar = h('div', { class: 'bar', style: { zIndex: 6 } },
    h('button', { class: 'ibtn', onclick: () => close() }, icon('x')),
    h('div', { class: 'ttl', style: { textAlign: 'center' } }, head, meta),
    h('button', { class: 'ibtn', title: 'Previous (←)', onclick: () => load(cur - 1) }, icon('skipB')), h('button', { class: 'ibtn', title: 'Next (→)', onclick: () => load(cur + 1) }, icon('skipF')))
  root.append(bar, h('div', { class: 'novel' }, scroll), nbar)
  apply()
  const persistPos = () => api('progress:set', { key: posKey, value: { chapter: cur, scroll: pct() } }).catch(() => {})
  const close = overlay(root, { onClose: () => { window.removeEventListener('keydown', key); stopListen(); haltAuto(); persistPos() } })
  async function load(i, restore = 0) {
    if (i < 0 || i >= info.chapters.length) return
    cur = i
    try { raw = remote ? (await remote.chapters[i].load()).html : await api('epub:chapter', { path, href: info.chapters[i].href }) } catch (e) { return toast(e.message, true) }
    render()
    if (remote) remote.onRead?.(remote.chapters[i])
    scroll.scrollTop = restore ? restore * (scroll.scrollHeight - scroll.clientHeight) : 0
    head.textContent = info.chapters[i].title
    updateMeta(); persistPos()
  }
  const key = (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return
    const k = e.key.toLowerCase()
    if (e.key === 'ArrowRight' || k === 'n') load(cur + 1)
    else if (e.key === 'ArrowLeft' || k === 'p') load(cur - 1)
    else if (k === 's') toggleQS()
    else if (k === 'c' && info) openToc()
    else if (k === 'l') toggleListen()
    else if (k === 'a') toggleAuto()
    else if (e.key === '+' || e.key === '=') nZoom(10)
    else if (e.key === '-' || e.key === '_') nZoom(-10)
    else if (e.key === '0') update({ zoom: 100 })
  }
  const nZoom = (d) => { const z = Math.max(60, Math.min(220, (prefs.zoom || 100) + d)); update({ zoom: z }); toast(`Zoom ${z}%`) }
  scroll.addEventListener('wheel', (e) => { if (e.ctrlKey) { e.preventDefault(); nZoom(e.deltaY > 0 ? -10 : 10) } }, { passive: false })
  window.addEventListener('keydown', key)
  root.addEventListener('mousedown', (e) => { if (qs && !qs.contains(e.target) && !e.target.closest('[data-qs]')) closeQS() })
  scroll.addEventListener('scroll', () => { updateMeta(); clearTimeout(saveT); saveT = setTimeout(persistPos, 1500) })
  if (remote) info = { chapters: remote.chapters.map((c) => ({ title: c.label })) }
  else try { info = await api('epub:open', { path }) } catch (e) { toast(e.message, true); return close() }
  const saved = (await api('progress:list').catch(() => ({})))[posKey]
  // Online chapters: only resume the saved scroll position when opening the same chapter again.
  if (remote && saved && saved.chapter === remote.index) load(saved.chapter, saved.scroll || 0)
  else if (remote) load(remote.index || 0)
  else load(saved?.chapter || 0, saved?.scroll || 0)
}

// ---- video player ------------------------------------------------------------------------------------------------------------------
// Files are either local ({ path }) or online episodes from an extension ({ key, videos: async () => [video, ...] }).
const ls = (k, d = '') => { try { return localStorage.getItem(k) ?? d } catch { return d } }
const lset = (k, v) => { try { localStorage.setItem(k, v) } catch {} }
const heightOf = (v) => Number(/(\d{3,4})\s*p/i.exec(`${v.quality} ${v.title}`)?.[1]) || 0
// Remembered target height; otherwise the best the source offers.
export function pickVideo(videos) {
  const pq = S.settings.player?.quality, want = pq && pq !== 'auto' ? Number(pq) : Number(ls('ds.quality', '0'))
  // Audio preset (Sub / Dub): when the source offers streams for it, only those are considered.
  const audio = S.settings.player?.audio === 'dub' ? /dub/i : /^(?!.*dub)/i
  const pool = videos.map((v, i) => i).filter((i) => audio.test(`${videos[i].title} ${videos[i].quality}`))
  const idx = pool.length ? pool : videos.map((v, i) => i)
  const hs = videos.map(heightOf)
  if (!idx.some((i) => hs[i])) return idx[0] || 0
  let best = idx[0]
  for (const i of idx) {
    const hv = hs[i], hb = hs[best]
    if (!want) { if (hv > hb) best = i }
    else if (hv <= want ? (hb > want || hv > hb) : (hb > want && hv < hb)) best = i
  }
  return best
}
let hlsLoading
const loadHls = () => window.Hls ? Promise.resolve() : (hlsLoading ||= new Promise((res, rej) => {
  const el = document.createElement('script'); el.src = 'vendor/hls.min.js'; el.onload = res; el.onerror = () => rej(new Error('Could not load the video library'))
  document.head.append(el)
}))

// Every live player's <video>. Starting one pauses the others, so a forgotten player can never keep playing underneath a new one.
const liveVideos = new Set()
export function createPlayer({ title, files, index = 0, onWatched, onIndex, onClose, inline = false, poster = '' }) {
  let cur = index, reported = false, lastSave = 0, hls = null, vids = [], vi = 0, token = 0
  const video = h('video', { autoplay: true, preload: 'auto' })
  liveVideos.add(video)
  video.addEventListener('play', () => liveVideos.forEach((v) => v !== video && !v.paused && v.pause()))
  const sub = h('span')
  const root = h('div', { class: 'reader player' + (inline ? ' inline' : '') })
  const idle = h('div', { class: 'p-idle hidden', style: poster ? { backgroundImage: `url("${poster}")` } : null }, h('div', { class: 'p-idle-in' }, h('button', { class: 'p-big', onclick: () => load(Math.max(0, startAt)), 'aria-label': 'Play' }, icon('play', 34)), h('h3', null, 'Waiting for Episode to be picked'), h('p', null, 'Choose an episode to begin watching')))
  let startAt = 0
  const err = h('div', { class: 'perr hidden' })
  const quality = h('select', { class: 'input hidden', 'aria-label': 'Quality', style: { width: 'auto', height: '34px' } })
  const mpv = async () => {
    video.pause()
    const f = files[cur]
    const r = f.videos ? await api('ext:mpv', { url: vids[vi]?.url, headers: vids[vi]?.headers, subtitles: vids[vi]?.subtitles, title: `${title} · ${f.name}` }) : await api('player:mpv', { file: f.path })
    r.error ? toast(r.error, true) : toast('Opened in mpv')
  }
  // ---- desktop version of the Android player extras: speed, subtitles, audio, resize, PiP, lock, notes, gestures, shortcuts ----
  const lsj = (k, d) => { try { const v = JSON.parse(localStorage.getItem(k)); return v ?? d } catch { return d } }
  const lsw = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)) } catch {} }
  const pp = { speed: lsj('ds.p.speed', 1), fit: lsj('ds.p.fit', 'contain'), locked: false }
  const applyLook = () => { video.playbackRate = pp.speed; video.style.objectFit = pp.fit }
  video.addEventListener('loadedmetadata', applyLook); video.addEventListener('loadeddata', applyLook)
  const tried = new Set()
  const flash = h('div', { class: 'p-flash hidden' }); let flashT
  const say = (t) => { flash.textContent = t; flash.classList.remove('hidden'); clearTimeout(flashT); flashT = setTimeout(() => flash.classList.add('hidden'), 900) }
  const seek = (d) => { if (video.duration) { video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + d)); say((d > 0 ? '+' : '') + d + ' s') } }
  const tick = (on) => (on ? 'check' : null)
  const setSpeed = (v) => { pp.speed = Math.max(0.25, Math.min(3, Math.round(v * 100) / 100)); lsw('ds.p.speed', pp.speed); applyLook(); say(pp.speed + '×') }
  const extSubs = () => [...video.querySelectorAll('track')].map((t) => t.track)
  // Every subtitle track of the current stream: <track> files from the source, plus tracks inside an HLS playlist.
  const subList = () => [
    ...extSubs().map((t, i) => ({ kind: 'ext', i, label: t.label || `Subtitles ${i + 1}`, lang: t.language || '' })),
    ...(hls?.subtitleTracks || []).map((t, i) => ({ kind: 'hls', i, label: t.name || t.lang || `Subtitles ${i + 1}`, lang: t.lang || '' })),
  ]
  const showSub = (it) => {
    extSubs().forEach((t) => (t.mode = 'disabled'))
    if (hls) { hls.subtitleTrack = -1; hls.subtitleDisplay = false }
    if (!it) return
    if (it.kind === 'ext') extSubs()[it.i].mode = 'showing'
    else if (hls) { hls.subtitleDisplay = true; hls.subtitleTrack = it.i }
  }
  const subOn = (it) => (it.kind === 'ext' ? extSubs()[it.i]?.mode === 'showing' : !!hls && hls.subtitleTrack === it.i && hls.subtitleDisplay !== false)
  // Which subtitle track to show. A choice made in this player (menu or C key) sticks for the next episodes.
  // Otherwise the Player settings decide: Sub turns subtitles on in the chosen language, Dub leaves them off.
  const autoSubs = () => {
    const list = subList(); if (!list.length) return
    const pick = pp.subPick, cfg = S.settings.player || {}, lang = cfg.subLang || 'en'
    let it = null
    if (pick === 'off') it = null
    else if (pick) it = list.find((x) => x.label.toLowerCase() === pick.toLowerCase()) || list[pickSubtitle(list, langOf(pick) || lang)] || null
    else if (cfg.audio !== 'dub' && lang !== 'off') it = list[pickSubtitle(list, lang)] || null
    showSub(it)
  }
  // Chromium also runs its own "match the system language" subtitle selection when tracks load, which can switch on a second
  // track after ours. For a few seconds after the tracks appear, any change is followed by re-applying our pick; a manual choice ends that.
  let autoUntil = 0, autoRuns = 0
  const armAuto = () => { autoUntil = Date.now() + 5000; autoRuns = 0; autoSubs() }
  video.textTracks.addEventListener('change', () => { if (Date.now() < autoUntil && autoRuns++ < 10) autoSubs() })
  const subsMenu = (e) => {
    const list = subList()
    if (!list.length) return say('No subtitle tracks in this stream')
    const items = [{ label: 'Off', icon: tick(!list.some(subOn)), run: () => ((autoUntil = 0), (pp.subPick = 'off'), showSub(null)) }]
    list.forEach((it) => items.push({ label: it.label, icon: tick(subOn(it)), run: () => ((autoUntil = 0), (pp.subPick = it.label), showSub(it)) }))
    menu(e.currentTarget, items)
  }
  const audioMenu = (e) => {
    const as = hls?.audioTracks || []
    if (as.length < 2) return say('This stream has a single audio track')
    menu(e.currentTarget, as.map((t, i) => ({ label: t.name || t.lang || `Audio ${i + 1}`, icon: tick(hls.audioTrack === i), run: () => (hls.audioTrack = i) })))
  }
  const fits = [['contain', 'Fit'], ['cover', 'Fill (crop)'], ['fill', 'Stretch']]
  const cycleFit = () => { const i = fits.findIndex((f) => f[0] === pp.fit); pp.fit = fits[(i + 1) % fits.length][0]; lsw('ds.p.fit', pp.fit); applyLook(); say(fits.find((f) => f[0] === pp.fit)[1]) }
  const pip = async () => { try { document.pictureInPictureElement ? await document.exitPictureInPicture() : await video.requestPictureInPicture() } catch { toast('Picture in picture is not available for this video', true) } }
  const lockBtn = h('button', { class: 'ibtn p-lock hidden', title: 'Unlock controls', onclick: () => setLock(false) }, icon('eyeoff', 20))
  const setLock = (v) => { pp.locked = v; root.classList.toggle('locked', v); lockBtn.classList.toggle('hidden', !v); say(v ? 'Controls locked' : 'Unlocked') }
  const noteKey = () => 'ds.notes:' + norm(title)
  const notesSheet = () => {
    if (document.fullscreenElement) document.exitFullscreen?.()
    const all = () => lsj(noteKey(), [])
    const list = h('div'), ta = h('textarea', { class: 'input', rows: 3, placeholder: 'Write a note…', style: { width: '100%' } })
    const clock = (s) => (s == null ? 'General' : `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`)
    const drawN = () => list.replaceChildren(...all().map((n, i) => h('div', { class: 's-row' },
      h('div', { style: { minWidth: 0 } }, h('b', null, `${clock(n.t)}${n.ep ? ' · ' + n.ep : ''}`), h('div', { class: 'd', style: { whiteSpace: 'pre-wrap' } }, n.text)),
      h('div', { style: { display: 'flex', gap: '6px' } }, n.t != null && h('button', { class: 'btn sm', onclick: () => ((video.currentTime = n.t), say(clock(n.t))) }, 'Jump'),
        h('button', { class: 'ibtn', title: 'Delete', onclick: () => (lsw(noteKey(), all().filter((_, k) => k !== i)), drawN()) }, icon('trash', 16))))))
    const add = (stamped) => { const text = ta.value.trim(); if (!text) return; lsw(noteKey(), [...all(), { t: stamped ? video.currentTime : null, ep: sub.textContent, text }]); ta.value = ''; drawN() }
    drawN()
    sheet({ title: 'Notes', body: h('div', null, ta, h('div', { style: { display: 'flex', gap: '8px', margin: '10px 0 16px' } }, h('button', { class: 'btn primary', onclick: () => add(true) }, `Add at ${clock(video.currentTime || 0)}`), h('button', { class: 'btn', onclick: () => add(false) }, 'Add general note')), list) })
  }
  // double-click: left third back 10 s, right third forward 10 s, middle fullscreen
  video.addEventListener('dblclick', (e) => {
    const r = video.getBoundingClientRect()
    e.preventDefault(); e.stopPropagation()
    const x = (e.clientX - r.left) / r.width
    if (x < 0.33) seek(-10); else if (x > 0.67) seek(10); else document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen?.()
  }, true)
  // True while a sheet (episodes, tracking, notes…) is open on top of this player. A sheet the player was opened FROM sits below it and doesn't count.
  const covered = () => {
    const ov = document.getElementById('overlay'); if (!ov) return false
    if (inline) return ov.children.length > 0
    const kids = [...ov.children], i = kids.indexOf(root)
    return i >= 0 && i < kids.length - 1
  }
  // Volume keys / wheel. "System" moves the real system volume (the <video> element's own volume can only ever scale below it);
  // if no mixer tool is found it falls back to the player's own volume. The slider and mute button always control the player's level.
  let volPending = 0, volBusy = false, sysDown = false
  const playerVol = (d) => { video.muted = false; video.volume = Math.max(0, Math.min(1, video.volume + d / 100)); say(Math.round(video.volume * 100) + '%') }
  const nudgeVolume = async (d) => {
    if (sysDown || (S.settings.player?.volumeScope || 'system') !== 'system') return playerVol(d)
    volPending += d
    if (volBusy) return
    volBusy = true
    if (video.volume < 1) video.volume = 1 // the system volume is the level now; the player stays at full so the two don't multiply
    video.muted = false
    while (volPending) {
      const step = volPending; volPending = 0
      const r = await api('system:volume', { delta: step }).catch((e) => ({ error: e.message }))
      if (r.error) { sysDown = true; toast(r.error + ' Using the player volume instead.', true); playerVol(step); break }
      say('Volume ' + r.percent + '%')
    }
    volBusy = false
  }
  const onKey = (e) => {
    if (!document.body.contains(root)) return document.removeEventListener('keydown', onKey, true)
    const tg = e.target
    // Typing fields keep their keys; the player's own volume slider and quality picker do not.
    const typing = tg.isContentEditable || tg.tagName === 'TEXTAREA' || (tg.tagName === 'INPUT' && !/^(range|checkbox|radio|button)$/.test(tg.type)) || (tg.tagName === 'SELECT' && !root.contains(tg))
    if (typing || e.ctrlKey || e.metaKey || e.altKey || pp.locked || covered()) return
    const k = tokenOf(e), used = () => (show(), e.preventDefault(), e.stopPropagation())
    // Number keys jump to 0-90 % and stay fixed; everything else comes from the remappable table (Settings → Player).
    if (k >= '0' && k <= '9' && k.length === 1) { if (video.duration) (used(), (video.currentTime = (video.duration * Number(k)) / 10)); return }
    const act = actionFor(k, S.settings.player?.keys)
    if (!act) return
    used()
    if (act === 'playPause') video.paused ? video.play() : video.pause()
    else if (act === 'back5') seek(-5); else if (act === 'fwd5') seek(5)
    else if (act === 'back10') seek(-10); else if (act === 'fwd10') seek(10)
    else if (act === 'volUp') nudgeVolume(5)
    else if (act === 'volDown') nudgeVolume(-5)
    else if (act === 'mute') (video.muted = !video.muted, say(video.muted ? 'Muted' : 'Sound on'))
    else if (act === 'fullscreen') document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen?.()
    else if (act === 'nextEp') load(cur < 0 ? Math.max(0, startAt) : cur + 1)
    else if (act === 'prevEp') load(cur - 1)
    else if (act === 'skip') seek(S.settings.player?.skipSeconds || 85)
    else if (act === 'speedUp') setSpeed(pp.speed + 0.25); else if (act === 'speedDown') setSpeed(pp.speed - 0.25)
    else if (act === 'fit') cycleFit()
    else if (act === 'subs') { const list = subList(); if (!list.length) say('No subtitle tracks in this stream'); else { const on = list.findIndex(subOn), nx = list[on + 1] || null; autoUntil = 0; pp.subPick = nx ? nx.label : 'off'; showSub(nx); say(nx ? nx.label : 'Subtitles off') } }
  }
  document.addEventListener('keydown', onKey, true)
  // Space on a focused button would otherwise also click it when released, toggling play twice.
  const onKeyUp = (e) => { if (e.key === ' ' && root.contains(e.target) && e.target.tagName === 'BUTTON') e.preventDefault() }
  document.addEventListener('keyup', onKeyUp, true)
  // A stream that fails (dead link, blocked host) is replaced by the next best one when auto-pick is on, before any error is shown.
  const tryNext = () => {
    if (S.settings.player?.autoPick === false || vids.length < 2) return false
    tried.add(vi)
    const rest = vids.map((v, i) => i).filter((i) => !tried.has(i)).sort((a, b) => Math.abs(heightOf(vids[a]) - heightOf(vids[vi])) - Math.abs(heightOf(vids[b]) - heightOf(vids[vi])))
    if (!rest.length) return false
    vi = rest[0]; quality.value = String(vi); toast(`That stream failed, trying ${vids[vi].quality || vids[vi].title}`)
    playStream(vids[vi], video.currentTime).catch((e) => fail('Could not start playback', e.message)); return true
  }
  // ================= streaming-style controls (custom UI, replaces the native <video> bar) =================
  const fmt = (t) => { t = Math.max(0, Math.floor(isFinite(t) ? t : 0)); const hh = Math.floor(t / 3600), mm = Math.floor((t % 3600) / 60), ss = String(t % 60).padStart(2, '0'); return hh ? `${hh}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}` }
  const btn = (ic, tip, fn, { cls = '', size = 26, badge } = {}) => h('button', { class: 'pc-btn ' + cls, title: tip, 'aria-label': tip, onclick: fn }, icon(ic, size), badge && h('span', { class: 'n10' }, badge))
  const vol0 = lsj('ds.p.vol', { v: 1, m: false }); video.volume = vol0.v; video.muted = vol0.m
  let streamInfo = null, dragging = false, dragT = 0, raf = 0, wasConn = false, hideT = 0, quietUntil = 0

  // seek bar: buffered + played + knob, hover tooltip with a frame preview (like the reference), click/drag to seek
  const bufI = h('i', { class: 'buf' }), playedI = h('i', { class: 'played' }), knob = h('b', { class: 'knob' })
  const track = h('div', { class: 'pc-track' }, bufI, playedI, knob)
  const pv = h('video', { class: 'hidden', preload: 'auto', playsInline: true }); pv.muted = true
  const tipTime = h('span'), tip = h('div', { class: 'pc-tip hidden' }, pv, tipTime)
  const seekEl = h('div', { class: 'pc-seek', role: 'slider', 'aria-label': 'Seek' }, track, tip)
  const remain = h('span', { class: 'pc-remain' }, '--:--')
  let pvHls = null, pvFor = null, pvT = 0
  const pvReset = () => { clearTimeout(pvT); pvHls?.destroy(); pvHls = null; pvFor = null; pv.classList.add('hidden'); pv.removeAttribute('src'); pv.load() }
  pv.addEventListener('loadeddata', () => pv.classList.remove('hidden'))
  const pvSetup = () => {
    if (!streamInfo || pvFor === streamInfo) return
    pvReset(); pvFor = streamInfo
    if (video.crossOrigin) pv.crossOrigin = video.crossOrigin; else pv.removeAttribute('crossorigin')
    if (!streamInfo.hls) pv.src = streamInfo.url
    else if (window.Hls?.isSupported()) {
      pvHls = new window.Hls({ enableWorker: false, maxBufferLength: 3, capLevelToPlayerSize: true })
      pvHls.on(window.Hls.Events.MANIFEST_PARSED, () => { pvHls.currentLevel = 0 }) // lowest quality is plenty for a thumbnail
      pvHls.loadSource(streamInfo.url); pvHls.attachMedia(pv)
    }
  }
  const frac = (e) => { const r = track.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) }
  const paint = () => {
    const d = video.duration || 0, t = dragging ? dragT : video.currentTime || 0, f = d ? t / d : 0
    playedI.style.width = knob.style.left = f * 100 + '%'
    let be = 0; for (let i = 0; i < video.buffered.length; i++) if (video.buffered.start(i) <= t + 0.5 && video.buffered.end(i) >= t) be = video.buffered.end(i)
    bufI.style.width = (d ? (be / d) * 100 : 0) + '%'
    remain.textContent = d ? fmt(d - t) : '--:--'
  }
  const loop = () => { if (root.isConnected) wasConn = true; else if (wasConn) { raf = 0; return } paint(); raf = requestAnimationFrame(loop) }
  const startLoop = () => { if (!raf) raf = requestAnimationFrame(loop) }
  seekEl.addEventListener('pointermove', (e) => {
    const d = video.duration; if (!d) return
    const f = frac(e), w = track.offsetWidth, half = 92
    tip.style.left = Math.max(half, Math.min(w - half, f * w)) + 'px'; tipTime.textContent = fmt(f * d); tip.classList.remove('hidden')
    pvSetup(); clearTimeout(pvT); pvT = setTimeout(() => { try { pv.currentTime = f * d } catch {} }, 80)
    if (dragging) { dragT = f * d; paint() }
  })
  seekEl.addEventListener('pointerleave', () => !dragging && tip.classList.add('hidden'))
  seekEl.addEventListener('pointerdown', (e) => { if (!video.duration) return; dragging = true; dragT = frac(e) * video.duration; seekEl.classList.add('drag'); seekEl.setPointerCapture(e.pointerId); paint() })
  const endDrag = (e) => { if (!dragging) return; dragging = false; seekEl.classList.remove('drag'); video.currentTime = dragT; tip.classList.add('hidden'); seekEl.releasePointerCapture?.(e.pointerId); show() }
  seekEl.addEventListener('pointerup', endDrag); seekEl.addEventListener('pointercancel', endDrag)

  // buttons
  const playBtn = btn('playFill', 'Play / pause (K)', () => (video.paused ? video.play() : video.pause()), { cls: 'fill', size: 30 })
  const syncPlay = () => playBtn.replaceChildren(icon(video.paused ? 'playFill' : 'pauseFill', 30))
  const muteBtn = btn('volume', 'Mute (M)', () => (video.muted = !video.muted), { size: 24 })
  const volEl = h('input', { class: 'pc-range', type: 'range', min: 0, max: 1, step: 0.02, 'aria-label': 'Volume', oninput: (e) => { video.muted = false; video.volume = Number(e.target.value) } })
  const syncVol = () => {
    const v = video.muted ? 0 : video.volume
    volEl.value = v; volEl.style.setProperty('--v', v * 100 + '%')
    muteBtn.replaceChildren(icon(v === 0 ? 'mute' : v < 0.5 ? 'volumeLow' : 'volume', 24)); lsw('ds.p.vol', { v: video.volume, m: video.muted })
  }
  video.addEventListener('volumechange', syncVol); syncVol()
  const fsBtn = btn('fullscreen', 'Fullscreen (F)', () => (document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen?.()))
  const onFs = () => fsBtn.replaceChildren(icon(document.fullscreenElement === root ? 'fsExit' : 'fullscreen', 26))
  document.addEventListener('fullscreenchange', onFs)
  const speedMenu = (a) => menu(a, [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((v) => ({ label: v + '×', icon: tick(pp.speed === v), run: () => setSpeed(v) })))
  const qualityMenu = (a) => menu(a, vids.map((v, k) => ({ label: v.quality || v.title || `Source ${k + 1}`, icon: tick(k === vi), run: () => { quality.value = String(k); quality.onchange() } })))
  const rt = (t) => h('span', { style: { color: 'var(--muted)', fontSize: '13px' } }, t)
  const gearMenu = (e) => {
    const a = e.currentTarget
    menu(a, [
      { icon: 'speed', label: 'Playback speed', right: rt(pp.speed + '×'), run: () => setTimeout(() => speedMenu(a)) },
      vids.length > 1 && { icon: 'film', label: 'Quality', right: rt(vids[vi]?.quality || vids[vi]?.title || ''), run: () => setTimeout(() => qualityMenu(a)) },
      { icon: 'fullscreen', label: 'Resize video (R)', right: rt(fits.find((f) => f[0] === pp.fit)[1]), run: cycleFit },
      { icon: 'pip', label: 'Picture in picture', run: pip },
      { icon: 'bookmark', label: 'Notes', run: notesSheet },
      { icon: 'eyeoff', label: 'Lock controls', run: () => setLock(true) },
      { icon: 'external', label: 'Open in mpv', run: mpv },
    ].filter(Boolean))
  }
  const skipSecs = () => S.settings.player?.skipSeconds || 85
  const skipBtn = h('button', { class: 'pc-skip', title: 'Skip ahead (S)', onclick: () => seek(skipSecs()) }, `Skip ${skipSecs()}s`, icon('skipF', 18))
  const spin = h('div', { class: 'pc-spin hidden' })
  const ui = h('div', { class: 'pc-ui' },
    h('div', { class: 'pc-top' }, !inline ? btn('back', 'Back', () => onClose?.()) : h('span'), h('span', { class: 'pc-top-r' }, btn('eyeoff', 'Lock controls', () => setLock(true), { size: 22 }), btn('external', 'Open in mpv', mpv, { size: 22 }))),
    skipBtn,
    h('div', { class: 'pc-bottom' },
      h('div', { class: 'pc-seekrow' }, seekEl, remain),
      h('div', { class: 'pc-row' },
        h('div', { class: 'pc-l' }, playBtn, btn('replay10', 'Back 10 s (J)', () => seek(-10), { badge: '10' }), btn('fwd10', 'Forward 10 s (L)', () => seek(10), { badge: '10' }), h('div', { class: 'pc-vol' }, muteBtn, volEl)),
        h('div', { class: 'pc-mid' }, h('b', null, title), sub),
        h('div', { class: 'pc-r' }, btn('skipB', 'Previous episode (P)', () => load(cur - 1), { size: 22 }), btn('skipF', 'Next episode (N)', () => load(cur + 1), { size: 22 }), btn('text', 'Subtitles (C)', subsMenu), btn('layers', 'Audio track', audioMenu), btn('gear', 'Settings', gearMenu), fsBtn))))
  root.append(video, spin, err, idle, ui, flash, lockBtn)

  // auto-hide: controls fade after a moment of no activity while playing; mouse move shows them; a click on the picture shows / hides them
  const busy = () => video.paused || dragging || root.classList.contains('idle-on') || !err.classList.contains('hidden') || root.querySelector('.menu') || ui.querySelector('.pc-vol:hover, .pc-range:active')
  const show = () => { root.classList.remove('ui-off'); clearTimeout(hideT); if (!busy()) hideT = setTimeout(hideUi, 2800) }
  const hideUi = () => { if (busy()) return show(); root.classList.add('ui-off') }
  root.addEventListener('mousemove', () => Date.now() > quietUntil && show())
  root.addEventListener('mouseleave', () => (clearTimeout(hideT), !busy() && root.classList.add('ui-off')))
  // Click on the picture: the middle third plays / pauses, the outer thirds show or hide the controls.
  // It waits a moment so a double click (seek / fullscreen) isn't also treated as a single click.
  let clickT = 0
  root.addEventListener('click', (e) => {
    clearTimeout(clickT)
    if (e.target.closest('.pc-ui button, .pc-seek, .pc-vol, .menu, .p-idle, .perr, .p-lock')) return show()
    if (e.detail > 1) return
    const r = root.getBoundingClientRect(), x = (e.clientX - r.left) / r.width
    const middle = !pp.locked && x > 0.33 && x < 0.67 && !root.classList.contains('idle-on')
    clickT = setTimeout(() => {
      if (middle) { video.paused ? video.play() : video.pause(); say(video.paused ? 'Paused' : 'Playing'); show() }
      else if (root.classList.contains('ui-off')) show(); else (clearTimeout(hideT), (quietUntil = Date.now() + 600), root.classList.add('ui-off'))
    }, 230)
  })
  root.addEventListener('wheel', (e) => { if (pp.locked || e.target.closest('.menu')) return; e.preventDefault(); nudgeVolume(e.deltaY < 0 ? 5 : -5); show() }, { passive: false })
  video.addEventListener('play', () => (syncPlay(), show())); video.addEventListener('pause', () => (syncPlay(), show()))
  video.addEventListener('waiting', () => spin.classList.remove('hidden')); video.addEventListener('seeking', () => spin.classList.remove('hidden'))
  for (const ev of ['playing', 'canplay', 'seeked', 'error', 'emptied']) video.addEventListener(ev, () => ev !== 'emptied' && spin.classList.add('hidden'))
  video.addEventListener('loadstart', () => spin.classList.remove('hidden'))
  const fail = (headline, detail) => {
    err.replaceChildren(h('div', null, h('h3', null, headline), h('p', { class: 'note', style: { margin: '8px 0 18px' } }, detail), h('button', { class: 'btn primary', onclick: mpv }, icon('external', 16), 'Open in mpv')))
    err.classList.remove('hidden')
  }
  const teardown = () => { hls?.destroy(); hls = null; pvReset(); streamInfo = null; video.querySelectorAll('track').forEach((t) => t.remove()) }
  async function playStream(v, start) {
    const my = ++token
    teardown(); err.classList.add('hidden')
    const s = await api('ext:stream', { video: v })
    if (my !== token) return
    video.crossOrigin = 'anonymous' // our stream proxy sends CORS headers; needed for subtitle tracks
    streamInfo = { url: s.url, hls: !!s.hls }
    s.subtitles.forEach((t, i) => video.append(h('track', { kind: 'subtitles', src: t.url, label: t.label || `Subtitles ${i + 1}` })))
    // No srclang/default on purpose, so Chromium has less reason to pick a track itself.
    armAuto()
    if (start > 5) video.addEventListener('loadedmetadata', () => (video.currentTime = start), { once: true })
    if (s.hls) {
      await loadHls()
      if (my !== token) return
      if (window.Hls.isSupported()) {
        hls = new window.Hls({ maxBufferLength: 40, enableWorker: false })
        hls.on(window.Hls.Events.SUBTITLE_TRACKS_UPDATED, () => armAuto())
        hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, () => { const want = S.settings.player?.audio === 'dub' ? /dub|eng|^en/i : /jpn|^ja|japan/i; const i = hls.audioTracks.findIndex((t) => want.test(`${t.name} ${t.lang}`)); if (i >= 0 && hls.audioTrack !== i) hls.audioTrack = i })
        hls.on(window.Hls.Events.ERROR, (_, d) => { if (d.fatal && !tryNext()) fail('This stream failed to load', `${d.details || 'Playback error'}. The source may have blocked the request or the link expired; try another quality or mpv.`) })
        hls.loadSource(s.url); hls.attachMedia(video)
        return
      }
    }
    video.src = s.url
  }
  async function load(i, resume = true) {
    if (i < 0 || i >= files.length) return toast(i < 0 ? 'First episode' : 'No more episodes')
    cur = i; reported = false; tried.clear(); liveVideos.add(video); teardown(); idle.classList.add('hidden'); root.classList.remove('idle-on'); startLoop(); show(); video.classList.remove('hidden'); onIndex?.(i)
    err.classList.add('hidden')
    const f = files[i]
    sub.textContent = f.episode != null ? `Episode ${f.episode}` : f.name
    const pos = resume ? (await api('progress:list'))[f.key || 'file:' + f.path]?.position : 0
    if (!f.videos) { quality.classList.add('hidden'); vids = []; video.removeAttribute('crossorigin'); video.src = mediaUrl(f.path); streamInfo = { url: video.src, hls: false }; if (pos > 5) video.addEventListener('loadedmetadata', () => (video.currentTime = pos), { once: true }); return }
    video.removeAttribute('src'); video.load()
    sub.textContent += ' · loading…'
    try { vids = await f.videos() } catch (e) { sub.textContent = sub.textContent.replace(' · loading…', ''); return fail('Could not get this episode', e.message) }
    sub.textContent = sub.textContent.replace(' · loading…', '')
    if (!vids.length) return fail('No playable links', 'The source returned nothing for this episode.')
    vi = pickVideo(vids)
    quality.replaceChildren(...vids.map((v, k) => h('option', { value: k, selected: k === vi }, v.quality || v.title)))
    quality.classList.toggle('hidden', vids.length < 2)
    await playStream(vids[vi], pos).catch((e) => fail('Could not start playback', e.message))
  }
  quality.onchange = () => {
    vi = Number(quality.value)
    const h0 = heightOf(vids[vi]); if (h0) lset('ds.quality', String(h0))
    playStream(vids[vi], video.currentTime).catch((e) => fail('Could not start playback', e.message))
  }
  video.addEventListener('timeupdate', () => {
    const f = files[cur]
    if (!reported && video.duration && video.currentTime / video.duration > 0.85) { reported = true; onWatched?.(f) }
    if (Date.now() - lastSave > 5000 && video.currentTime > 5) { lastSave = Date.now(); api('progress:set', { key: f.key || 'file:' + f.path, value: { position: video.currentTime, duration: video.duration } }).catch(() => {}) }
  })
  video.addEventListener('ended', () => S.settings.player?.autoNext !== false && cur + 1 < files.length && load(cur + 1, false))
  video.addEventListener('error', () => {
    if (hls) return // hls.js reports its own errors
    if (files[cur].videos && tryNext()) return
    fail('The built-in player can’t play this', files[cur].videos ? 'The stream may need a different quality, or use a codec Chromium lacks. mpv plays everything.' : 'Chromium doesn’t support every codec (for example HEVC or some audio tracks). mpv plays everything.')
  })
  // stop = silence and reset the stream, keep the player usable (idle screen). destroy = the player is gone for good.
  const stop = () => (cancelAnimationFrame(raf), (raf = 0), clearTimeout(hideT), token++, teardown(), video.pause(), video.removeAttribute('src'), video.load())
  const destroy = () => (liveVideos.delete(video), document.removeEventListener('keydown', onKey, true), document.removeEventListener('keyup', onKeyUp, true), document.removeEventListener('fullscreenchange', onFs), stop())
  const showIdle = () => { stop(); video.classList.add('hidden'); idle.classList.remove('hidden'); root.classList.add('idle-on'); root.classList.remove('ui-off'); sub.textContent = '' }
  if (index < 0) showIdle(); else load(index)
  return { el: root, load, destroy, showIdle, setFiles: (f, start = 0) => { files = f; startAt = start }, get index() { return cur }, video }
}

export function openPlayer(opts) {
  const { title, files, index = 0 } = opts
  if (S.settings.player.mode === 'mpv') {
    const f = files[index]
    if (f.videos) f.videos().then((vs) => { const v = vs[pickVideo(vs)]; return v ? api('ext:mpv', { url: v.url, headers: v.headers, subtitles: v.subtitles, title: `${title} · ${f.name}` }) : { error: 'No playable links found' } }).then((r) => (r.error ? toast(r.error, true) : toast('Opened in mpv. Progress is not tracked automatically.'))).catch((e) => toast(e.message, true))
    else api('player:mpv', { file: f.path }).then((r) => (r.error ? toast(r.error, true) : toast('Opened in mpv. Progress is not tracked automatically.')))
    return () => {}
  }
  // Sheets that are already open (the episode list this was started from) would sit on top of the player (z-index) and swallow
  // clicks and keys. Hide and disable them while the player is up, and bring them back when it closes.
  const below = [...(document.getElementById('overlay')?.children || [])]
  below.forEach((n) => { n.dataset.pv = n.style.visibility || '-'; n.style.visibility = 'hidden'; n.inert = true })
  const p = createPlayer({ ...opts, onClose: () => close() })
  const close = overlay(p.el, { onClose: () => { p.destroy(); below.forEach((n) => { n.style.visibility = n.dataset.pv === '-' ? '' : n.dataset.pv; n.inert = false; delete n.dataset.pv }) } })
  return close
}

// ---- local library entry sheet ----------------------------------------------------------------------------------------------------------
export function openLocalSheet(entry, { matched, onLinked } = {}) {
  const body = h('div')
  const mode = entry.type
  if (matched) body.append(h('div', { style: { marginBottom: '14px' } }, h('button', { class: 'btn primary', onclick: () => go(`/media/${mode}/${matched.id}`) }, 'Open details page')))
  if (mode === 'anime') {
    const files = entry.files.filter((f) => f.episode != null)
    entry.files.forEach((f) => body.append(h('div', { class: 'ep-row', onclick: () => openPlayer({ title: entry.title, files: entry.files.some((x) => x.episode == null) ? entry.files : files, index: (entry.files.some((x) => x.episode == null) ? entry.files : files).indexOf(f), onWatched: (ff) => matched && ff.episode != null && api('track:progress', { mode: 'anime', mediaId: matched.id, progress: ff.episode, total: totalOf(matched) }) }) },
      h('div', { class: 'th' }, f.episode ?? '▶'), h('div', { class: 'tx' }, h('b', null, f.episode != null ? `Episode ${f.episode}` : f.name), h('span', null, f.name)))))
  } else if (mode === 'manga') {
    const chs = entry.files.map((f) => ({ number: f.chapter, label: `Chapter ${f.chapter}`, sub: f.name, load: () => api('library:pages', { file: f }) }))
    chs.forEach((c, i) => body.append(h('div', { class: 'ep-row', onclick: () => openMangaReader({ title: entry.title, chapters: chs, index: i, onRead: (ch) => matched && api('track:progress', { mode: 'manga', mediaId: matched.id, progress: Math.floor(ch.number), total: totalOf(matched) }) }) },
      h('div', { class: 'th' }, c.number), h('div', { class: 'tx' }, h('b', null, c.label), h('span', null, c.sub)))))
  } else entry.files.forEach((f) => body.append(h('div', { class: 'ep-row', onclick: () => openNovelReader({ path: f.path, title: entry.title }) }, h('div', { class: 'th' }, icon('book', 22)), h('div', { class: 'tx' }, h('b', null, entry.title), h('span', null, entry.author || f.name)))))

  // Link to a different AniList entry when the automatic match is missing or wrong.
  const q = h('input', { class: 'input', placeholder: 'Search AniList…', value: entry.title })
  const res = h('div', { style: { marginTop: '8px' } })
  const search = async () => {
    const items = (await data('anilist:browse', { mode, search: q.value, sort: 'SEARCH_MATCH', perPage: 6 })).items
    res.replaceChildren(...items.map((m) => h('div', { class: 'ep-row', onclick: async () => { await api('library:link', { localKey: entry.key, mediaId: m.id }); toast('Linked to ' + titleOf(m)); close(); onLinked?.(m) } },
      h('div', { class: 'th', style: { width: '46px', aspectRatio: '2/3' } }, h('img', { src: coverOf(m), alt: '' })), h('div', { class: 'tx' }, h('b', null, titleOf(m)), h('span', null, [m.format, m.seasonYear].filter(Boolean).join(' · '))))))
  }
  q.onkeydown = (e) => e.key === 'Enter' && search()
  body.append(h('div', { class: 'h3' }, matched ? 'Linked to ' + titleOf(matched) : 'Link to AniList'), h('div', { style: { display: 'flex', gap: '8px' } }, q, h('button', { class: 'btn', onclick: search }, 'Search')), res)
  const close = sheet({ title: entry.title, body })
}
