import { h, api } from '../core.js'

const card = (label, value, sub) => h('div', { class: 'card', style: { flex: '1 1 160px' } }, h('div', { class: 'note' }, label), h('div', { style: { fontSize: '30px', fontWeight: 800, margin: '4px 0' } }, String(value)), sub ? h('div', { class: 'note' }, sub) : null)

// /stats  Chapters read and episodes watched, from the history this app keeps on this device.
export async function stats() {
  const d = await api('stats:get')
  const days = Array.from({ length: 30 }, (_, i) => { const t = new Date(Date.now() - (29 - i) * 86400000); const k = t.toISOString().slice(0, 10); return { k, n: d.byDay[k] || 0, t } })
  const max = Math.max(1, ...days.map((x) => x.n))
  const modes = [['anime', 'Episodes watched'], ['manga', 'Manga chapters read'], ['novel', 'Novel chapters read']]
  const mTotal = Math.max(1, d.byMode.anime + d.byMode.manga + d.byMode.novel)
  return h('div', { class: 'page' }, h('h1', { class: 'page-title' }, 'Stats'),
    h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '14px' } }, card('Finished', d.total, 'chapters and episodes'), card('Streak', `${d.streak} day${d.streak === 1 ? '' : 's'}`, 'in a row'), card('Titles', d.titles, 'in your history'), card('Favourites', d.favourites)),
    h('div', { class: 'card', style: { marginTop: '14px' } }, h('div', { class: 'h3', style: { marginTop: 0 } }, 'Last 30 days'),
      h('div', { style: { display: 'flex', alignItems: 'flex-end', gap: '4px', height: '120px' } }, days.map((x) => h('div', { title: `${x.t.toLocaleDateString()}: ${x.n}`, style: { flex: 1, height: Math.max(3, (x.n / max) * 100) + '%', borderRadius: '4px', background: x.n ? 'rgb(var(--accent))' : 'var(--surface-3)' } })))),
    h('div', { class: 'card', style: { marginTop: '14px' } }, h('div', { class: 'h3', style: { marginTop: 0 } }, 'By type'),
      modes.map(([k, l]) => h('div', { style: { margin: '10px 0' } }, h('div', { style: { display: 'flex', justifyContent: 'space-between', fontSize: '13px' } }, h('span', null, l), h('b', null, d.byMode[k])), h('div', { style: { height: '8px', borderRadius: '99px', background: 'var(--surface-3)', overflow: 'hidden', marginTop: '6px' } }, h('i', { style: { display: 'block', height: '100%', width: (d.byMode[k] / mTotal) * 100 + '%', background: 'rgb(var(--accent))' } }))))),
    d.top.length ? h('div', { class: 'card', style: { marginTop: '14px' } }, h('div', { class: 'h3', style: { marginTop: 0 } }, 'Most read / watched'), d.top.map((t, i) => h('div', { class: 's-row' }, h('span', null, `${i + 1}. ${t.title}`), h('b', null, t.count || 0)))) : null)
}
