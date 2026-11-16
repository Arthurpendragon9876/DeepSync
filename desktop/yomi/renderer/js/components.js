import { h, icon, S, api, go, onLeave, titleOf, coverOf, bannerOf, modeOf, unit, totalOf, pretty, menu, switchEl, segmented, saveSettings, currentPath, toast } from './core.js'

import { openMedia, openPrefsSheet } from './metadata.js'
export const mediaLink = (m) => `/media/${modeOf(m)}/${m.id}`

// ---- cards ------------------------------------------------------------------------------------------------------------
export function posterCard(m, { entry, sub, onclick } = {}) {
  const total = totalOf(m)
  const pct = entry && total ? Math.min(100, (entry.progress / total) * 100) : entry?.progress ? 8 : 0
  return h('div', { class: 'poster', onclick: onclick || (() => openMedia(m)) },
    h('div', { class: 'cover' },
      entry && h('div', { class: 'pbar' }, h('b', { style: { width: pct + '%' } })),
      h('img', { src: coverOf(m), alt: '', loading: 'lazy', draggable: false }),
      m.averageScore ? h('div', { class: 'score' }, icon('star', 12), (m.averageScore / 10).toFixed(1)) : null,
      entry ? h('div', { class: 'tag' }, `${entry.progress}${total ? ' / ' + total : ''}`) : m.nextAiringEpisode ? h('div', { class: 'tag' }, 'Ep ' + m.nextAiringEpisode.episode) : null),
    h('div', { class: 'name' }, titleOf(m)),
    h('div', { class: 'sub' }, sub ?? [pretty(m.format), m.seasonYear || m.startDate?.year].filter(Boolean).join(' · ')))
}

// "Continue" card: next episode/chapter for a tracked title. Every card uses the same layout (poster on the left, text on
// the right). The backdrop is the banner / episode thumbnail when there is one, otherwise a blurred copy of the cover.
export function wideCard(entry) {
  const m = entry.media
  const mode = modeOf(m)
  const next = entry.progress + 1
  const total = totalOf(m)
  const se = m.streamingEpisodes?.find((e) => new RegExp(`episode\\s*${next}\\b`, 'i').test(e.title))
  const epTitle = se?.title?.replace(/^.*?episode\s*\d+\s*[-–:]?\s*/i, '').trim()
  const pct = total ? (entry.progress / total) * 100 : 0
  const bannerImg = se?.thumbnail || m.bannerImage
  const cover = coverOf(m)
  const statusTxt = m.status === 'RELEASING' ? 'Releasing' : pretty(m.format)

  const thumb = h('div', { class: `thumb cc${bannerImg ? ' has-banner' : ''}` },
    h('img', { class: 'cc-bg', src: bannerImg || cover, alt: '', loading: 'lazy', draggable: false }),
    h('div', { class: 'cc-poster' }, h('img', { src: cover, alt: '', loading: 'lazy', draggable: false })),
    h('div', { class: 'cc-copy' }, h('div', { class: 'cc-box' },
      h('div', { class: 'cc-label' }, `Continue · ${unit(mode)} ${next}`, m.averageScore ? h('span', null, `★ ${(m.averageScore / 10).toFixed(1)}`) : null),
      h('div', { class: 'cc-title' }, titleOf(m)),
      h('div', { class: 'cc-genres' }, (m.genres || []).slice(0, 3).join(' · ')))),
    h('div', { class: 'bar' }, h('b', { style: { width: pct + '%' } })))

  return h('div', { class: 'wide', onclick: () => go(mediaLink(m)) }, thumb,
    h('div', { class: 't1' }, epTitle ? `${unit(mode)} ${next} · ${epTitle}` : `${unit(mode)} ${next}${total ? ' / ' + total : ''}`),
    h('div', { class: 't2' }, h('span', null, epTitle && total ? `${unit(mode)} ${next} / ${total} - ${statusTxt}` : statusTxt), mode === 'anime' && m.duration ? h('span', null, m.duration + 'm') : null))
}

// Click-and-drag horizontal scrolling for mouse users (touch/trackpad already scroll natively).
// A drag never triggers the click on the card underneath.
export function dragScroll(el) {
  let down = false, moved = false, startX = 0, startLeft = 0, lastX = 0, lastT = 0, vel = 0, raf = 0
  el.addEventListener('dragstart', (e) => e.preventDefault())
  el.addEventListener('mousedown', (e) => {
    if (e.button !== 0) return
    cancelAnimationFrame(raf)
    down = true; moved = false; startX = lastX = e.clientX; startLeft = el.scrollLeft; lastT = performance.now(); vel = 0
  })
  window.addEventListener('mousemove', (e) => {
    if (!down) return
    const dx = e.clientX - startX
    if (!moved && Math.abs(dx) < 5) return
    if (!moved) { moved = true; el.classList.add('dragging') }
    const now = performance.now()
    vel = (lastX - e.clientX) / Math.max(1, now - lastT) // px per ms
    lastX = e.clientX; lastT = now
    el.scrollLeft = startLeft - dx
    e.preventDefault()
  })
  const end = () => {
    if (!down) return
    down = false
    if (!moved) return
    // small momentum glide, then re-enable snapping
    let v = Math.abs(performance.now() - lastT) > 80 ? 0 : vel * 16
    const glide = () => {
      if (Math.abs(v) < 0.5) { el.classList.remove('dragging'); return }
      el.scrollLeft += v; v *= 0.93; raf = requestAnimationFrame(glide)
    }
    glide()
  }
  window.addEventListener('mouseup', end)
  // swallow the click that follows a drag
  el.addEventListener('click', (e) => { if (moved) { e.stopPropagation(); e.preventDefault(); moved = false } }, true)
}

