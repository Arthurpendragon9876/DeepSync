// Quick-settings panels for the manga and novel readers (the desktop version of the Android app's reader sheets).
// Preferences are remembered per title, like "The chosen configuration will be remembered for this manga".
import { h, icon, segmented, switchEl } from './core.js'
import { skipDuplicates, setSkipDuplicates } from './prefs.js'

const read = (k) => { try { return JSON.parse(localStorage.getItem(k) || '{}') } catch { return {} } }
const write = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)) } catch {} }
const slug = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 60)

export const MANGA_DEFAULTS = { mode: 'ltr', width: 820, gap: 0, fit: 'screen', bg: '#0b0b0d', bright: 100, contrast: 100, invert: false, gray: false, sepia: 0, autoSpeed: 4, tapInvert: false, counter: true, zoom: 100, double: true, coverAlone: true, dpOrder: 'rtl', dpAsked: false, anim: 'none', awake: true, eye: 0, autoNext: true, autoMode: true }
export const NOVEL_DEFAULTS = { size: 19, lh: 1.75, para: 0.9, theme: 'dark', font: 'serif', width: 720, zoom: 100, align: 'left', bionic: false, autoSpeed: 3, rate: 1, pitch: 1, voice: '' }

// The reading mode is not shared between titles (each title gets its own, detected on first open); the default mode comes from Settings.
export const mangaPrefs = (title) => { const g = { ...read('ds.reader') }; delete g.mode; const d = read('ds.readerDefault'); return { ...MANGA_DEFAULTS, ...g, ...(d.mode ? { mode: d.mode } : {}), ...read('ds.reader:' + slug(title)), zoom: 100 } } // zoom is never remembered: every chapter opens at 100 %
export const hasTitlePrefs = (title) => { try { return localStorage.getItem('ds.reader:' + slug(title)) != null } catch { return false } }
// Per-title copy, plus the keys that carry over to the next title you open (colour correction stays per title).
export const GLOBAL_MANGA = ['autoMode', 'width', 'gap', 'fit', 'bg', 'double', 'coverAlone', 'dpOrder', 'dpAsked', 'anim', 'awake', 'eye', 'autoNext', 'counter', 'tapInvert', 'autoSpeed']
export const saveMangaPrefs = (title, p) => { const { zoom, ...keep } = p; write('ds.reader:' + slug(title), keep); write('ds.reader', Object.fromEntries(GLOBAL_MANGA.map((k) => [k, p[k]]))) }
export const setGlobalManga = (patch) => { const { mode, ...rest } = patch; if (mode) write('ds.readerDefault', { mode }); if (Object.keys(rest).length) write('ds.reader', { ...read('ds.reader'), ...rest }) }
export const GLOBAL_NOVEL = ['theme', 'font', 'size', 'lh', 'para', 'width', 'zoom', 'align', 'bionic']
export const setGlobalNovel = (patch) => write('ds.novel', { ...read('ds.novel'), ...patch })
export const novelPrefs = (title) => ({ ...NOVEL_DEFAULTS, ...read('ds.novel'), ...read('ds.novel:' + slug(title)) })
export const saveNovelPrefs = (title, p) => { write('ds.novel:' + slug(title), p); write('ds.novel', Object.fromEntries(GLOBAL_NOVEL.map((k) => [k, p[k]]))) }

// ---- small building blocks ----------------------------------------------------------------------------------------------------
const tile = (ic, label, on, onclick) => h('button', { class: 'qs-tile' + (on ? ' on' : ''), onclick }, icon(ic, 22), h('span', null, label))
const card = (ic, title, sub, onclick, on) => h('button', { class: 'qs-card' + (on ? ' on' : ''), onclick }, h('span', { class: 'qs-ic' }, icon(ic, 20)), h('b', null, title), h('span', null, sub))
const slider = (label, min, max, step, value, fmt, onchange) => {
  const out = h('em', null, fmt(value))
  const r = h('input', { type: 'range', min, max, step, value, oninput: () => { out.textContent = fmt(+r.value); onchange(+r.value) } })
  return h('label', { class: 'qs-row' }, h('div', { class: 'qs-lbl' }, h('span', null, label), out), r)
}
const toggle = (label, sub, on, onchange) => h('div', { class: 'qs-row qs-switch' }, h('div', null, h('b', null, label), sub ? h('span', null, sub) : null), switchEl(on, onchange))
const group = (title, ...kids) => h('div', { class: 'qs-group' }, title ? h('div', { class: 'qs-h' }, title) : null, ...kids)

