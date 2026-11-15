// One source "session" per details page, the desktop version of how the Android app behaves: the title is already linked to a
// source when the page opens. The saved link is used first; otherwise the installed sources are searched silently, the best
// confident match is saved, and the chapter / episode list loads in place. The user can change the source or the series at any time.
import { api, titleOf, h, icon, sheet, go, toast } from './core.js'
import { installedSources, resolveSource, srcLabel, linkKey, autoKey, setLink, nextIndex, openAt } from './ext-ui.js'
import { groupPreset, groupPrefs, setGroupPrefs, hasGroupPrefs, skipDuplicates } from './prefs.js'

const gname = (c) => c.scanlator || 'official'
const isOfficial = (n) => /^official$/i.test(n)

export function createSession({ media, mode }) {
  const type = mode === 'anime' ? 'anime' : mode === 'novel' ? 'novel' : 'manga'
  const s = { status: 'resolving', type, sources: [], source: null, url: null, auto: false, score: 0, chapters: [], raw: [], detail: null, error: '', failed: [], listeners: new Set() }
  const emit = () => s.listeners.forEach((f) => f(s))
  s.on = (f) => (s.listeners.add(f), () => s.listeners.delete(f))
  const stateOf = (status, extra = {}) => (Object.assign(s, { status }, extra), emit())

  // s.raw is everything the source returned; s.chapters is what the app uses everywhere (list, counts, progress, reader next/prev):
  // groups switched off are dropped and, unless duplicates are wanted, each chapter number is kept once, from the preferred group.
  const pk = `${mode}.${media.id}`
  s.apply = () => {
    if (type !== 'manga') return void (s.chapters = s.raw)
    const p = groupPrefs(pk), preset = groupPreset(), off = new Set(p.off || [])
    let on = s.raw.filter((c) => !off.has(gname(c)))
    if (!on.length) on = s.raw
    if (!skipDuplicates()) return void (s.chapters = on)
    // group priority: the preset's pick first (official / unofficial), otherwise (and after it) the group with the most chapters
    const counts = s.groups()
    const order = [...counts.keys()].sort((a, b) => (preset === 'most' ? 0 : (isOfficial(b) === (preset === 'official')) - (isOfficial(a) === (preset === 'official'))) || counts.get(b) - counts.get(a))
    const rank = (c) => order.indexOf(gname(c))
    const best = new Map()
    for (const c of on) if (c.number != null && (!best.has(c.number) || rank(c) < rank(best.get(c.number)))) best.set(c.number, c)
    s.chapters = on.filter((c) => c.number == null || best.get(c.number) === c)
  }
  s.groups = () => { const m = new Map(); for (const c of s.raw) m.set(gname(c), (m.get(gname(c)) || 0) + 1); return m }
  s.isOff = (n) => (groupPrefs(pk).off || []).includes(n)
  s.customGroups = () => hasGroupPrefs(pk)
  const save = (patch) => { setGroupPrefs(pk, { ...groupPrefs(pk), ...patch }); s.apply(); emit() }
  s.toggleGroup = (n) => {
    const off = new Set(groupPrefs(pk).off || [])
    if (off.has(n)) off.delete(n)
    else { if (s.groups().size - off.size <= 1) return false; off.add(n) }
    save({ off: [...off] }); return true
  }
  s.resetGroups = () => { setGroupPrefs(pk, null); s.apply(); emit() }
  s.latest = () => s.chapters.reduce((m, c) => (c.number != null && c.number > m ? c.number : m), 0)

  async function loadChapters() {
    stateOf('loading')
    try {
      const d = await api('ext:detail', { sourceId: s.source.id, media: { title: titleOf(media), url: s.url, cover: '' } })
      s.raw = d.chapters || []; s.apply()
      stateOf(s.raw.length ? 'ready' : 'empty', { detail: d })
    } catch (e) { stateOf('error', { error: e.message }) }
  }
  s.resolve = async (only) => {
    stateOf('resolving', { chapters: [], raw: [], detail: null })
    try {
      const r = await resolveSource({ media, mode, sources: s.sources, only })
      if (!r || r.none) return stateOf('nomatch', { source: only || s.source || s.sources[0], failed: r?.failed || [] })
      Object.assign(s, { source: r.source, url: r.url, auto: !!r.auto, score: r.score || 0 })
      await loadChapters()
    } catch (e) { stateOf('error', { error: e.message }) }
  }
  s.init = async () => {
    // Novels: online sources (LNReader / Mangayomi) when any are installed, otherwise the local EPUB files.
    try { s.sources = await installedSources(type) } catch (e) { return mode === 'novel' ? stateOf('local') : stateOf(e.code === 'NO_RUNTIME' ? 'noruntime' : 'error', { error: e.message }) }
    if (!s.sources.length) return mode === 'novel' ? stateOf('local') : stateOf('nosources')
    // Opened straight from a source: that source and title are the link.
    if (media.srcRef) {
      const src = s.sources.find((x) => x.id === media.srcRef.s)
      if (src) { await setLink(linkKey(src.id, media.id), media.srcRef.u); return s.resolve(src) }
    }
    return s.resolve()
  }
  s.link = async (item, src = s.source) => { await setLink(linkKey(src.id, media.id), item.url); await setLink(autoKey(media.id), src.id); Object.assign(s, { source: src, url: item.url, auto: false }); await loadChapters() }
  s.unlink = async () => { if (s.source) await setLink(linkKey(s.source.id, media.id), null); await setLink(autoKey(media.id), null); stateOf('nomatch', { chapters: [], raw: [], detail: null }) }
  s.next = (progress) => nextIndex(s.chapters, progress)
  s.open = (index, onProgress, page) => openAt({ source: s.source, chapters: s.chapters, index, page, title: titleOf(media), media, mode, onProgress })
  s.label = () => (s.source ? srcLabel(s.source) : '')
  return s
}

