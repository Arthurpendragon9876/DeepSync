'use strict'
// Everything the UI needs from Aniyomi/Mihon extensions: repos, install/uninstall, browsing sources.
// The heavy lifting (APK -> JAR conversion, Android API shims, running the extension) is done by the
// AnymeX desktop bridge JAR, driven through ./sidecar.js.
const fs = require('fs')
const os = require('os')
const path = require('path')
const zlib = require('zlib')
const { execFile } = require('child_process')
const { Sidecar } = require('./sidecar')
const { createRuntime } = require('./runtime')
const { decodeIndex } = require('./pb')
const match = require('./match')
const jsExt = require('./js')

const PKG_RE = /^[A-Za-z0-9_.]{3,200}$/
const isHttp = (u) => /^https?:\/\//i.test(u || '')

// aniyomi://add-repo?url=...  /  tachiyomi://add-repo?url=...  /  plain URL
function normaliseRepoUrl(input) {
  let u = String(input || '').trim()
  const m = /^(?:aniyomi|tachiyomi|mihon):\/\/add-repo\?url=(.+)$/i.exec(u)
  if (m) u = decodeURIComponent(m[1])
  if (!isHttp(u)) throw new Error('Repo address must start with http:// or https://')
  return u
}

const repoBase = (url) => url.replace(/\/(index\.min\.json|index\.json|index\.pb\.gz|index\.pb|repo\.json)(\?.*)?$/, '').replace(/\/$/, '')

function entryType(e) {
  if (/^Aniyomi: /.test(e.name)) return 'anime'
  if (/^Tachiyomi: /.test(e.name)) return 'manga'
  if (/\.anime(extension)?\./.test(e.pkg)) return 'anime'
  return 'manga'
}
const cleanName = (n) => n.replace(/^(Aniyomi|Tachiyomi): /, '')

function parseIndex(bytes, repoUrl) {
  let buf = Buffer.from(bytes)
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf)
  const first = buf[0]
  let list
  if (first === 0x5b || first === 0x7b) {
    const j = JSON.parse(buf.toString('utf8'))
    if (Array.isArray(j)) list = j
    else return { follow: j.index_v2 || j.index || null, name: j.meta?.name }
  } else list = decodeIndex(buf)
  const base = repoBase(repoUrl)
  return {
    list: list
      .filter((e) => e && e.pkg && PKG_RE.test(e.pkg))
      .map((e) => ({
        pkg: e.pkg,
        name: cleanName(e.name || e.pkg),
        type: entryType(e),
        lang: e.lang || e.sources?.[0]?.lang || 'all',
        version: String(e.version || ''),
        code: Number(e.code) || 0,
        nsfw: !!(e.nsfw || e.isNsfw),
        apkUrl: isHttp(e.apkUrl) ? e.apkUrl : isHttp(e.apk) ? e.apk : e.apk ? `${base}/apk/${e.apk}` : '',
        iconUrl: isHttp(e.iconUrl) ? e.iconUrl : `${base}/icon/${e.pkg}.png`,
        sources: (e.sources || []).map((s) => ({ id: String(s.id), name: s.name, lang: s.lang })),
        repo: repoUrl,
      })),
  }
}

// "1.4.23" vs "1.4.9"
function cmpVersion(a, b) {
  const pa = String(a).split(/[.\-]/).map((x) => parseInt(x, 10) || 0)
  const pb = String(b).split(/[.\-]/).map((x) => parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0)
  return 0
}

// Aniyomi/Mihon index entries plus Mangayomi / LNReader entries from the same address.
function parseAny(bytes, repoUrl) {
  const p = parseIndex(bytes, repoUrl)
  if (p.follow) return p
  const js = jsExt.parseIndex(bytes, repoUrl) || []
  return { ...p, list: [...(p.list || []), ...js] }
}

