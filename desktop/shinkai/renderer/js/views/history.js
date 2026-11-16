import { h, icon, S, api, go, toast, ago } from '../core.js'
import { srcRefOf, isSrcId } from '../srcmedia.js'

const dayLabel = (t) => {
  const d = new Date(t), n = new Date(), mid = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((mid(n) - mid(d)) / 86400000)
  return diff === 0 ? 'Today' : diff === 1 ? 'Yesterday' : d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
}

// /history  What you last read or watched, one row per title, grouped by day.
export async function history() {
  let items = await api('history:list')
  const body = h('div')
  const draw = () => {
    if (!items.length) return body.replaceChildren(h('div', { class: 'empty' }, h('h3', null, 'No history yet'), h('p', null, S.settings.incognito ? 'Incognito is on, so nothing is being recorded.' : 'Chapters you read and episodes you watch from a source show up here.')))
    const groups = new Map()
    for (const e of items) { const k = dayLabel(e.at); (groups.get(k) || groups.set(k, []).get(k)).push(e) }
    body.replaceChildren(...[...groups.entries()].map(([day, list]) => h('div', null, h('h3', { class: 'h3' }, day),
      list.map((e) => h('div', { class: 'ep-row', onclick: () => go(`/media/${e.mode}/${e.id}`) },
        h('div', { class: 'th', style: { width: '56px', aspectRatio: '2/3', overflow: 'hidden' } }, e.cover && h('img', { src: e.cover, alt: '', style: { width: '100%', height: '100%', objectFit: 'cover' } })),
        h('div', { class: 'tx' }, h('b', null, e.title), h('span', null, [e.label || (e.number != null ? `${e.mode === 'anime' ? 'Episode' : 'Chapter'} ${e.number}` : ''), { anime: 'Anime', manga: 'Manga', novel: 'Novel' }[e.mode], new Date(e.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })].filter(Boolean).join(' · '))),
        h('div', { class: 'st' }, h('button', { class: 'ibtn', title: 'Remove from history', onclick: async (ev) => { ev.stopPropagation(); await api('history:remove', { key: e.key }); items = items.filter((x) => x.key !== e.key); draw() } }, icon('trash', 18))))))))
  }
  draw()
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, 'History', items.length ? h('button', { class: 'btn danger', style: { marginLeft: 'auto' }, onclick: async () => { if (!confirm('Clear all history?')) return; await api('history:clear'); items = []; draw(); toast('History cleared') } }, icon('trash', 18), 'Clear all') : null),
    body)
}
