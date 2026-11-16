// The two floating buttons, like the mobile app (ModeFan.kt):
//  - Mode button: TAP continues what you last watched / read; PULL (drag past 14 px) blooms a quarter-circle fan with Anime / Manga / Novel,
//    the one under the pointer lights up and releasing switches to it; HOLD for a second opens the details of the last title, a ring charging
//    around the button. Right-click (or Enter on the focused button, then arrows) opens the fan without dragging.
// The mode it sets is the app-wide one (setMode in core.js), so every page follows it.
import { h, icon, S, setMode, go, toast, overlayCount } from './core.js'
import { MODES, ANGLE, PULL_PX, pickMode } from './fan-math.js'
import { lastTitle, continuePath, MODE_NAMES } from './metadata.js'

const HOLD_MS = 1000
const ICON = { anime: 'playCircle', manga: 'manga', novel: 'novel' }
const SVG = 'http://www.w3.org/2000/svg'
const svg = (tag, attrs = {}) => Object.entries(attrs).reduce((el, [k, v]) => (el.setAttribute(k, v), el), document.createElementNS(SVG, tag))

export function mountFab() {
  document.getElementById('fab-root')?.remove()
  if (S.settings.floatingButtons === false) return
  let fanOpen = false, locked = false, highlighted = null, down = null, holdTimer = 0, pulled = false, held = false, R = 280, O = { x: 0, y: 0 }, NR = 230, DEAD = 110

  const ring = svg('svg', { class: 'fab-ring', viewBox: '0 0 72 72' })
  ring.append(svg('circle', { cx: 36, cy: 36, r: 33, pathLength: 100 })) // a circle that fills up while you hold
  const glyph = h('span', { class: 'fab-glyph' })
  const main = h('button', { class: 'fab main', 'aria-haspopup': 'menu', 'aria-expanded': 'false' }, ring, glyph)
  const tip = h('span', { class: 'fab-tip' })
  main.append(tip)
  const col = h('div', { class: 'fab-col' }, main)

  // The fan: dimmed page, a quarter-circle sector whose tip is the button, the three modes on an arc, and the "Switch mode" pill.
  const dim = h('div', { class: 'fan-dim', onpointerdown: () => closeFan() })
  const amb = h('i', { class: 'fan-glow amb' }), spot = h('i', { class: 'fan-glow spot' }) // drifting light + a light that follows the highlighted mode
  const blob = h('div', { class: 'fan-blob' }, amb, spot) // frosted quarter disc: a div, because SVG can't blur what is behind it
  const sector = svg('svg', { class: 'fan-sector' }) // only holds the highlight line
  const nodes = Object.fromEntries(MODES.map((m) => [m, h('button', { class: 'fan-node', 'data-mode': m, role: 'menuitem', onclick: () => choose(m) }, icon(ICON[m], 28), h('b', null, MODE_NAMES[m]), h('i', { class: 'cur' }))]))
  const pillMode = h('div', { class: 'fan-mode' })
  const pill = h('div', { class: 'fan-pill' }, h('small', null, 'SWITCH MODE'), pillMode, h('span', null, 'Release to switch · Hold the button for details'))
  const fan = h('div', { class: 'fan', role: 'menu', 'aria-label': 'Switch mode' }, dim, blob, sector, ...Object.values(nodes), pill)
  const root = h('div', { id: 'fab-root' }, fan, col)
  document.body.append(root)

  const center = () => { const r = main.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 } }

  function layout() {
    const c = center(), EDGE = 0
    O = { x: innerWidth - EDGE, y: innerHeight - EDGE } // the fan is attached to the screen corner, like the mobile app; the button sits inside it
    R = Math.max(180, Math.min(380, innerHeight - 170, innerWidth - 110))
    NR = R * 0.82
    DEAD = Math.hypot(c.x - O.x, c.y - O.y) + 34 // pointer still near the button: nothing picked
        sector.replaceChildren()
    sector.setAttribute('viewBox', `0 0 ${innerWidth} ${innerHeight}`)
    blob.style.width = blob.style.height = R + 'px'
    blob.style.borderRadius = `${R - 26}px 26px 0 26px` // big round outer edge, softened tips; the radii add up to exactly R so the browser doesn't shrink them
    for (const m of MODES) {
      const a = (ANGLE[m] * Math.PI) / 180, x = O.x - NR * Math.cos(a), y = O.y - NR * Math.sin(a)
      nodes[m].style.left = x + 'px'; nodes[m].style.top = y + 'px'; nodes[m].dataset.x = x; nodes[m].dataset.y = y
      nodes[m].style.setProperty('--dx', c.x - x + 'px'); nodes[m].style.setProperty('--dy', c.y - y + 'px')
    }
    const spoke = svg('line', { class: 'spoke', x1: c.x, y1: c.y, x2: c.x, y2: c.y })
    sector.append(spoke)
    sector.spoke = spoke
    pill.style.right = '28px'
    pill.style.bottom = innerHeight - O.y + R + 14 + 'px'
  }
  function light() {
    for (const m of MODES) {
      nodes[m].classList.toggle('hl', highlighted === m)
      nodes[m].classList.toggle('current', S.mode === m)
    }
    const c = center(), el = highlighted && nodes[highlighted]
    if (sector.spoke) {
      sector.spoke.classList.toggle('on', !!el)
      if (el) { sector.spoke.setAttribute('x2', el.dataset.x); sector.spoke.setAttribute('y2', el.dataset.y) } else { sector.spoke.setAttribute('x2', c.x); sector.spoke.setAttribute('y2', c.y) }
    }
    const t = el ? { x: +el.dataset.x, y: +el.dataset.y } : c
    spot.style.left = t.x - (O.x - R) + 'px'; spot.style.top = t.y - (O.y - R) + 'px'
    const shown = highlighted || S.mode
    pillMode.replaceChildren(icon(ICON[shown], 24), h('b', null, MODE_NAMES[shown]))
  }
  function openFan(isLocked) {
    if (fanOpen) return
    fanOpen = true; locked = isLocked; highlighted = S.mode
    layout(); light()
    void fan.offsetWidth // let the freshly drawn sector start from its closed state so it animates open
    fan.classList.add('open'); col.classList.add('lift')
    main.setAttribute('aria-expanded', 'true')
    if (isLocked) nodes[S.mode].focus({ preventScroll: true })
  }
  function closeFan() {
    if (!fanOpen) return
    fanOpen = false; locked = false; highlighted = null
    sector.spoke?.classList.remove('on') // otherwise the highlight line is left floating after the fan closes
    fan.classList.remove('open'); col.classList.remove('lift')
    main.setAttribute('aria-expanded', 'false')
  }
  function choose(m) {
    closeFan()
    if (m !== S.mode) { setMode(m); toast(`${MODE_NAMES[m]} mode`) }
  }

  // ---- the three gestures ----
  const stopHold = () => { clearTimeout(holdTimer); main.classList.remove('charging') }
  async function openDetails() {
    const t = await lastTitle(S.mode)
    if (!t) return toast(`Nothing to open yet. Watch or read something in ${MODE_NAMES[S.mode]} first`, true)
    go(`/media/${t.mode}/${t.id}`)
  }
  async function tap() {
    const t = await lastTitle(S.mode)
    if (t) return go(continuePath(t))
    toast(`Nothing to continue in ${MODE_NAMES[S.mode]} yet, pick a mode`)
    openFan(true)
  }
  main.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return
    if (fanOpen && locked) return closeFan() // pressing the button again closes a fan opened by tap / right-click
    main.setPointerCapture(e.pointerId)
    down = { x: e.clientX, y: e.clientY }; pulled = false; held = false
    main.classList.add('charging'); main.classList.add('press')
    holdTimer = setTimeout(() => { if (!pulled) { held = true; stopHold(); openDetails() } }, HOLD_MS)
  })
  main.addEventListener('pointermove', (e) => {
    if (!down) return
    const dx = O.x - e.clientX, dy = O.y - e.clientY
    if (!pulled && Math.hypot(e.clientX - down.x, e.clientY - down.y) > PULL_PX) { pulled = true; stopHold(); openFan(false) }
    if (pulled) {
      const m = pickMode(dx, dy, NR, DEAD)
      if (m !== highlighted) { highlighted = m; light() }
    }
  })
  const finish = (e, cancelled) => {
    if (!down) return
    down = null; stopHold(); main.classList.remove('press')
    if (cancelled) return closeFan()
    if (pulled) { const m = highlighted; return m ? choose(m) : closeFan() }
    if (!held) tap()
  }
  main.addEventListener('pointerup', (e) => finish(e, false))
  main.addEventListener('pointercancel', (e) => finish(e, true))
  main.addEventListener('contextmenu', (e) => { e.preventDefault(); stopHold(); down = null; fanOpen ? closeFan() : openFan(true) })
  // Keyboard: Enter / Space continues, the arrow keys open the fan, Esc closes it.
  main.addEventListener('click', (e) => { if (e.detail === 0) tap() })
  main.addEventListener('keydown', (e) => { if (['ArrowUp', 'ArrowLeft', 'm', 'M'].includes(e.key)) { e.preventDefault(); openFan(true) } })
  fan.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.stopPropagation(); closeFan(); main.focus() }
    if (['ArrowUp', 'ArrowLeft', 'ArrowDown', 'ArrowRight'].includes(e.key)) {
      e.preventDefault()
      const i = MODES.indexOf(document.activeElement?.dataset?.mode), step = e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? 1 : -1
      const next = MODES[(Math.max(i, 0) + step + MODES.length) % MODES.length]
      highlighted = next; nodes[next].focus(); light()
    }
  })
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && fanOpen) closeFan() })
  addEventListener('resize', () => fanOpen && (layout(), light()))

  // ---- keep it in step with the app ----
  const sync = () => {
    glyph.replaceChildren(icon(ICON[S.mode], 28))
    glyph.classList.remove('swap'); void glyph.offsetWidth; glyph.classList.add('swap')
    const tipText = `${MODE_NAMES[S.mode]} · tap to continue, drag to switch mode, hold for details`
    tip.textContent = tipText
    main.setAttribute('aria-label', `Switch mode (now ${MODE_NAMES[S.mode]}). Tap to continue, hold for details`)
    if (fanOpen) light()
  }
  const visibility = () => root.classList.toggle('away', overlayCount() > 0) // hidden under players, readers and sheets
  window.addEventListener('ds:mode', sync)
  window.addEventListener('ds:overlay', visibility)
  // On a title's page the big Continue / Watch button floats in this spot instead, so the mode button steps aside.
  const place = () => root.classList.toggle('gone', location.hash.startsWith('#/media/'))
  window.addEventListener('ds:route', () => (closeFan(), place()))
  window.addEventListener('hashchange', place)
  sync(); visibility(); place()
}