function create({ dir, store, fetchImpl = globalThis.fetch, proxyUrl = (u) => u, sidecarFactory, fixer, jsHost, jsInline = false }) {
  const root = path.join(dir, 'extensions')
  const jarDir = path.join(root, 'aniyomi')
  const metaFile = path.join(root, 'installed.json')
  fs.mkdirSync(jarDir, { recursive: true })
  const settings = () => store.settings
  const runtime = createRuntime({ dir: root, getSettings: settings, fetchImpl })
  const js = jsExt.create({ dir, fetchImpl, proxyUrl, host: jsHost, inline: jsInline })

  // R8 strips empty constructors from extension classes; converted JARs then crash with NoClassDefFoundError.
  // ctorfix.jar (see main/extensions/java/CtorFix.java) puts them back. Runs before JARs are loaded.
  const fixerJar = path.join(root, 'runtime', 'ctorfix.jar')
  const runFixer =
    fixer ||
    (async (target) => {
      const java = await runtime.findJava()
      const jar = runtime.bridgePath()
      if (!java || java.tooOld || !jar) return
      fs.mkdirSync(path.dirname(fixerJar), { recursive: true })
      fs.copyFileSync(path.join(__dirname, 'ctorfix.jar'), fixerJar) // copy out of app.asar so Java can read it
      await new Promise((resolve) =>
        execFile(java.path, ['-Xmx256m', '-cp', jar + path.delimiter + fixerJar, 'CtorFix', target].concat(fs.statSync(target).isDirectory() ? [] : [target]), { timeout: 120000, windowsHide: true }, () => resolve()),
      )
    })

  let sidecar = null
  let installed = null // [{ id, name, lang, type, pkgName, baseUrl, ... }]
  const meta = (() => {
    try {
      return JSON.parse(fs.readFileSync(metaFile, 'utf8'))
    } catch {
      return {}
    }
  })()
  const saveMeta = () => fs.writeFileSync(metaFile, JSON.stringify(meta))

  async function bridge() {
    if (sidecar?.running) return sidecar
    const java = await runtime.findJava()
    const jar = runtime.bridgePath()
    if (!java || java.tooOld) throw Object.assign(new Error('Java 17 or newer is required. Open Extensions to set it up.'), { code: 'NO_RUNTIME' })
    if (!jar) throw Object.assign(new Error('The extension runtime is not installed yet. Open Extensions to set it up.'), { code: 'NO_RUNTIME' })
    await runFixer(jarDir).catch(() => {}) // JARs installed by an earlier version
    sidecar = sidecarFactory ? sidecarFactory(java.path, jar) : new Sidecar({ javaPath: java.path, jarPath: jar })
    sidecar.on?.('log', () => {})
    installed = null
    await sidecar.start()
    return sidecar
  }
  // Extension code reports network failures as a bare host name ("api.example.org"); say what it means.
  const friendly = (e) => {
    const m = String(e.message || '')
    if (/^[a-z0-9.-]+\.[a-z]{2,}(:\d+)?$/i.test(m) || /UnknownHost|Unable to resolve host|Connection refused|timeout/i.test(m) && !/Extension call/.test(m))
      return Object.assign(new Error(`Could not reach ${m.match(/[a-z0-9.-]+\.[a-z]{2,}/i)?.[0] || 'the source'}. Check your connection; the site may also be down or blocking this app.`), { code: 'OFFLINE' })
    return e
  }
  const call = async (method, args, opts) => {
    const sc = await bridge()
    try {
      return await sc.call(method, args, opts)
    } catch (e) {
      throw friendly(e)
    }
  }
  // The JAR sometimes reports failures as data: {"error": "..."}.
  const unwrap = (r) => {
    if (r && typeof r === 'object' && !Array.isArray(r) && typeof r.error === 'string') throw friendly(new Error(r.error))
    return r
  }

  // ---- repos -------------------------------------------------------------------------------------------------------
  const repos = () => settings().extensions?.repos || []
  const setRepos = (list) => store.patchSettings({ extensions: { repos: list } })
  const indexCache = new Map() // repo -> { at, list }

  async function fetchRepo(url, depth = 0) {
    const hit = indexCache.get(url)
    if (hit && Date.now() - hit.at < 5 * 60000) return hit.list
    let res
    try {
      res = await fetchImpl(url, { signal: AbortSignal.timeout(30000), redirect: 'follow' })
    } catch {
      throw Object.assign(new Error('Cannot reach ' + url), { code: 'OFFLINE' })
    }
    if (!res.ok) throw new Error(`Repo returned HTTP ${res.status}: ${url}`)
    const parsed = parseAny(new Uint8Array(await res.arrayBuffer()), url)
    if (parsed.follow && depth < 2) return fetchRepo(new URL(parsed.follow, url).href, depth + 1)
    if (!parsed.list) throw new Error('That address is not an extension repo index')
    indexCache.set(url, { at: Date.now(), list: parsed.list })
    return parsed.list
  }

  async function addRepo(input) {
    const url = normaliseRepoUrl(input)
    if (repos().includes(url)) return repos()
    const list = await fetchRepo(url) // validates before saving
    if (!list.length) throw new Error('That repo has no extensions in it')
    setRepos([...repos(), url])
    return repos()
  }
  const removeRepo = (url) => (indexCache.delete(url), setRepos(repos().filter((r) => r !== url)), repos())

  // ---- installed ---------------------------------------------------------------------------------------------------
  // Java-free sources always work; Aniyomi/Mihon ones are added when the Java bridge is available.
  async function loadInstalled(force = false) {
    if (installed && !force) return installed
    const own = js.installed()
    let java = []
    let javaError = null
    // With Java-free sources installed and no Aniyomi files, don't start a JVM just to list nothing.
    if (!own.length || hasAniyomiFiles()) {
      try { java = await loadJavaInstalled() } catch (e) { javaError = e }
    }
    if (javaError && !own.length) throw javaError
    installed = [...java, ...own]
    return installed
  }
  const hasAniyomiFiles = () => { try { return fs.readdirSync(jarDir).some((f) => f.endsWith('.jar')) } catch { return false } }

  async function loadJavaInstalled() {
    const r = unwrap(await call('loadExtensions', { folderPath: jarDir }, { timeout: 180000 }))
    const list = Array.isArray(r) ? r : []
    return list.map((s) => ({
      runtime: 'aniyomi',
      id: String(s.id),
      name: s.name,
      lang: s.lang,
      type: s.type === 'anime' ? 'anime' : 'manga',
      pkgName: s.pkgName || '',
      baseUrl: s.baseUrl || '',
      version: meta[s.pkgName]?.version || s.version || '',
      nsfw: !!s.isNsfw || !!meta[s.pkgName]?.nsfw,
      supportsLatest: !!s.supportsLatest,
      supportsPopular: s.supportsPopular !== false,
      iconUrl: meta[s.pkgName]?.iconUrl ? proxyUrl(meta[s.pkgName].iconUrl) : '',
    }))
  }

  async function available(type) {
    const have = await loadInstalled().catch(() => [])
    const byPkg = new Map(have.map((s) => [s.pkgName, s]))
    const results = await Promise.allSettled(repos().map((r) => fetchRepo(r)))
    const errors = []
    const out = []
    results.forEach((r, i) => (r.status === 'fulfilled' ? out.push(...r.value) : errors.push({ repo: repos()[i], message: r.reason.message })))
    const seen = new Set()
    const list = out
      .filter((e) => (!type || e.type === type) && (seen.has(e.pkg) ? false : seen.add(e.pkg)))
      .map((e) => {
        const cur = byPkg.get(e.pkg)
        return { ...e, iconUrl: proxyUrl(e.iconUrl), installed: !!cur, hasUpdate: !!cur && cmpVersion(e.version, cur.version) > 0 }
      })
      .sort((a, b) => a.name.localeCompare(b.name))
    return { list, errors }
  }

  async function install({ pkg, apkUrl, version, iconUrl, nsfw, repo }) {
    if (jsExt.isJsPkg(pkg)) return installJs(pkg, repo)
    if (!PKG_RE.test(pkg || '')) throw new Error('Invalid extension package name')
    // Never trust the renderer for the download address: look it up from a configured repo.
    let entry
    for (const r of repos()) {
      const l = await fetchRepo(r).catch(() => [])
      entry = l.find((e) => e.pkg === pkg && (!repo || e.repo === repo)) || entry
    }
    if (!entry) throw new Error('Extension not found in your repos')
    if (!isHttp(entry.apkUrl)) throw new Error('This repo entry has no APK address')
    const tmp = path.join(os.tmpdir(), `ds-ext-${process.pid}-${Date.now()}.apk`)
    try {
      const res = await fetchImpl(entry.apkUrl, { redirect: 'follow', signal: AbortSignal.timeout(120000) })
      if (!res.ok) throw new Error(`APK download failed (HTTP ${res.status}). The repo may have moved its files.`)
      const buf = Buffer.from(await res.arrayBuffer())
      if (buf.length < 1000 || buf[0] !== 0x50 || buf[1] !== 0x4b) throw new Error('Downloaded file is not an APK')
      fs.writeFileSync(tmp, buf)
      const jar = path.join(jarDir, pkg + '.jar')
      unwrap(await call('convertApk', { apkPath: tmp, outJarPath: jar }, { timeout: 240000 }))
      if (!fs.existsSync(jar)) throw new Error('Conversion finished but no JAR was produced')
      await runFixer(jar).catch(() => {})
    } finally {
      fs.rmSync(tmp, { force: true })
    }
    meta[pkg] = { version: entry.version, iconUrl: entry.iconUrl, nsfw: entry.nsfw, name: entry.name, repo: entry.repo }
    saveMeta()
    const list = await loadInstalled(true)
    if (!list.some((s) => s.pkgName === pkg)) throw new Error('The extension was converted but exposes no sources (it may need a newer runtime)')
    return list
  }

  // Mangayomi / LNReader source: the entry comes from a configured repo, never from the renderer.
  async function installJs(pkg, repo) {
    let entry
    for (const r of repos()) {
      const l = await fetchRepo(r).catch(() => [])
      entry = l.find((e) => e.pkg === pkg && (!repo || e.repo === repo)) || entry
    }
    if (!entry) throw new Error('Extension not found in your repos')
    const list = await js.install(entry)
    installed = null
    return list
  }

  async function uninstall({ pkg }) {
    if (jsExt.isJsPkg(pkg)) { const rest = js.uninstall(pkg); installed = null; return rest }
    if (!PKG_RE.test(pkg || '')) throw new Error('Invalid extension package name')
    for (const s of (await loadInstalled()).filter((x) => x.pkgName === pkg)) await call('unloadExtension', { sourceId: s.id }).catch(() => {})
    fs.rmSync(path.join(jarDir, pkg + '.jar'), { force: true })
    delete meta[pkg]
    saveMeta()
    return loadInstalled(true)
  }

  // ---- sources -----------------------------------------------------------------------------------------------------
  const src = async (id) => {
    const s = (await loadInstalled()).find((x) => x.id === String(id))
    if (!s) throw new Error('That source is not installed any more')
    return s
  }
  const ref = (s) => ({ sourceId: s.id, isAnime: s.type === 'anime' })
  const refererFor = (s) => (s.baseUrl ? { Referer: s.baseUrl.replace(/\/?$/, '/') } : {})

  const item = (s, m) => ({
    title: m.title || m.name || '',
    url: m.url || '',
    cover: m.cover || m.thumbnail_url ? proxyUrl(m.cover || m.thumbnail_url, refererFor(s)) : '',
    description: m.description || '',
    author: m.author || '',
    artist: m.artist || '',
    genre: Array.isArray(m.genre) ? m.genre : [],
    status: m.status ?? 0,
  })

  async function browse({ sourceId, kind = 'popular', query = '', page = 1, filters = [] }) {
    if (jsExt.isJsId(sourceId)) return js.browse(sourceId, { kind, query, page, filters })
    const s = await src(sourceId)
    const method = kind === 'search' ? 'search' : kind === 'latest' ? 'getLatestUpdates' : 'getPopular'
    const args = { ...ref(s), page, ...(kind === 'search' ? { query, filters } : {}) }
    const r = unwrap(await call(method, args))
    return { list: (r?.list || []).map((m) => item(s, m)), hasNextPage: !!r?.hasNextPage }
  }

  async function detail({ sourceId, media }) {
    if (jsExt.isJsId(sourceId)) return js.detail(sourceId, media)
    const s = await src(sourceId)
    const r = unwrap(await call('getDetail', { ...ref(s), media: { title: media.title, url: media.url, thumbnail_url: media.cover || '' } }))
    const eps = (r?.episodes || []).map((e, i) => {
      const n = Number(e.chapter_number ?? e.episode_number)
      return {
        name: e.name || '',
        url: e.url || '',
        number: Number.isFinite(n) && n >= 0 ? n : null,
        date: Number(e.date_upload) || 0,
        scanlator: e.scanlator || '',
        _i: i,
      }
    })
    // Sources list newest first; the UI wants oldest first.
    const chapters = eps.reverse().map(({ _i, ...c }) => c)
    return { ...item(s, { ...media, ...r }), chapters, type: s.type }
  }

  async function pages({ sourceId, chapter }) {
    if (jsExt.isJsId(sourceId)) return js.pages(sourceId, chapter)
    const s = await src(sourceId)
    const r = unwrap(await call('getPageList', { ...ref(s), episode: { name: chapter.name, url: chapter.url, episode_number: chapter.number, scanlator: chapter.scanlator } }, { timeout: 90000 }))
    if (!Array.isArray(r)) throw new Error('The source returned no pages')
    return r.filter((p) => p?.url).map((p) => ({ kind: 'url', url: proxyUrl(p.url, { ...refererFor(s), ...(p.headers || {}) }) }))
  }

  async function videos({ sourceId, episode }) {
    if (jsExt.isJsId(sourceId)) return js.videos(sourceId, episode)
    const s = await src(sourceId)
    const r = unwrap(await call('getVideoList', { ...ref(s), episode: { name: episode.name, url: episode.url, episode_number: episode.number, scanlator: episode.scanlator } }, { timeout: 90000 }))
    if (!Array.isArray(r)) throw new Error('The source returned no video links')
    // Aniyomi's Video has both `url` (page) and `videoUrl` (the real stream); take whichever is a web address.
    // Any array field with "audio" in its name counts (the bridge may call it audios, audioTracks, audio_tracks...); items may be { file | url | videoUrl, label | lang | name | title }.
    const audiosOf = (v) => Object.keys(v || {}).filter((k) => /audio/i.test(k) && Array.isArray(v[k])).flatMap((k) => v[k])
      .map((t) => (typeof t === 'string' ? { url: t, label: '' } : { url: t?.file || t?.url || t?.videoUrl, label: t?.label || t?.lang || t?.name || t?.title || '' }))
      .filter((t) => isHttp(t.url))
    const fieldsOf = (v) => Object.keys(v || {}).map((k) => (Array.isArray(v[k]) ? `${k}[${v[k].length}]` : k)).join(', ')
    const real = (v) => (isHttp(v?.url) ? v.url : isHttp(v?.videoUrl) ? v.videoUrl : '')
    const ok = r.filter((v) => real(v))
    if (r.length && !ok.length) throw new Error(`The extension returned ${r.length} link(s) but none is a web address (first: "${String(r[0]?.url || r[0]?.videoUrl || 'empty').slice(0, 60)}").`)
    return ok.map((v) => ({
      title: v.title || v.quality || 'Video',
      quality: v.quality || v.title || '',
      url: real(v),
      headers: v.headers || {},
      subtitles: (v.subtitles || v.subtitleTracks || []).map((t) => ({ url: t.file || t.url, label: t.label || t.lang || '' })).filter((t) => isHttp(t.url)),
      audios: audiosOf(v),
      fields: fieldsOf(v), // raw shape of what the bridge sent, for "Copy details"
    }))
  }

  // Finds an AniList title in the installed sources, the way the Android app picks a source for a tracker entry.
  // Searches the sources in parallel (a few at a time), ranks every result with the title matcher and returns the
  // best hit at or above the matcher's threshold. `preferred` wins ties.
  async function autoMatch({ media, mode, sourceIds, preferred }) {
    const type = mode === 'anime' ? 'anime' : mode === 'novel' ? 'novel' : 'manga'
    const langs = settings().extensions?.autoLangs || ['en', 'all']
    let list = (await loadInstalled()).filter((x) => x.type === type)
    if (sourceIds?.length) list = list.filter((x) => sourceIds.includes(x.id))
    else list = list.filter((x) => langs.includes(x.lang))
    const names = match.namesOf(media)
    const queries = [...new Set([media?.title?.english, media?.title?.romaji, media?.title?.userPreferred].filter(Boolean))].slice(0, 2)
    if (!queries.length) return { best: null, hits: [], failed: [], tried: 0 }
    const hits = [], failed = []
    const one = async (s) => {
      let lastErr = null
      for (const q of queries) {
        try {
          const r = await browse({ sourceId: s.id, kind: 'search', query: q })
          const ranked = match.rank(media, r.list)
          if (ranked.length) return hits.push({ sourceId: s.id, sourceName: s.name, lang: s.lang, item: ranked[0].item, score: ranked[0].score })
          lastErr = null
        } catch (e) { lastErr = e }
      }
      if (lastErr) failed.push({ sourceId: s.id, sourceName: s.name, error: lastErr.message })
    }
    const queue = [...list]
    await Promise.all(Array.from({ length: Math.min(3, queue.length) }, async () => { for (let s; (s = queue.shift()); ) await Promise.race([one(s), new Promise((r) => setTimeout(r, 30000))]) }))
    hits.sort((a, b) => b.score - a.score || (b.sourceId === preferred) - (a.sourceId === preferred))
    return { best: hits[0] || null, hits, failed, tried: list.length, names: names.length }
  }

  const chapterText = async ({ sourceId, chapter }) => {
    if (!jsExt.isJsId(sourceId)) throw new Error('Only Mangayomi and LNReader sources provide novel text')
    return js.chapterText(sourceId, chapter)
  }

  const filters = async ({ sourceId }) => {
    if (jsExt.isJsId(sourceId)) return js.filters(sourceId)
    const s = await src(sourceId)
    const r = unwrap(await call('getFilterList', ref(s)))
    return typeof r === 'string' ? JSON.parse(r) : r || []
  }

  async function status() {
    const st = await runtime.status()
    return { ...st, running: !!sidecar?.running, repos: repos() }
  }
  const stop = () => (sidecar?.stop(), (sidecar = null), (installed = null))
  const stopAll = () => (stop(), js.stop())

  return { js, chapterText, stopAll, runtime, status, ensure: (o) => runtime.ensure(o).then(() => status()), repos, addRepo, removeRepo, available, loadInstalled, install, uninstall, browse, detail, pages, videos, filters, autoMatch, stop, call }
}

module.exports = { create, parseIndex, parseAny, cmpVersion, normaliseRepoUrl, entryType }