// Source chip shown next to the status chip in the header ("Ongoing · Asura Scans" on Android).
export function sourceChip(session, media) {
  const el = h('button', { class: 'src-chip', onclick: () => openSourcePicker(session, media) })
  const draw = (s) => {
    const dot = h('i', { class: 'src-dot ' + (s.status === 'ready' ? 'ok' : s.status === 'error' || s.status === 'nomatch' ? 'bad' : s.status === 'local' ? '' : 'busy') })
    const text = { resolving: 'Finding source…', loading: s.label() || 'Loading…', ready: s.label(), empty: s.label() + ' · no chapters', nomatch: 'Link a source', error: 'Source error', nosources: 'No source', noruntime: 'Set up sources', local: 'Local files' }[s.status] || ''
    el.replaceChildren(dot, h('span', null, text), icon('chevD', 16))
    el.title = s.url ? `${s.label()} · ${s.auto ? 'matched automatically' : 'linked by you'}` : text
  }
  session.on(draw); draw(session)
  return el
}

// Change source / change series / unlink. Opened from the chip.
export function openSourcePicker(session, media) {
  if (session.status === 'nosources' || session.status === 'noruntime') { go('/extensions'); return toast('Install a source to link this title') }
  if (session.status === 'local') return
  const body = h('div')
  let close
  const q = h('input', { class: 'input', value: titleOf(media), placeholder: 'Search the selected source…' })
  const results = h('div', { style: { marginTop: '10px' } })
  let target = session.source || session.sources[0]
  const search = async () => {
    results.replaceChildren(h('div', { class: 'skeleton', style: { height: '90px' } }))
    try {
      const r = await api('ext:browse', { sourceId: target.id, kind: 'search', query: q.value.trim() })
      results.replaceChildren(...(r.list.length ? r.list.slice(0, 20).map((it) => h('div', { class: 'ep-row', onclick: async () => { close(); await session.link(it, target); toast('Linked to ' + it.title) } },
        h('div', { class: 'th', style: { width: '52px', aspectRatio: '2/3' } }, it.cover && h('img', { src: it.cover, alt: '' })), h('div', { class: 'tx' }, h('b', null, it.title)))) : [h('p', { class: 'note' }, 'No results. Try different spelling or another source.')]))
    } catch (e) { results.replaceChildren(h('p', { class: 'note' }, e.message)) }
  }
  q.addEventListener('keydown', (e) => e.key === 'Enter' && search())
  const list = h('div', { class: 'opt-list' }, session.sources.map((src) => h('div', { class: 'opt' + (session.source?.id === src.id ? ' on' : ''), onclick: () => { target = src; list.querySelectorAll('.opt').forEach((o, i) => o.classList.toggle('on', session.sources[i].id === src.id)); q.placeholder = `Search ${srcLabel(src)}…` } },
    icon('link', 18), h('span', { style: { flex: 1 } }, srcLabel(src)), session.source?.id === src.id && session.url ? h('span', { class: 'chip acc' }, 'Linked') : null)))
  body.append(
    session.url ? h('p', { class: 'note', style: { marginBottom: '12px' } }, session.auto ? `Matched automatically on ${session.label()}. Wrong series? Search below to relink.` : `Linked to ${session.label()}.`) : h('p', { class: 'note', style: { marginBottom: '12px' } }, 'No confident automatic match. Pick a source and the matching series.'),
    h('div', { class: 'h3', style: { marginTop: 0 } }, 'Source'), list,
    h('div', { style: { display: 'flex', gap: '8px', margin: '14px 0 0' } },
      h('button', { class: 'btn', onclick: () => { close(); session.resolve(target) } }, icon('sync', 16), 'Find automatically here'), h('button', { class: 'btn', onclick: () => { close(); go('/source/' + target.id) } }, 'Browse source')),
    h('div', { class: 'h3' }, 'Search for the series'), h('div', { style: { display: 'flex', gap: '8px' } }, q, h('button', { class: 'btn', onclick: search }, icon('search', 18))), results,
    session.url ? h('button', { class: 'btn danger sm', style: { marginTop: '18px' }, onclick: () => { close(); session.unlink() } }, 'Unlink this source') : null,
    h('button', { class: 'btn sm', style: { marginTop: '18px', marginLeft: session.url ? '8px' : 0 }, onclick: () => { close(); go('/extensions') } }, icon('puzzle', 16), 'Manage extensions'))
  close = sheet({ title: 'Source', body, side: 'center' })
}