// Wraps any horizontally scrolling track with hover arrows + drag-to-scroll.
export function scroller(track, extra = '') {
  const step = (d) => track.scrollBy({ left: d * track.clientWidth * 0.85, behavior: 'smooth' })
  dragScroll(track)
  return h('div', { class: `car ${extra}` }, h('button', { class: 'arrow l', onclick: () => step(-1), 'aria-label': 'Scroll left' }, icon('chevL')), track, h('button', { class: 'arrow r', onclick: () => step(1), 'aria-label': 'Scroll right' }, icon('chevR')))
}

export function carousel(title, items, { more, render = (x) => posterCard(x) } = {}) {
  const track = h('div', { class: 'track' }, items.map(render))
  return h('section', { class: 'section' },
    h('div', { class: 'section-head' }, h('h2', null, title), more && h('a', { class: 'more', onclick: more }, 'See all', icon('chevR', 16))),
    scroller(track))
}

// ---- hero --------------------------------------------------------------------------------------------------------------
// items: [{ media, label, entry? }]. Style comes from Settings > Appearance (Spotlight / Card / Panels).
export function hero(items, { style = 'spotlight', autoRotate = true, tools } = {}) {
  let idx = 0
  const el = h('div', { class: `hero ${style === 'spotlight' ? '' : style}` })
  el.style.setProperty('--n', items.length)
  const bg = h('div', { class: 'bg' })
  const layers = items.map((it) => {
    if (style === 'panels') {
      const pn = h('div', { class: 'pn', onclick: () => show(items.indexOf(it)) }, h('img', { src: bannerOf(it.media), alt: '' }))
      bg.append(pn)
      return pn
    }
    return null
  })
  const imgs = []
  if (style !== 'panels') {
    items.forEach((it, i) => {
      const img = h('img', { alt: '', draggable: false })
      if (i < 2) img.src = bannerOf(it.media) // later slides load when first shown
      imgs.push(img)
      bg.append(img)
    })
  }
  const label = h('div', { class: 'label' })
  const title = h('div', { class: 'title' })
  const meta = h('div', { class: 'meta' })
  const copy = h('div', { class: 'copy' }, label, title, meta)
  const dots = h('div', { class: 'dots' }, items.map((_, i) => h('i', { onclick: () => show(i), 'aria-label': `Slide ${i + 1}` })))
  el.append(bg, tools && h('div', { class: 'tools' }, tools))
  let posterL
  if (style === 'card') {
    posterL = h('div', { class: 'poster-l' }, h('img', { alt: '' }))
    el.append(posterL)
  }
  el.append(copy, items.length > 1 ? dots : null)

  function show(i) {
    idx = (i + items.length) % items.length
    const it = items[idx], m = it.media, mode = modeOf(m)
    if (style === 'panels') layers.forEach((l, k) => l.classList.toggle('on', k === idx))
    else {
      imgs.forEach((im, k) => im.classList.toggle('on', k === idx))
      const cur = imgs[idx]
      if (!cur.src) cur.src = bannerOf(m)
      const nxt = imgs[(idx + 1) % imgs.length]
      if (nxt && !nxt.src) nxt.src = bannerOf(items[(idx + 1) % items.length].media)
    }
    if (posterL) posterL.firstChild.src = coverOf(m)
    label.textContent = it.label
    title.textContent = titleOf(m)
    title.onclick = () => openMedia(m)
    meta.replaceChildren(...[
      m.averageScore ? h('span', null, `★ ${(m.averageScore / 10).toFixed(1)}`) : null, pretty(m.format), m.seasonYear || m.startDate?.year,
      it.entry ? `${unit(mode)} ${it.entry.progress + 1}${totalOf(m) ? ' of ' + totalOf(m) : ''}` : m.genres?.slice(0, 3).join(' · '),
    ].filter(Boolean).flatMap((x, k) => (k ? [h('i', { class: 'sep' }, '•'), x] : [x])).map((x) => (typeof x === 'string' || typeof x === 'number' ? h('span', null, x) : x)))
    dots.querySelectorAll('i').forEach((d, k) => d.classList.toggle('on', k === idx))
  }
  show(0)
  if (autoRotate && items.length > 1) {
    const t = setInterval(() => document.body.contains(el) && show(idx + 1), 8000)
    onLeave(() => clearInterval(t))
  }
  return el
}

