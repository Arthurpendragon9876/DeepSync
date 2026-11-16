import { h, icon, S, api, go, sheet, toast, segmented, clear } from '../core.js'
import { itemCard } from '../ext-ui.js'
import { isSrcId, srcRefOf } from '../srcmedia.js'

const MODES = [['all', 'All'], ['anime', 'Anime'], ['manga', 'Manga'], ['novel', 'Novels']]

// /favourites  Titles you hearted, sorted into categories (Yugen's Favourites screen).
export async function favourites() {
  let d = await api('fav:list')
  let cat = d.categories[0].id, mode = 'all'
  const tabs = h('div', { class: 'tabs', style: { marginBottom: '16px' } })
  const grid = h('div', { class: 'grid' })
  const draw = () => {
    if (!d.categories.some((c) => c.id === cat)) cat = d.categories[0].id
    const count = (id) => d.items.filter((i) => i.cats.includes(id) && (mode === 'all' || i.mode === mode)).length
    tabs.replaceChildren(...d.categories.map((c) => h('div', { class: 'tab' + (c.id === cat ? ' on' : ''), onclick: () => ((cat = c.id), draw()) }, `${c.name} (${count(c.id)})`)))
    const items = d.items.filter((i) => i.cats.includes(cat) && (mode === 'all' || i.mode === mode)).sort((a, b) => b.at - a.at)
    clear(grid)
    if (!items.length) return grid.replaceChildren(h('div', { class: 'empty', style: { gridColumn: '1/-1' } }, h('h3', null, 'Nothing here yet'), h('p', null, 'Tap the heart on a title’s page to add it to this category.')))
    grid.append(...items.map((i) => itemCard(i, () => go(`/media/${i.mode}/${i.id}`))))
  }
  const manage = () => {
    let cats = d.categories.map((c) => ({ ...c }))
    const list = h('div')
    const drawList = () => list.replaceChildren(...cats.map((c, k) => h('div', { class: 's-row' },
      h('input', { class: 'input', value: c.name, style: { maxWidth: '260px' }, oninput: (e) => (c.name = e.target.value) }),
      h('button', { class: 'btn danger', disabled: cats.length < 2, title: 'Delete category', onclick: () => (cats.splice(k, 1), drawList()) }, icon('trash', 18)))))
    drawList()
    const close = sheet({ title: 'Categories', body: h('div', null, list,
      h('div', { style: { display: 'flex', gap: '8px', marginTop: '14px' } },
        h('button', { class: 'btn', onclick: () => (cats.push({ id: 'c' + Date.now().toString(36), name: 'New category' }), drawList()) }, icon('plus', 18), 'Add category'),
        h('button', { class: 'btn primary', onclick: async () => { try { await api('fav:categories', { categories: cats }); d = await api('fav:list'); close(); draw() } catch (e) { toast(e.message, true) } } }, 'Save'))) })
  }
  draw()
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, 'Favourites', h('button', { class: 'btn', style: { marginLeft: 'auto' }, onclick: manage }, icon('gear', 18), 'Categories')),
    h('div', { class: 'filters', style: { marginBottom: '14px' } }, segmented(MODES, mode, (m) => ((mode = m), draw()))),
    tabs, grid)
}
