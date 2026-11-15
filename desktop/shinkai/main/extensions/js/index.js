'use strict'
// Mangayomi-format extensions that need no Java:
//   - Mangayomi JavaScript sources (manga, anime and novel), repos are JSON arrays with `sourceCodeUrl` entries
//   - LNReader plugins (novels), repos are JSON arrays of { id, name, site, lang, version, url, iconUrl }
// Both are plain JavaScript run by ./engine.js in a child process (./host.js). This module reads those repo indexes,
// downloads and stores source files, and answers the browse / detail / pages / videos / chapter-text calls.
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')
const { createHost } = require('./host')
const { cleanNovelHtml } = require('./sanitize')

const isHttp = (u) => /^https?:\/\//i.test(u || '')
const TYPES = ['manga', 'anime', 'novel']
const MAX_CODE = 6 * 1048576

// pkg: the id the UI uses to install/uninstall ("mgy.1234", "lnr.NovelFull"); id: the source id used in routes.
const isJsPkg = (pkg) => /^(mgy|lnr)\./.test(String(pkg || ''))
const isJsId = (id) => /^(mgy|lnr)-/.test(String(id || ''))
const idPart = (v) => String(v).replace(/-/g, 'n').replace(/[^A-Za-z0-9_]/g, '_')
const idToPkg = (id) => String(id).replace(/^(mgy|lnr)-/, '$1.')
const pkgToId = (pkg) => String(pkg).replace(/^(mgy|lnr)\./, '$1-')

const LN_LANGS = { 'العربية': 'ar', '中文, 汉语, 漢語': 'zh', English: 'en', 'Français': 'fr', 'Bahasa Indonesia': 'id', '日本語': 'ja', '조선말, 한국어': 'ko', Polski: 'pl', 'Português': 'pt', 'Русский': 'ru', 'Español': 'es', 'ไทย': 'th', 'Türkçe': 'tr', 'Українська': 'uk', 'Tiếng Việt': 'vi', Multi: 'all' }
const lnLang = (l) => LN_LANGS[String(l || '').replace(/\u200e/g, '').trim()] || 'all'

function abs(u, base) {
  const v = String(u || '').trim()
  if (!v) return ''
  try { return new URL(v, base || undefined).href } catch { return v }
}

// Returns a list of entries, or null when the bytes are not a JSON array this module understands.
function parseIndex(bytes, repoUrl) {
  let buf = Buffer.from(bytes)
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf)
  if (buf[0] !== 0x5b) return null
  let arr
  try { arr = JSON.parse(buf.toString('utf8')) } catch { return null }
  if (!Array.isArray(arr)) return null
  const out = []
  for (const e of arr) {
    if (!e || typeof e !== 'object') continue
    if (typeof e.file === 'string' && /\.aix$/i.test(e.file)) continue // Aidoku (WASM): not supported here
    if (e.sourceCodeUrl && Number(e.sourceCodeLanguage) === 1 && e.name) {
      const t = e.itemType != null ? TYPES[Number(e.itemType)] : e.isManga === false ? 'anime' : 'manga'
      if (!t || !Number.isFinite(Number(e.id))) continue
      const idStr = idPart(e.id)
      out.push({
        pkg: 'mgy.' + idStr, name: String(e.name), type: t, lang: String(e.lang || 'all'), version: String(e.version || '0.0.1'), nsfw: !!e.isNsfw, code: 0,
        iconUrl: abs(e.iconUrl, repoUrl), apkUrl: '', codeUrl: abs(e.sourceCodeUrl, repoUrl), runtime: 'mangayomi', repo: repoUrl,
        sources: [{ id: 'mgy-' + idStr, name: String(e.name), lang: String(e.lang || 'all') }],
        info: { id: Number(e.id), baseUrl: String(e.baseUrl || ''), apiUrl: String(e.apiUrl || ''), dateFormat: String(e.dateFormat || ''), dateFormatLocale: String(e.dateFormatLocale || ''), hasCloudflare: !!e.hasCloudflare, isFullData: !!e.isFullData, additionalParams: String(e.additionalParams || ''), notes: String(e.notes || '') },
      })
    } else if (typeof e.id === 'string' && e.name && e.site && e.lang && e.version && e.url && e.iconUrl) {
      const slug = idPart(e.id) || idPart(e.name)
      const lang = lnLang(e.lang)
      out.push({
        pkg: 'lnr.' + slug, name: String(e.name), type: 'novel', lang, version: String(e.version), nsfw: false, code: 0,
        iconUrl: abs(e.iconUrl, repoUrl), apkUrl: '', codeUrl: abs(e.url, repoUrl), runtime: 'lnreader', repo: repoUrl,
        sources: [{ id: 'lnr-' + slug, name: String(e.name), lang }],
        info: { id: 'lnr-' + slug, baseUrl: String(e.site), apiUrl: '', dateFormat: '', dateFormatLocale: '', hasCloudflare: false, isFullData: false, additionalParams: '', notes: '' },
      })
    }
  }
  return out
}

