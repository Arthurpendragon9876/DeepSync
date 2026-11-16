import { h, icon, S, api, data, go, titleOf, coverOf, errorBox, segmented, setMode, STATUS, ago, fmtTime, modeOf, totalOf, norm } from '../core.js'
import { posterCard, mediaLink } from '../components.js'

const ORDER = ['CURRENT', 'PLANNING', 'COMPLETED', 'PAUSED', 'DROPPED']

export async function lists({ query }) {
  const mode = query.mode || S.mode
  const status = query.status || 'CURRENT'
  const page = h('div', { class: 'page' })
  const head = h('h1', { class: 'page-title' }, 'My lists', segmented([['anime', 'Anime'], ['manga', 'Manga'], ['novel', 'Novels']], mode, (m) => setMode(m)))
  const r = await api('anilist:list', { mode })
  if (r.needsLogin) return h('div', { class: 'page' }, head, h('div', { class: 'empty' }, h('h3', null, 'Sign in to see your lists'), h('p', null, 'Connect AniList to sync your watching and reading lists.'), h('button', { class: 'btn primary', onclick: () => go('/settings/trackers') }, 'Open tracker settings')))
  const entries = r.data
  const counts = Object.fromEntries(ORDER.map((s) => [s, entries.filter((e) => e.status === s).length]))
  const tabs = h('div', { class: 'tabs' }, ORDER.map((s) => h('div', { class: 'tab' + (s === status ? ' on' : ''), onclick: () => go(`/lists?mode=${mode}&status=${s}`) }, STATUS[s](mode), h('small', null, counts[s]))))
  const shown = entries.filter((e) => e.status === status).sort((a, b) => b.updatedAt - a.updatedAt)
  page.append(head, tabs, shown.length ? h('div', { class: 'grid' }, shown.map((e) => posterCard(e.media, { entry: e, sub: e.score ? `★ ${e.score}` : undefined }))) : h('div', { class: 'empty' }, h('h3', null, 'Nothing here'), h('p', null, 'This list is empty.')))
  return page
}

const startOfWeek = (offset) => {
  const d = new Date(); d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7) + offset * 7)
  return d
}

export async function schedule({ query }) {
  const off = parseInt(query.w || '0', 10)
  const start = startOfWeek(off)
  const end = new Date(start); end.setDate(end.getDate() + 7)
  const mineOnly = query.mine === '1'
  const [items, mineList] = await Promise.all([data('anilist:schedule', { from: Math.floor(start / 1000), to: Math.floor(end / 1000) }), data('anilist:list', { mode: 'anime' }).catch(() => [])])
  const mine = new Set((mineList || []).filter((e) => e.status === 'CURRENT' || e.status === 'PLANNING').map((e) => e.mediaId))
  const days = Array.from({ length: 7 }, (_, i) => ({ date: new Date(start.getTime() + i * 864e5), items: [] }))
  for (const it of items) {
    if (S.settings.hideAdult && it.media.isAdult) continue
    if (mineOnly && !mine.has(it.media.id)) continue
    const i = Math.floor((new Date(it.airingAt * 1000).setHours(0, 0, 0, 0) - start) / 864e5)
    if (days[Math.min(6, Math.max(0, Math.round(i)))]) days[Math.min(6, Math.max(0, Math.round(i)))].items.push(it)
  }
  const todayStr = new Date().toDateString()
  const range = `${start.toLocaleDateString([], { month: 'short', day: 'numeric' })} – ${new Date(end - 1).toLocaleDateString([], { month: 'short', day: 'numeric' })}`
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, 'Schedule', h('span', { class: 'chip' }, range),
      h('button', { class: 'ibtn', onclick: () => go(`/schedule?w=${off - 1}${mineOnly ? '&mine=1' : ''}`) }, icon('chevL')), h('button', { class: 'btn sm', onclick: () => go(`/schedule${mineOnly ? '?mine=1' : ''}`) }, 'This week'),
      h('button', { class: 'ibtn', onclick: () => go(`/schedule?w=${off + 1}${mineOnly ? '&mine=1' : ''}`) }, icon('chevR')),
      h('button', { class: 'btn sm' + (mineOnly ? ' primary' : ''), onclick: () => go(`/schedule?w=${off}${mineOnly ? '' : '&mine=1'}`) }, 'My list only')),
    h('div', { class: 'week' }, days.map((d) => h('div', { class: 'day' + (d.date.toDateString() === todayStr ? ' today' : '') },
      h('h4', null, d.date.toLocaleDateString([], { weekday: 'long' }), h('span', null, d.date.getDate())),
      d.items.length ? d.items.map((it) => h('div', { class: 'air' + (mine.has(it.media.id) ? ' mine' : ''), onclick: () => go(mediaLink(it.media)) },
        h('img', { src: coverOf(it.media), alt: '', loading: 'lazy' }), h('div', { class: 'tx' }, h('b', null, titleOf(it.media)), h('span', null, `Ep ${it.episode} · ${fmtTime(it.airingAt)}`)))) : h('div', { class: 'note' }, '—')))))
}

export async function updates({ query }) {
  const mineOnly = query.mine !== '0'
  const [recent, mineList] = await Promise.all([data('anilist:recent'), data('anilist:list', { mode: 'anime' }).catch(() => [])])
  const mine = new Set((mineList || []).filter((e) => e.status === 'CURRENT').map((e) => e.mediaId))
  const hasMine = mine.size > 0
  const items = recent.filter((r) => !(mineOnly && hasMine) || mine.has(r.media.id))
  return h('div', { class: 'page' },
    h('h1', { class: 'page-title' }, 'Updates', hasMine && segmented([['1', 'My list'], ['0', 'All airing']], mineOnly ? '1' : '0', (v) => go('/updates?mine=' + v))),
    items.length ? h('div', { style: { maxWidth: 820 } }, items.map((r) => h('div', { class: 'ep-row', onclick: () => go(mediaLink(r.media)) },
      h('div', { class: 'th', style: { width: 56, aspectRatio: '2/3' } }, h('img', { src: coverOf(r.media), alt: '', loading: 'lazy' })),
      h('div', { class: 'tx' }, h('b', null, titleOf(r.media)), h('span', null, `Episode ${r.episode} aired ${ago(r.airingAt)}`)), mine.has(r.media.id) ? h('span', { class: 'chip acc' }, 'On your list') : null)))
      : h('div', { class: 'empty' }, h('h3', null, 'No recent episodes'), h('p', null, 'Nothing from your list aired in the last few days.')))
}
