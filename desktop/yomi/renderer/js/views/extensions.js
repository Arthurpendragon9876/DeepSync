import { h, icon, S, api, saveSettings, switchEl, toast, sheet, go, errorBox } from '../core.js'
import { srcLabel } from '../ext-ui.js'

const mb = (n) => (n / 1048576).toFixed(0) + ' MB'
const TYPE_LABEL = { manga: 'Manga', anime: 'Anime', novel: 'Novel' }
const RUNTIME_LABEL = { mangayomi: 'Mangayomi', lnreader: 'LNReader', aniyomi: 'Aniyomi/Mihon' }
const TABS = [['installed', 'Installed'], ['manga', 'Manga'], ['anime', 'Anime'], ['novel', 'Novel'], ['repos', 'Repos'], ['builtin', 'Built-in']]

export async function extensions({ query }) {
  const tab = query.tab || 'installed'
  const page = h('div', { class: 'page' }, h('h1', { class: 'page-title' }, 'Extensions'))
  const wrap = h('div', { style: { maxWidth: '820px' } })
  const body = h('div')
  const rt = h('div')
  wrap.append(rt, h('div', { class: 'tabs', style: { marginTop: '22px' } }, TABS.map(([k, l]) => h('div', { class: 'tab' + (k === tab ? ' on' : ''), onclick: () => (location.hash = '#/extensions?tab=' + k) }, l))), body)
  page.append(wrap)

  // ---- runtime card -------------------------------------------------------------------------------------------------
  let rstat
  async function drawRuntime() {
    try { rstat = await api('ext:status') } catch (e) { return rt.replaceChildren(errorBox(e)) }
    const ok = rstat.java.found && rstat.bridge.installed
    const java = h('input', { class: 'input', placeholder: window.deepsync.platform === 'win32' ? 'C:\\Program Files\\Eclipse Adoptium\\jdk-17\\bin\\java.exe' : '/usr/lib/jvm/default/bin/java', value: S.settings.extensions?.javaPath || '', style: { maxWidth: '320px' } })
    const status = h('div', { class: 'note' })
    const btn = h('button', { class: 'btn primary' }, ok ? 'Update runtime' : 'Set up runtime')
    btn.onclick = async () => {
      btn.disabled = true
      const poll = setInterval(async () => { const p = await api('ext:progress').catch(() => null); if (p) status.textContent = p.label ? `${p.label}${p.progress ? ` (${Math.round(p.progress * 100)}%)` : ''}` : '' }, 400)
      try { await api('ext:setup', { force: ok }); toast('Extension runtime ready') } catch (e) { toast(e.message, true) }
      clearInterval(poll)
      drawRuntime(); draw()
    }
    rt.replaceChildren(h('div', { class: 'card' },
      h('div', { style: { display: 'flex', justifyContent: 'space-between', gap: '16px', alignItems: 'center' } },
        h('div', null, h('b', null, 'Extension runtime'), h('span', { class: 'chip ' + (ok ? 'acc' : ''), style: { marginLeft: '10px' } }, ok ? 'Ready' : 'Not set up')),
        h('div', { style: { display: 'flex', gap: '8px' } }, btn, ok && h('button', { class: 'btn', onclick: async () => (await api('ext:stop'), toast('Runtime stopped; it restarts when needed')) }, 'Stop'))),
      h('p', { class: 'note', style: { margin: '10px 0 4px' } }, 'Aniyomi and Mihon extensions are Android programs. Yomi runs them with the AnymeX desktop bridge, a separate Java process it starts for you.'),
      h('p', { class: 'note', style: { margin: '0 0 4px' } },
        rstat.java.found ? `Java ${rstat.java.major} (${rstat.java.managed ? 'downloaded by Yomi' : 'system'}: ${rstat.java.path})` : rstat.java.tooOld ? `Java ${rstat.java.major} is too old; version ${rstat.minJava}+ is needed.` : `Java ${rstat.minJava}+ not found; setup will download one (about 50 MB).`,
        ' · ', rstat.bridge.installed ? `Bridge ${mb(rstat.bridge.size)}` : 'Bridge not downloaded (about 42 MB)'),
      status,
      h('details', { style: { marginTop: '8px' } }, h('summary', { class: 'note', style: { cursor: 'pointer' } }, 'Use my own Java'),
        h('div', { style: { display: 'flex', gap: '8px', marginTop: '8px' } }, java, h('button', { class: 'btn', onclick: async () => { try { await api('ext:setJava', { javaPath: java.value.trim() }); S.settings = await api('settings:get'); toast('Saved'); drawRuntime() } catch (e) { toast(e.message, true) } } }, 'Save'))),
      h('p', { class: 'note', style: { margin: '8px 0 0', fontSize: '12px' } }, 'Bridge: RyanYuuki/AnymeXExtensionRuntimeBridge (Unabandon Public License = GPLv3 + public source). It is downloaded from its GitHub releases and run as a separate process.')))
  }

  // ---- risk gate ----------------------------------------------------------------------------------------------------
  const accept = () => new Promise((resolve) => {
    if (S.settings.extensions?.acceptedRisk) return resolve(true)
    let close
    close = sheet({ title: 'Before you install extensions', side: 'center', body: h('div',
      h('p', { class: 'note' }, 'Extensions are third-party programs written by other people. When you install one, it runs on your computer with your user permissions and can access the network and your files. Yomi does not review them.'),
      h('p', { class: 'note' }, 'Only add repos you trust, and install only what you need.'),
      h('div', { style: { display: 'flex', gap: '10px', marginTop: '18px' } },
        h('button', { class: 'btn primary', style: { flex: 1 }, onclick: async () => { await api('ext:accept'); S.settings = await api('settings:get'); close(); resolve(true) } }, 'I understand'),
        h('button', { class: 'btn', onclick: () => (close(), resolve(false)) }, 'Cancel'))) })
  })

  // ---- tabs ---------------------------------------------------------------------------------------------------------
  async function installedTab() {
    let list
    try { list = await api('ext:installed') } catch (e) { return body.replaceChildren(e.code === 'NO_RUNTIME' ? h('div', { class: 'empty' }, h('h3', null, 'Set up the runtime first'), h('p', null, e.message)) : errorBox(e, draw)) }
    if (!list.length) return body.replaceChildren(h('div', { class: 'empty' }, h('h3', null, 'No extensions installed'), h('p', null, 'Add a repo under Repos, then install extensions from the Manga, Anime or Novel tab.')))
    const groups = new Map()
    for (const s of list) (groups.get(s.pkgName) || groups.set(s.pkgName, []).get(s.pkgName)).push(s)
    const q = h('input', { class: 'input', placeholder: 'Filter installed…', style: { maxWidth: '240px' } })
    const kinds = [['', 'All'], ['manga', 'Manga'], ['anime', 'Anime'], ['novel', 'Novel']]
    const kind = h('select', { class: 'input', style: { maxWidth: '130px' } }, kinds.map(([v, l]) => h('option', { value: v }, l)))
    const runs = [...new Set(list.map((s) => s.runtime || 'aniyomi'))]
    const runtime = h('select', { class: 'input', style: { maxWidth: '170px' } }, h('option', { value: '' }, 'All types'), runs.map((k) => h('option', { value: k }, RUNTIME_LABEL[k] || k)))
    const rows = h('div')
    const drawRows = () => rows.replaceChildren(...[...groups.entries()].filter(([, ss]) => ss.some((s) => (!q.value.trim() || s.name.toLowerCase().includes(q.value.trim().toLowerCase())) && (!kind.value || s.type === kind.value) && (!runtime.value || (s.runtime || 'aniyomi') === runtime.value))).map(([pkg, ss]) => {
      const first = ss.find((s) => s.lang === 'en') || ss.find((s) => s.lang === 'all') || ss[0]
      return h('div', { class: 's-row' },
        h('div', { style: { display: 'flex', gap: '12px', alignItems: 'center', minWidth: '0px' } },
          first.iconUrl ? h('img', { src: first.iconUrl, alt: '', width: 40, height: 40, onerror: (ev) => (ev.target.style.visibility = 'hidden'), style: { borderRadius: 10 } }) : h('div', { style: { width: '40px' } }),
          h('div', { style: { minWidth: '0px' } }, h('b', null, first.name, first.nsfw && h('span', { class: 'chip', style: { marginLeft: '8px' } }, '18+')),
            h('div', { class: 'd' }, `${TYPE_LABEL[first.type] || 'Manga'} · ${RUNTIME_LABEL[first.runtime] || 'Aniyomi/Mihon'} · v${first.version || '?'} · ${ss.length > 1 ? ss.length + ' languages' : first.lang}`))),
        h('div', { style: { display: 'flex', gap: '8px' } },
          h('button', { class: 'btn', onclick: () => go('/source/' + first.id) }, icon('search', 18), 'Browse'),
          h('button', { class: 'btn danger', onclick: async () => { try { await api('ext:uninstall', { pkg }); toast('Uninstalled'); drawTab() } catch (e) { toast(e.message, true) } } }, icon('trash', 18))))
    }))
    q.oninput = drawRows; kind.onchange = drawRows; runtime.onchange = drawRows
    drawRows()
    body.replaceChildren(h('div', { class: 'filters', style: { marginBottom: '14px', flexWrap: 'wrap' } }, q, kind, runtime), rows)
  }

  async function availableTab(type) {
    body.replaceChildren(h('div', { class: 'skeleton', style: { height: '160px' } }))
    let r
    try { r = await api('ext:available', { type }) } catch (e) { return body.replaceChildren(errorBox(e, drawTab)) }
    const q = h('input', { class: 'input', placeholder: 'Filter by name…', style: { maxWidth: '260px' } })
    const langs = [...new Set(r.list.map((e) => e.lang))].sort()
    // Default: English plus multi-language sources, so a repo with 40 languages doesn't bury what you can use.
    const lang = h('select', { class: 'input', style: { maxWidth: '190px' } }, h('option', { value: 'auto' }, 'English + multi-language'), h('option', { value: '' }, 'All languages'), langs.map((l) => h('option', { value: l }, l)))
    const runs = [...new Set(r.list.map((e) => e.runtime || 'aniyomi'))]
    const runtime = h('select', { class: 'input', style: { maxWidth: '170px' } }, h('option', { value: '' }, 'All types'), runs.map((k) => h('option', { value: k }, RUNTIME_LABEL[k] || k)))
    const state = h('select', { class: 'input', style: { maxWidth: '160px' } }, [['', 'Any status'], ['new', 'Not installed'], ['installed', 'Installed'], ['update', 'Updates']].map(([v, l]) => h('option', { value: v }, l)))
    const adult = h('select', { class: 'input', style: { maxWidth: '150px' } }, [['hide', 'Hide 18+'], ['show', 'Show 18+']].map(([v, l]) => h('option', { value: v }, l)))
    const out = h('div')
    let shown = 50
    const draw1 = () => {
      const f = q.value.trim().toLowerCase()
      const items = r.list.filter((e) => (!f || e.name.toLowerCase().includes(f)) && (lang.value === 'auto' ? ['en', 'all', 'multi'].includes(e.lang) : !lang.value || e.lang === lang.value)
        && (!runtime.value || (e.runtime || 'aniyomi') === runtime.value) && (adult.value === 'show' || !e.nsfw)
        && (!state.value || (state.value === 'new' ? !e.installed : state.value === 'installed' ? e.installed && !e.hasUpdate : e.hasUpdate)))
      out.replaceChildren(...items.slice(0, shown).map((e) => h('div', { class: 's-row' },
        h('div', { style: { display: 'flex', gap: '12px', alignItems: 'center', minWidth: '0px' } },
          h('img', { src: e.iconUrl, alt: '', width: 40, height: 40, loading: 'lazy', onerror: (ev) => (ev.target.style.visibility = 'hidden'), style: { borderRadius: 10, background: 'var(--surface-3)' } }),
          h('div', { style: { minWidth: '0px' } }, h('b', null, e.name, e.nsfw && h('span', { class: 'chip', style: { marginLeft: '8px' } }, '18+')), h('div', { class: 'd' }, `${e.lang} · ${RUNTIME_LABEL[e.runtime] || 'Aniyomi/Mihon'} · v${e.version}`))),
        e.installed && !e.hasUpdate ? h('span', { class: 'chip acc' }, 'Installed') : (() => {
          const b = h('button', { class: 'btn' }, e.hasUpdate ? 'Update' : 'Install')
          b.onclick = async () => {
            if (!(await accept())) return
            b.disabled = true; b.textContent = 'Installing…'
            try { await api('ext:install', { pkg: e.pkg }); toast(e.name + ' installed'); drawTab() } catch (er) { toast(er.message, true); b.disabled = false; b.textContent = 'Install' }
          }
          return b
        })())),
        items.length > shown ? h('button', { class: 'btn', onclick: () => ((shown += 50), draw1()) }, `Show more (${items.length - shown} left)`) : null,
        !items.length && h('div', { class: 'empty' }, h('h3', null, r.list.length ? 'Nothing matches' : 'No extensions available'), h('p', null, r.list.length ? 'Change the filter.' : 'Add a repo under Repos first.')))
    }
    q.oninput = () => ((shown = 50), draw1())
    for (const el of [lang, runtime, state, adult]) el.onchange = () => ((shown = 50), draw1())
    body.replaceChildren(...r.errors.map((e) => h('p', { class: 'note' }, `Repo problem: ${e.repo}: ${e.message}`)), h('div', { class: 'filters', style: { marginBottom: '14px', flexWrap: 'wrap' } }, q, lang, runtime, state, adult), out)
    draw1()
  }

  async function reposTab() {
    const list = await api('ext:repos')
    const input = h('input', { class: 'input', placeholder: 'https://…/index.json (Mangayomi / LNReader), index.min.json, index.pb, or an aniyomi:// link', style: { flex: 1 } })
    const add = async () => { try { await api('ext:repoAdd', { url: input.value }); toast('Repo added'); drawTab() } catch (e) { toast(e.message, true) } }
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') add() })
    body.replaceChildren(
      h('p', { class: 'note' }, 'A repo is an address that lists extensions. Yomi does not come with any; add the ones you trust. Mangayomi and LNReader repos (index.json) run without Java; Aniyomi / Mihon repos (index.min.json or index.pb) need the Java runtime.'),
      h('div', { style: { display: 'flex', gap: '8px', marginBottom: '16px' } }, input, h('button', { class: 'btn primary', onclick: add }, 'Add repo')),
      ...(list.length ? list.map((u) => h('div', { class: 's-row' }, h('div', { style: { minWidth: '0px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, u),
        h('button', { class: 'btn danger', onclick: async () => { await api('ext:repoRemove', { url: u }); drawTab() } }, icon('trash', 18)))) : [h('div', { class: 'empty' }, h('h3', null, 'No repos yet'))]))
  }

  async function builtinTab() {
    const list = await api('sources:list')
    body.replaceChildren(...list.map((s) => h('div', { class: 's-row' }, h('div', null, h('b', null, s.name), h('div', { class: 'd' }, 'Manga · English chapters · built in, no runtime needed')),
      switchEl(s.enabled, (v) => saveSettings({ sources: { [s.id]: v } })))))
  }

  function drawTab() {
    if (tab === 'installed') return installedTab()
    if (tab === 'manga' || tab === 'anime' || tab === 'novel') return availableTab(tab)
    if (tab === 'repos') return reposTab()
    return builtinTab()
  }
  const draw = drawTab
  drawRuntime()
  drawTab()
  return page
}