// ---- sidebar ---------------------------------------------------------------------------------------------------------------
export function buildSidebar() {
  const side = document.getElementById('sidebar')
  const nav = (ico, tip, path, extra = {}) => {
    const b = h('button', { class: 'nav', 'data-tip': tip, 'aria-label': tip, 'data-path': path || '', onclick: extra.onclick || (() => go(path)) }, extra.node || icon(ico, 22))
    if (extra.badge) b.append(h('span', { class: 'badge', id: extra.badge }, ''))
    return b
  }
  const incog = nav('eyeoff', 'Incognito', null, { onclick: async () => { await saveSettings({ incognito: !S.settings.incognito }); sync(); toast(S.settings.incognito ? 'Incognito on — progress is not saved or synced' : 'Incognito off') } })
  const avatar = h('div', { class: 'avatar', title: 'Account', onclick: (e) => accountMenu(e.currentTarget) })

  // Windows-style navigation pane: compact icon rail by default, the menu button expands it to show labels (remembered).
  const setOpen = (on) => { document.body.classList.toggle('nav-open', on); try { localStorage.setItem('ds.navOpen', on ? '1' : '0') } catch {} }
  try { document.body.classList.toggle('nav-open', localStorage.getItem('ds.navOpen') === '1') } catch {}
  const burger = nav('menu', 'Menu', null, { onclick: () => setOpen(!document.body.classList.contains('nav-open')) })
  burger.classList.add('burger')

  side.replaceChildren(
    burger,
    h('img', { class: 'logo', src: 'assets/icon.png', alt: 'Yomi preferences', title: 'Yomi preferences', role: 'button', onclick: () => openPrefsSheet() }),
    nav('home', 'Home', '/'),
    nav('calendar', 'Schedule', '/schedule', { badge: 'sched-badge' }),
    nav('list', 'My lists', '/lists'),
    nav('compass', 'Discover', '/discover/anime', { onclick: () => go('/discover/' + S.mode) }),
    nav('search', 'Search', '/search'),
    nav('heart', 'Favourites', '/favourites'),
    nav('clock', 'History', '/history'),
    nav('chart', 'Stats', '/stats'),
    nav('library', 'Local library', '/library'),
    nav('rss', 'Updates', '/updates'),
    nav('sync', 'Sync library', null, { onclick: () => syncAll() }),
    nav('back', 'Back', null, { onclick: () => history.back() }),
    h('div', { class: 'spacer' }),
    nav(null, 'About', '/settings/about', { node: h('div', { class: 'dot-i' }, '!') }),
    nav('puzzle', 'Extensions', '/extensions'),
    incog,
    nav('gear', 'Settings', '/settings'),
    avatar)

  function sync() {
    const p = currentPath()
    side.querySelectorAll('.nav[data-path]').forEach((n) => {
      const path = n.dataset.path
      n.classList.toggle('active', !!path && (path === '/' ? p === '/' : p.startsWith(path.split('/').slice(0, 2).join('/')) && (path.startsWith('/discover') ? p.startsWith('/discover') : true)))
    })
    incog.classList.toggle('on', !!S.settings.incognito)
    incog.firstChild.replaceWith(icon(S.settings.incognito ? 'eyeoff' : 'eye', 22))
    const u = S.user
    avatar.replaceChildren(u?.avatar ? h('img', { src: u.avatar, class: 'avatar', style: { margin: 0 }, alt: '' }) : icon('user', 18))
    avatar.title = u ? u.name : 'Not signed in'
  }
  window.addEventListener('ds:route', sync)
  window.addEventListener('ds:user', sync)
  sync()
}

async function syncAll() {
  toast('Syncing library and tracker…')
  await api('cache:clear')
  await api('library:scan').catch(() => {})
  window.dispatchEvent(new Event('ds:refresh'))
}

function accountMenu(anchor) {
  const u = S.user
  menu(anchor, [
    u ? { label: `Signed in as ${u.name}`, icon: 'user', run: () => go('/settings/trackers') } : { label: 'Sign in to AniList', icon: 'user', run: () => go('/settings/trackers') },
    { label: 'Settings', icon: 'gear', run: () => go('/settings') },
    u && { label: 'Sign out', icon: 'x', run: async () => { await api('anilist:logout'); S.user = null; window.dispatchEvent(new Event('ds:user')); window.dispatchEvent(new Event('ds:refresh')) } },
  ].filter(Boolean))
}

// "Yugen preferences" popover from the hero's ⋮ button (incognito, layout, library).
export function prefsMenu(anchor) {
  menu(anchor, [
    { label: 'Incognito mode', icon: 'eyeoff', keep: true, right: switchEl(S.settings.incognito, async (v) => { await saveSettings({ incognito: v }); window.dispatchEvent(new Event('ds:route')) }) },
    { label: 'Home layout', icon: 'sliders', run: () => go('/settings/home') },
    { label: 'Local library', icon: 'library', run: () => go('/library') },
    { label: 'Settings', icon: 'gear', run: () => go('/settings') },
  ])
}

export const modeSeg = (onChange) => segmented([['anime', 'Anime'], ['manga', 'Manga'], ['novel', 'Novel']], S.mode, async (v) => { S.mode = v; await saveSettings({ mode: v }); onChange(v) })
