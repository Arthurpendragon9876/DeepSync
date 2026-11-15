import { h, icon, api, go, errorBox, segmented, clear } from '../core.js'
import { itemCard, srcLabel } from '../ext-ui.js'
import { openSourceTitle } from '../srcmedia.js'

// /source/:id  Browse one installed extension source: popular, latest, search.
export async function source({ params }) {
  let all
  try { all = await api('ext:installed') } catch (e) { return h('div', { class: 'page' }, errorBox(e)) }
  const src = all.find((s) => s.id === params.id)
  if (!src) return h('div', { class: 'page' }, h('div', { class: 'empty' }, h('h3', null, 'Source not found'), h('p', null, 'It may have been uninstalled.'), h('button', { class: 'btn', onclick: () => go('/extensions') }, 'Back to Extensions')))
  const siblings = all.filter((s) => s.pkgName === src.pkgName)
  const st = { kind: 'popular', query: '', page: 1 }
  const grid = h('div', { class: 'grid' })
  const more = h('div', { style: { textAlign: 'center', margin: '26px 0' } })
  const note = h('div', { class: 'note', style: { marginBottom: '14px' } })
  const err = h('div')

  async function run(reset) {
    if (reset) { st.page = 1; clear(grid) }
    note.textContent = 'Loading…'
    err.replaceChildren()
    try {
      const r = await api('ext:browse', { sourceId: src.id, kind: st.query ? 'search' : st.kind, query: st.query, page: st.page })
      grid.append(...r.list.map((it) => itemCard(it, () => openSourceTitle(src, it))))
      note.textContent = grid.children.length ? '' : 'No results.'
      more.replaceChildren(r.hasNextPage ? h('button', { class: 'btn', onclick: () => (st.page++, run(false)) }, 'Load more') : '')
    } catch (e) { note.textContent = ''; more.replaceChildren(); err.replaceChildren(errorBox(e, () => run(true))) }
  }

  const q = h('input', { class: 'input', placeholder: `Search ${src.name}…`, style: { maxWidth: '320px' } })
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { st.query = q.value.trim(); run(true) } })
  const tabs = segmented([['popular', 'Popular'], ...(src.supportsLatest ? [['latest', 'Latest']] : [])], 'popular', (k) => ((st.kind = k), (st.query = ''), (q.value = ''), run(true)))
  const lang = siblings.length > 1 && h('select', { class: 'input', 'aria-label': 'Language', style: { maxWidth: '200px' }, onchange: (e) => go('/source/' + e.target.value) },
    siblings.map((s) => h('option', { value: s.id, selected: s.id === src.id }, s.lang)))
  run(true)
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, srcLabel(src), h('button', { class: 'btn', style: { marginLeft: 'auto' }, onclick: () => go('/extensions') }, icon('puzzle', 18), 'Extensions')),
    h('div', { class: 'filters', style: { marginBottom: '14px' } }, tabs, q, h('button', { class: 'btn', onclick: () => ((st.query = q.value.trim()), run(true)) }, icon('search', 18)), lang),
    note, err, grid, more)
}