function shell(tabs, start, onClose) {
  const body = h('div', { class: 'qs-body' })
  const nav = h('div', { class: 'qs-tabs' })
  const root = h('div', { class: 'qs', onmousedown: (e) => e.stopPropagation() }, h('div', { class: 'qs-grab' }), body, nav)
  let cur = start
  const show = (k) => {
    cur = k
    body.replaceChildren(tabs[k].render())
    nav.replaceChildren(...Object.entries(tabs).map(([id, t]) => h('button', { class: id === cur ? 'on' : '', onclick: () => show(id) }, icon(t.icon, 18), t.label)))
  }
  show(cur)
  root.refresh = () => show(cur)
  root.append(h('button', { class: 'ibtn qs-x', 'aria-label': 'Close', onclick: onClose }, icon('x', 18)))
  return root
}

// ---- manga ---------------------------------------------------------------------------------------------------------------------------
/**
 * @param prefs   live preference object (mutated by update)
 * @param update  (patch) => void   applies + persists
 * @param act     { save, fullscreen, toggleAuto, isAuto, jump, chapters(): [{label}], cur(), page(), pages(), goPage, prev, next, bookmark, reset }
 */
export function mangaQuickSettings({ prefs, update, act, onClose }) {
  const tabs = {
    reading: { label: 'Reading', icon: 'book', render: () => h('div', null,
      h('div', { class: 'qs-modes' }, [['ltr', 'Standard', 'book'], ['rtl', 'R-to-L', 'rtl'], ['vertical', 'Vertical', 'layers'], ['webtoon', 'Webtoon', 'menu']].map(([m, l, ic]) => tile(ic, l, prefs.mode === m, () => (update({ mode: m }), root.refresh())))),
      h('p', { class: 'qs-note' }, 'The chosen configuration will be remembered for this manga'),
      h('div', { class: 'qs-grid' },
        card('clock', 'Automatic scroll', act.isAuto() ? 'Running · tap to stop' : `Speed ${prefs.autoSpeed}`, () => (act.toggleAuto(), root.refresh()), act.isAuto()),
        card('download', 'Save page', 'Export current page', act.save),
        card('fullscreen', 'Fullscreen', 'Toggle window fullscreen', act.fullscreen),
        card('sliders', 'Color correction', 'Brightness, contrast, invert', () => root.show('color'))),
      slider('Auto scroll speed', 1, 10, 1, prefs.autoSpeed, (v) => v, (v) => update({ autoSpeed: v })),
      group('Fit (paged modes)', segmented([['screen', 'Fit screen'], ['width', 'Fit width'], ['height', 'Fit height']], prefs.fit, (v) => update({ fit: v }))),
      slider('Zoom', 50, 300, 10, prefs.zoom, (v) => v + '%', (v) => update({ zoom: v })),
      h('button', { class: 'qs-wide', onclick: () => root.show('advanced') }, icon('gear', 20), h('div', null, h('b', null, 'Advanced reader options'), h('span', null, 'Gaps, fit, width, gestures & background')), icon('chevR', 18))) },
    more: { label: 'More', icon: 'grid', render: () => {
      const chs = act.chapters()
      const sel = h('select', { class: 'input', onchange: () => act.jump(+sel.value) }, chs.map((c, i) => h('option', { value: i, selected: i === act.cur() }, c.label)))
      return h('div', null,
        group('Chapter', sel, h('div', { class: 'qs-pair' }, h('button', { class: 'btn', onclick: act.prev }, icon('skipB', 16), 'Previous'), h('button', { class: 'btn', onclick: act.next }, 'Next', icon('skipF', 16)))),
        group('Page', slider(`Page ${act.page() + 1} of ${act.pages()}`, 1, Math.max(1, act.pages()), 1, act.page() + 1, (v) => v, (v) => act.goPage(v - 1))),
        group(null, card('star', 'Bookmark this page', 'Saved on this computer', act.bookmark)))
    } },
    extra: { label: 'Extra', icon: 'sliders', render: () => h('div', null,
      toggle('Double pages', 'Two pages side by side in Standard and R-to-L modes', prefs.double, (v) => update({ double: v })),
      toggle('Cover on its own', 'With double pages, show the first page alone', prefs.coverAlone, (v) => update({ coverAlone: v })),
      group('Double page order', segmented([['rtl', 'Right to left (manga)'], ['ltr', 'Left to right']], prefs.dpOrder, (v) => update({ dpOrder: v }))),
      h('p', { class: 'qs-note' }, 'Only the order of the two pages changes. Arrow keys and tap zones keep going left = back, right = forward.'),
      group('Page transition (paged modes)', segmented([['none', 'None'], ['fade', 'Fade'], ['slide', 'Slide']], prefs.anim, (v) => update({ anim: v }))),
      toggle('Keep screen awake', 'Stops the screen from sleeping while you read', prefs.awake, (v) => update({ awake: v })),
      toggle('Next chapter automatically', 'Webtoon mode: continue into the next chapter at the end', prefs.autoNext, (v) => update({ autoNext: v })),
      group('Eye-care reminder', segmented([[0, 'Off'], [20, '20 min'], [45, '45 min'], [60, '60 min']], prefs.eye, (v) => update({ eye: v }))),
      toggle('Page counter', 'Show “3 / 40” at the bottom', prefs.counter, (v) => update({ counter: v })),
      toggle('Invert tap zones', 'Left edge goes forward (paged modes)', prefs.tapInvert, (v) => update({ tapInvert: v })),
      toggle('Skip duplicate chapters', 'Next / previous jumps over a chapter that another scanlation group also has', skipDuplicates(), setSkipDuplicates),
      group('Shortcuts', h('div', { class: 'qs-keys' }, [['← →', 'Previous / next page'], ['↑ ↓', 'Vertical mode'], ['N / P', 'Next / previous chapter'], ['A', 'Automatic scroll'], ['+ / − / 0', 'Zoom in / out / reset (or Ctrl + wheel)'], ['F', 'Fullscreen'], ['S', 'Quick settings'], ['Esc', 'Close']].map(([k, d]) => h('div', null, h('kbd', null, k), h('span', null, d))))),
      h('button', { class: 'btn danger', style: { marginTop: '14px' }, onclick: () => (act.reset(), root.refresh()) }, 'Reset this manga to defaults')) },
    color: { label: 'Color', icon: 'sliders', hidden: true, render: () => h('div', null,
      h('button', { class: 'qs-back', onclick: () => root.show('reading') }, icon('back', 18), 'Color correction'),
      slider('Brightness', 40, 160, 1, prefs.bright, (v) => v + '%', (v) => update({ bright: v })),
      slider('Contrast', 40, 180, 1, prefs.contrast, (v) => v + '%', (v) => update({ contrast: v })),
      slider('Sepia', 0, 100, 1, prefs.sepia, (v) => v + '%', (v) => update({ sepia: v })),
      toggle('Invert colors', null, prefs.invert, (v) => update({ invert: v })), toggle('Grayscale', null, prefs.gray, (v) => update({ gray: v })),
      h('button', { class: 'btn sm', onclick: () => (update({ bright: 100, contrast: 100, sepia: 0, invert: false, gray: false }), root.show('color')) }, 'Reset colors')) },
    advanced: { label: 'Advanced', icon: 'gear', hidden: true, render: () => h('div', null,
      h('button', { class: 'qs-back', onclick: () => root.show('reading') }, icon('back', 18), 'Advanced reader options'),
      slider('Webtoon width', 360, 1400, 20, prefs.width, (v) => v + ' px', (v) => update({ width: v })),
      slider('Gap between pages', 0, 48, 2, prefs.gap, (v) => v + ' px', (v) => update({ gap: v })),
      group('Fit (paged modes)', segmented([['screen', 'Screen'], ['width', 'Width'], ['height', 'Height']], prefs.fit, (v) => update({ fit: v }))),
      group('Background', segmented([['#0b0b0d', 'Black'], ['#1c1c22', 'Dark'], ['#ffffff', 'White']], prefs.bg, (v) => update({ bg: v })))) },
  }
  const root = shell(Object.fromEntries(Object.entries(tabs).map(([k, t]) => [k, t])), 'reading', onClose)
  // 'hidden' pages are reachable only from inside the panel; the bottom bar lists the three main ones.
  const nav = root.querySelector('.qs-tabs'), body = root.querySelector('.qs-body')
  const draw = (k) => { body.replaceChildren(tabs[k].render()); nav.replaceChildren(...['reading', 'more', 'extra'].map((id) => h('button', { class: id === k ? 'on' : '', onclick: () => draw(id) }, icon(tabs[id].icon, 18), tabs[id].label))); root.cur = k }
  root.show = draw; root.refresh = () => draw(root.cur || 'reading'); draw('reading')
  return root
}