function create({ dir, fetchImpl = globalThis.fetch, proxyUrl = (u) => u, host: injectedHost, inline = false }) {
  const root = path.join(dir, 'extensions', 'js')
  const codeDir = path.join(root, 'src')
  const metaFile = path.join(root, 'installed.json')
  fs.mkdirSync(codeDir, { recursive: true })
  const host = injectedHost || createHost({ prefsFile: path.join(root, 'prefs.json'), inline, fetchImpl })

  const readMeta = () => { try { return JSON.parse(fs.readFileSync(metaFile, 'utf8')) } catch { return {} } }
  const writeMeta = (m) => fs.writeFileSync(metaFile, JSON.stringify(m))
  const codeCache = new Map()
  const codePath = (pkg) => path.join(codeDir, pkg + '.js')

  const present = (m) => ({
    id: pkgToId(m.pkg), name: m.name, lang: m.lang, type: m.type, pkgName: m.pkg, baseUrl: m.info.baseUrl, version: m.version, nsfw: !!m.nsfw,
    supportsLatest: m.supportsLatest !== false, supportsPopular: true, iconUrl: m.iconUrl ? proxyUrl(m.iconUrl) : '', runtime: m.runtime,
  })
  const installed = () => Object.values(readMeta()).map(present)
  const get = (id) => {
    const m = readMeta()[idToPkg(id)]
    if (!m) throw new Error('That source is not installed any more')
    return m
  }
  const metaFor = (m) => {
    let code = codeCache.get(m.pkg)
    if (code == null) {
      try { code = fs.readFileSync(codePath(m.pkg), 'utf8') } catch { throw new Error('The extension file is missing. Reinstall it from the Extensions screen.') }
      codeCache.set(m.pkg, code)
    }
    return { key: pkgToId(m.pkg), language: m.runtime, code, source: { ...m.info, id: m.info.id, name: m.name, lang: m.lang } }
  }
  const call = (m, method, args, opts) => host.call(metaFor(m), method, args, opts)

  // ---- install -------------------------------------------------------------------------------------------------------
  async function install(entry) {
    if (!isJsPkg(entry?.pkg) || !isHttp(entry.codeUrl)) throw new Error('This repo entry has no source address')
    let res
    try { res = await fetchImpl(entry.codeUrl, { redirect: 'follow', signal: AbortSignal.timeout(60000) }) } catch { throw Object.assign(new Error('Cannot reach ' + new URL(entry.codeUrl).host), { code: 'OFFLINE' }) }
    if (!res.ok) throw new Error(`Source download failed (HTTP ${res.status}). The repo may have moved its files.`)
    const buf = Buffer.from(await res.arrayBuffer())
    if (!buf.length || buf.length > MAX_CODE) throw new Error('Downloaded source file has an unexpected size')
    const code = buf.toString('utf8')
    if (entry.runtime === 'mangayomi' && !/class\s+DefaultExtension\b/.test(code)) throw new Error('That file is not a Mangayomi JavaScript source')
    if (entry.runtime === 'lnreader' && !/exports/.test(code)) throw new Error('That file is not an LNReader plugin')

    const file = codePath(entry.pkg)
    const had = fs.existsSync(file) ? fs.readFileSync(file) : null
    fs.writeFileSync(file, code)
    codeCache.delete(entry.pkg)
    host.stop(pkgToId(entry.pkg))
    const rec = { pkg: entry.pkg, name: entry.name, lang: entry.lang, type: entry.type, version: entry.version, nsfw: entry.nsfw, iconUrl: entry.iconUrl, runtime: entry.runtime, repo: entry.repo, info: entry.info, supportsLatest: true }
    try {
      // Loading it once proves the code at least evaluates; the error names the source if it does not.
      rec.supportsLatest = (await call(rec, 'supportsLatest', [])) !== false
    } catch (e) {
      if (had) fs.writeFileSync(file, had); else fs.rmSync(file, { force: true })
      codeCache.delete(entry.pkg)
      host.stop(pkgToId(entry.pkg))
      throw e
    }
    const m = readMeta()
    m[entry.pkg] = rec
    writeMeta(m)
    return installed()
  }

  function uninstall(pkg) {
    if (!isJsPkg(pkg)) throw new Error('Invalid extension package name')
    host.stop(pkgToId(pkg))
    fs.rmSync(codePath(pkg), { force: true })
    codeCache.delete(pkg)
    host.prefs.drop(pkgToId(pkg))
    const m = readMeta()
    delete m[pkg]
    writeMeta(m)
    return installed()
  }

  // ---- source calls --------------------------------------------------------------------------------------------------
  const hdrCache = new Map()
  async function imageHeaders(m) {
    const key = m.pkg
    if (!hdrCache.has(key)) {
      let h = {}
      try { h = (await call(m, 'headers', [])) || {} } catch {}
      if (!Object.keys(h).some((k) => k.toLowerCase() === 'referer') && m.info.baseUrl) h = { Referer: m.info.baseUrl.replace(/\/?$/, '/'), ...h }
      hdrCache.set(key, h)
    }
    return hdrCache.get(key)
  }
  const cover = (m, u, hdrs) => (u ? proxyUrl(abs(u, m.info.baseUrl), hdrs) : '')
  const mapItem = (m, hdrs) => (it) => ({ ...it, cover: cover(m, it.cover, hdrs) })

  async function browse(id, { kind = 'popular', query = '', page = 1, filters = [] }) {
    const m = get(id)
    const method = kind === 'search' ? 'search' : kind === 'latest' ? 'latest' : 'popular'
    const r = await call(m, method, method === 'search' ? [query, page, filters] : [page])
    const hdrs = await imageHeaders(m)
    return { list: r.list.map(mapItem(m, hdrs)), hasNextPage: !!r.hasNextPage }
  }

  async function detail(id, media) {
    const m = get(id)
    const d = await call(m, 'detail', [media.url])
    const hdrs = await imageHeaders(m)
    return { ...d, title: d.title || media.title || '', url: d.url || media.url, cover: d.cover ? cover(m, d.cover, hdrs) : media.cover || '', type: m.type }
  }

  async function pages(id, chapter) {
    const m = get(id)
    const r = await call(m, 'pages', [chapter.url], { timeout: 90000 })
    if (!r.length) throw new Error('The source returned no pages')
    const hdrs = await imageHeaders(m)
    return r.map((p) => ({ kind: 'url', url: proxyUrl(abs(p.url, m.info.baseUrl), { ...hdrs, ...(p.headers || {}) }) }))
  }

  async function videos(id, episode) {
    const m = get(id)
    const r = await call(m, 'videos', [episode.url], { timeout: 90000 })
    return r.filter((v) => isHttp(v.url)).map((v) => ({
      title: v.quality || 'Video', quality: v.quality || '', url: v.url, headers: v.headers || {},
      subtitles: (v.subtitles || []).filter((t) => isHttp(t.file)).map((t) => ({ url: t.file, label: t.label || '' })),
    }))
  }

  // Sanitised chapter text for the novel reader
  async function chapterText(id, chapter) {
    const m = get(id)
    const html = await call(m, 'html', [chapter.name || '', chapter.url], { timeout: 90000 })
    const hdrs = await imageHeaders(m)
    const base = abs(chapter.url, m.info.baseUrl) || m.info.baseUrl
    const clean = cleanNovelHtml(html, { baseUrl: base, proxyUrl, headers: hdrs })
    if (!clean.replace(/<[^>]*>/g, '').trim()) throw new Error('The source returned an empty chapter')
    return { html: clean }
  }

  return {
    host, installed, install, uninstall, browse, detail, pages, videos, chapterText,
    filters: (id) => call(get(id), 'filters', []),
    prefs: (id) => call(get(id), 'prefs', []),
    setPref: (id, key, value) => host.setPref(metaFor(get(id)), String(key), value),
    stop: () => host.stopAll(),
  }
}

module.exports = { create, parseIndex, isJsPkg, isJsId, lnLang }
