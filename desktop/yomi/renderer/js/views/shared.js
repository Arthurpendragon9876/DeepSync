// Pieces shared by the two details layouts (classic + YouTube-style anime page).
import { h, icon, norm, pretty, titleOf } from '../core.js'

export function findLocal(lib, media, mode) {
  const items = lib?.[mode] || []
  const linked = Object.entries(lib?.links || {}).filter(([, v]) => v === media.id).map(([k]) => k)
  const names = new Set([media.title.romaji, media.title.english, media.title.native, media.title.userPreferred, ...(media.synonyms || [])].filter(Boolean).map(norm))
  return items.find((x) => linked.includes(x.key)) || items.find((x) => names.has(norm(x.title)))
}
export const countdown = (secs) => {
  const d = Math.floor(secs / 86400), hr = Math.floor((secs % 86400) / 3600), m = Math.floor((secs % 3600) / 60)
  return d ? `${d}d ${hr}h` : hr ? `${hr}h ${m}m` : `${m}m`
}
export const mins = (m) => (m >= 60 ? `${Math.floor(m / 60)} h ${Math.round(m % 60)} m` : `${Math.max(1, Math.round(m))} m`)
export const when = (ts) => (ts > 0 ? new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '')

// Wavy progress bar (the Android details page uses an animated wave for the finished part).
export function waveBar(pct) {
  const W = 600, end = (W * Math.min(100, Math.max(0, pct))) / 100
  const el = h('div', { class: 'wave', html: `<svg viewBox="0 0 ${W} 16" preserveAspectRatio="none"><path class="w-rest" d="M${end} 8 L${W} 8"/><path class="w-done"/></svg>` })
  const path = el.querySelector('.w-done')
  const draw = (phase) => { let d = 'M0 8'; for (let x = 0; x <= end; x += 3) d += ` L${x} ${(8 + Math.sin((x / 26) * Math.PI - phase) * 3.4).toFixed(2)}`; path.setAttribute('d', d) }
  draw(0)
  // flowing wave; stops by itself once the bar has left the page, and stays still for people who prefer reduced motion
  if (!matchMedia('(prefers-reduced-motion: reduce)').matches) {
    let miss = 0
    const tick = (ms) => { if (!el.isConnected && ++miss > 30) return; draw(ms / 260); requestAnimationFrame(tick) }
    requestAnimationFrame(tick)
  }
  return el
}

export function infoGrid(media, mode, total) {
  const rows = [
    media.seasonYear && ['Season', `${pretty(media.season)} ${media.seasonYear}`.trim()], ['Status', pretty(media.status)], ['Format', pretty(media.format)],
    media.source && ['Source', pretty(media.source)], media.averageScore && ['Score', `★ ${(media.averageScore / 10).toFixed(1)}`], media.popularity && ['Popularity', media.popularity.toLocaleString()],
    media.duration && ['Duration', media.duration + ' min'], media.studios?.nodes?.[0] && ['Studio', media.studios.nodes[0].name], [mode === 'anime' ? 'Episodes' : 'Chapters', total || '?'],
  ].filter(Boolean)
  return h('div', { class: 'card info-grid' }, rows.map(([k, v]) => h('div', null, h('span', null, k), h('b', null, String(v)))))
}

export function altTitles(media) {
  const t = media.title || {}
  const rows = [['Main title', titleOf(media)], ['Romaji', t.romaji], ['English', t.english], ['Native', t.native], ...(media.synonyms || []).map((s) => ['Synonym', s])]
  const seen = new Set()
  const uniq = rows.filter(([, v]) => v && !(seen.has(v) ? true : (seen.add(v), false)))
  return uniq.length > 1 ? h('div', { class: 'card' }, h('div', { class: 'h3', style: { marginTop: 0 } }, 'Alternative titles'), h('dl', { class: 'kv alt-kv' }, uniq.flatMap(([k, v]) => [h('dt', null, k), h('dd', null, v)]))) : null
}

// Shown in place of the chapter / episode list while the source is not ready.
export function sourceState(session, { onRetry, goExt, openPicker }) {
  const box = (head, text, ...btns) => h('div', { class: 'empty small' }, h('h3', null, head), h('p', null, text), h('div', { style: { display: 'flex', gap: '8px', justifyContent: 'center' } }, ...btns))
  const s = session
  if (s.status === 'resolving' || s.status === 'loading') return h('div', null, h('div', { class: 'skeleton', style: { height: '84px', marginBottom: '8px' } }), h('div', { class: 'skeleton', style: { height: '84px' } }), h('p', { class: 'note', style: { textAlign: 'center', marginTop: '10px' } }, s.status === 'loading' ? `Loading from ${s.label()}…` : 'Finding it in your sources…'))
  if (s.status === 'nosources') return box('No source installed', `Install a ${s.type} extension and this title links itself automatically.`, h('button', { class: 'btn primary', onclick: goExt }, icon('puzzle', 18), 'Open Extensions'))
  if (s.status === 'noruntime') return box('Set up extensions', s.error || 'The extension runtime is not installed yet.', h('button', { class: 'btn primary', onclick: goExt }, icon('puzzle', 18), 'Set up'))
  if (s.status === 'nomatch') return box('No confident match', s.failed?.length ? `${s.failed.length} source(s) could not be searched.` : 'Pick the matching series once and it is remembered.', h('button', { class: 'btn primary', onclick: openPicker }, icon('link', 18), 'Link a source'))
  if (s.status === 'empty') return box('Nothing listed', `${s.label()} has no chapters or episodes for this series.`, h('button', { class: 'btn', onclick: openPicker }, 'Try another source'))
  if (s.status === 'error') return box('Could not load', s.error, h('button', { class: 'btn', onclick: onRetry }, 'Retry'), h('button', { class: 'btn', onclick: openPicker }, 'Change source'))
  return null
}