// ---- novel ---------------------------------------------------------------------------------------------------------------------------
export const NOVEL_THEMES = { dark: ['#121216', '#dadae0', 'Dark'], sepia: ['#f4ecd8', '#3b3226', 'Sepia'], light: ['#fbfbfb', '#1c1c1f', 'Light'], amoled: ['#000', '#cfcfd6', 'AMOLED'], paper: ['#e8e4da', '#2a2a2a', 'Paper'] }
export const NOVEL_FONTS = { serif: "Georgia,'Noto Serif',serif", sans: "Inter,'Noto Sans',system-ui,sans-serif", mono: "'JetBrains Mono','DejaVu Sans Mono',monospace" }

export function novelQuickSettings({ prefs, update, act, onClose }) {
  const swatch = (k) => h('button', { class: 'qs-swatch' + (prefs.theme === k ? ' on' : ''), style: { background: NOVEL_THEMES[k][0], color: NOVEL_THEMES[k][1] }, title: NOVEL_THEMES[k][2], onclick: () => (update({ theme: k }), root.refresh()) }, 'Aa')
  const tabs = {
    text: { label: 'Text', icon: 'text', render: () => h('div', null,
      group('Theme', h('div', { class: 'qs-swatches' }, Object.keys(NOVEL_THEMES).map(swatch))),
      group('Font', segmented([['serif', 'Serif'], ['sans', 'Sans'], ['mono', 'Mono']], prefs.font, (v) => update({ font: v }))),
      slider('Font size', 14, 34, 1, prefs.size, (v) => v + ' px', (v) => update({ size: v })),
      slider('Line spacing', 1.2, 2.6, 0.05, prefs.lh, (v) => v.toFixed(2), (v) => update({ lh: v })),
      slider('Paragraph spacing', 0, 2.2, 0.1, prefs.para, (v) => v.toFixed(1) + ' em', (v) => update({ para: v })),
      slider('Text width', 400, 1400, 20, prefs.width, (v) => v + ' px', (v) => update({ width: v })),
      slider('Zoom', 60, 220, 5, prefs.zoom, (v) => v + '%', (v) => update({ zoom: v })),
      group('Alignment', segmented([['left', 'Left'], ['justify', 'Justify'], ['center', 'Center']], prefs.align, (v) => update({ align: v }))),
      toggle('Bionic reading', 'Bolds the start of each word to guide your eyes', prefs.bionic, (v) => update({ bionic: v })),
      h('button', { class: 'btn sm', style: { marginTop: '10px' }, onclick: () => (act.reset(), root.refresh()) }, 'Reset to defaults')) },
    scroll: { label: 'Scroll', icon: 'clock', render: () => h('div', null,
      card('clock', 'Automatic scroll', act.isAuto() ? 'Running · tap to stop' : 'Scroll the chapter hands-free', () => (act.toggleAuto(), root.refresh()), act.isAuto()),
      slider('Auto scroll speed', 1, 10, 1, prefs.autoSpeed, (v) => v, (v) => update({ autoSpeed: v })),
      group('Shortcuts', h('div', { class: 'qs-keys' }, [['← →', 'Previous / next chapter'], ['+ / − / 0', 'Zoom in / out / reset (or Ctrl + wheel)'], ['Space', 'Page down'], ['L', 'Listen'], ['S', 'Quick settings'], ['C', 'Chapters'], ['A', 'Automatic scroll']].map(([k, d]) => h('div', null, h('kbd', null, k), h('span', null, d)))))) },
  }
  const root = shell(tabs, 'text', onClose)
  const nav = root.querySelector('.qs-tabs'), body = root.querySelector('.qs-body')
  let cur = 'text'
  const draw = (k) => { cur = k; body.replaceChildren(tabs[k].render()); nav.replaceChildren(...Object.entries(tabs).map(([id, t]) => h('button', { class: id === k ? 'on' : '', onclick: () => draw(id) }, icon(t.icon, 18), t.label))) }
  root.refresh = () => draw(cur); draw('text')
  return root
}

// Voice / speed controls for the Listen mode.
export function listenBar({ prefs, update, act }) {
  const voices = h('select', { class: 'input', 'aria-label': 'Voice', style: { maxWidth: '220px', height: '34px' }, onchange: (e) => update({ voice: e.target.value }) })
  const fill = () => { const vs = speechSynthesis.getVoices(); voices.replaceChildren(h('option', { value: '' }, 'Default voice'), ...vs.map((v) => h('option', { value: v.name, selected: v.name === prefs.voice }, `${v.name} (${v.lang})`))) }
  fill(); speechSynthesis.onvoiceschanged = fill
  const play = h('button', { class: 'ibtn', onclick: () => act.toggle() }, icon('play', 20))
  const bar = h('div', { class: 'listen' },
    h('button', { class: 'ibtn', title: 'Previous paragraph', onclick: () => act.skip(-1) }, icon('skipB', 18)), play,
    h('button', { class: 'ibtn', title: 'Next paragraph', onclick: () => act.skip(1) }, icon('skipF', 18)),
    voices, h('label', { class: 'note' }, 'Speed'), h('input', { type: 'range', min: 0.6, max: 2.2, step: 0.1, value: prefs.rate, oninput: (e) => update({ rate: +e.target.value }) }),
    h('button', { class: 'ibtn', title: 'Stop', onclick: () => act.stop() }, icon('x', 18)))
  bar.setPlaying = (p) => play.replaceChildren(icon(p ? 'pause' : 'play', 20))
  return bar
}
