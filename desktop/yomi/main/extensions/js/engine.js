'use strict'
// Runs ONE Mangayomi JavaScript source or ONE LNReader plugin and exposes a language-neutral interface:
//   popular(page) latest(page) search(query, page, filters) detail(url) pages(url) videos(url) html(name, url)
//   filters() prefs() setPref(key, value) headers() supportsLatest()
// It is environment-agnostic: network and preference storage are injected (deps), so it runs identically inside the
// child process (worker.js) and inline in tests.
//
// NOTE ON SECURITY: node's vm module keeps the extension's globals separate from ours, but it is NOT a security
// boundary. Extensions are third-party programs with your user's privileges; the app gates installing them behind an
// explicit warning, and host.js runs them in a separate process that can be killed on timeout.
const vm = require('vm')
const cheerio = require('cheerio')
const htmlparser2 = require('htmlparser2')
const dayjs = require('dayjs')
const { Doc, Elem } = require('./dom')
const bridge = require('./bridge')

const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
const MAX_BODY = 40 * 1048576
const HTTP_TIMEOUT = 30000

const EXTRACTORS = ['sibnetExtractor', 'myTvExtractor', 'okruExtractor', 'voeExtractor', 'vidBomExtractor', 'streamlareExtractor', 'sendVidExtractor', 'yourUploadExtractor', 'gogoCdnExtractor', 'doodExtractor', 'streamTapeExtractor', 'mp4UploadExtractor', 'streamWishExtractor', 'filemoonExtractor', 'quarkVideosExtractor', 'ucVideosExtractor', 'quarkFilesExtractor', 'ucFilesExtractor']

// ---- everything below runs INSIDE the extension's context ----------------------------------------------------------
const COMMON_SETUP = `
console.log = console.warn = console.error = console.info = console.debug = function () {
  var parts = [];
  for (var i = 0; i < arguments.length; i++) {
    var m = arguments[i];
    try { parts.push(typeof m === "object" ? JSON.stringify(m) : String(m)); } catch (e) { parts.push(String(m)); }
  }
  __h.log(parts.join(" "));
};
String.prototype.substringAfter = function (p) { var i = this.indexOf(p); return i === -1 ? this.substring(0) : this.substring(i + p.length); };
String.prototype.substringAfterLast = function (p) { return this.split(p).pop(); };
String.prototype.substringBefore = function (p) { var i = this.indexOf(p); return i === -1 ? this.substring(0) : this.substring(0, i); };
String.prototype.substringBeforeLast = function (p) { var i = this.lastIndexOf(p); return i === -1 ? this.substring(0) : this.substring(0, i); };
String.prototype.substringBetween = function (l, r) {
  var a = this.indexOf(l); if (a === -1) return "";
  var s = a + l.length, b = this.indexOf(r, s); if (b === -1) return "";
  return this.substring(s, b);
};
async function jsonStringify(fn) { return JSON.stringify(await fn()); }
`

const MANGAYOMI_SETUP = `
class MProvider {
  get source() { return __SOURCE__; }
  get supportsLatest() { throw new Error("supportsLatest not implemented"); }
  getHeaders(url) { throw new Error("getHeaders not implemented"); }
  async getPopular(page) { throw new Error("getPopular not implemented"); }
  async getLatestUpdates(page) { throw new Error("getLatestUpdates not implemented"); }
  async search(query, page, filters) { throw new Error("search not implemented"); }
  async getDetail(url) { throw new Error("getDetail not implemented"); }
  async getPageList() { throw new Error("getPageList not implemented"); }
  async getVideoList(url) { throw new Error("getVideoList not implemented"); }
  async getHtmlContent(name, url) { throw new Error("getHtmlContent not implemented"); }
  async cleanHtmlContent(html) { throw new Error("cleanHtmlContent not implemented"); }
  getFilterList() { throw new Error("getFilterList not implemented"); }
  getSourcePreferences() { throw new Error("getSourcePreferences not implemented"); }
}

class Client {
  constructor(reqcopyWith) { this.reqcopyWith = reqcopyWith; }
  async _send(method, url, headers, body) {
    return JSON.parse(await __h.http(method, String(url), JSON.stringify(headers || {}), body === undefined ? "" : JSON.stringify(body), body === undefined ? "none" : "json", JSON.stringify(this.reqcopyWith || {})));
  }
  head(url, headers) { return this._send("HEAD", url, headers); }
  get(url, headers) { return this._send("GET", url, headers); }
  post(url, headers, body) { return this._send("POST", url, headers, body); }
  put(url, headers, body) { return this._send("PUT", url, headers, body); }
  delete(url, headers, body) { return this._send("DELETE", url, headers, body); }
  patch(url, headers, body) { return this._send("PATCH", url, headers, body); }
}

class SharedPreferences {
  get(key) { return JSON.parse(__h.prefGet(String(key))); }
  getString(key, def) { return __h.strGet(String(key), def == null ? "" : String(def)); }
  setString(key, value) { return __h.strSet(String(key), String(value)); }
}

function __wrap(v) {
  if (v && typeof v === "object") {
    if (v.__el !== undefined) return v.__el === 0 ? new Element(0) : new Element(v.__el);
    if (v.__els) return v.__els.map(function (i) { return new Element(i); });
  }
  return v;
}
function __def(cls, props, methods) {
  props.forEach(function (p) {
    Object.defineProperty(cls.prototype, p, { get: function () { return __wrap(JSON.parse(__h.node(this._id, p, "[]"))); }, configurable: true });
  });
  methods.forEach(function (m) {
    Object.defineProperty(cls.prototype, m, { value: function () { return __wrap(JSON.parse(__h.node(this._id, m, JSON.stringify(Array.prototype.slice.call(arguments))))); }, configurable: true, writable: true });
  });
}
class Element { constructor(id) { this._id = id; this.key = id; } }
class Document { constructor(html) { this._id = __h.newDoc(String(html == null ? "" : html)); } }
__def(Element, ["text","outerHtml","innerHtml","className","localName","namespaceUri","getSrc","getImg","getHref","getDataSrc","children","previousElementSibling","nextElementSibling"],
  ["attr","hasAttr","select","selectFirst","getElementsByTagName","getElementsByClassName","xpath","xpathFirst"]);
__def(Document, ["body","documentElement","head","parent","text","outerHtml","children"],
  ["select","selectFirst","getElementsByTagName","getElementsByClassName","getElementById","attr","hasAttr","xpath","xpathFirst"]);

function cryptoHandler(text, iv, key, encrypt) { return __h.crypto("cryptoHandler", JSON.stringify([text, iv, key, !!encrypt])); }
function encryptAESCryptoJS(plain, pass) { return __h.crypto("encryptAESCryptoJS", JSON.stringify([plain, pass])); }
function decryptAESCryptoJS(enc, pass) { return __h.crypto("decryptAESCryptoJS", JSON.stringify([enc, pass])); }
function decryptAESGCM(enc, keyHex, ivHex, tagHex) { return __h.crypto("decryptAESGCM", JSON.stringify([enc, keyHex, ivHex, tagHex || ""])); }
function unpackJs(code) { return __h.crypto("unpackJs", JSON.stringify([code])); }
function unpackJsAndCombine(code) { return __h.crypto("unpackJsAndCombine", JSON.stringify([code])); }
function parseDates(value, format, locale) { return JSON.parse(__h.crypto("parseDates", JSON.stringify([value, format, locale]))); }
function deobfuscateJsPassword() { throw new Error("deobfuscateJsPassword is not supported in Yomi yet"); }
async function evaluateJavascriptViaWebview() { throw new Error("Running scripts in a hidden web view is not supported in Yomi yet"); }
async function createHlsProxyUrl(url) { return url; }
async function parseEpub() { throw new Error("EPUB parsing from sources is not supported in Yomi yet"); }
async function parseEpubChapter() { throw new Error("EPUB parsing from sources is not supported in Yomi yet"); }
${EXTRACTORS.map((n) => `async function ${n}() { throw new Error("The ${n.replace('Extractor', '')} video host helper is not available in Yomi yet"); }`).join('\n')}
`

const LNREADER_SETUP = `
var module = { exports: {} };
var exports = module.exports;
function __urlencode(s) { return encodeURIComponent(s); }
var NovelStatus = { Unknown: "Unknown", Ongoing: "Ongoing", Completed: "Completed", Licensed: "Licensed", PublishingFinished: "Publishing Finished", Cancelled: "Cancelled", OnHiatus: "On Hiatus", STUB: "STUB", Inactive: "Inactive" };
var FilterTypes = { TextInput: "Text", Picker: "Picker", CheckboxGroup: "Checkbox", Switch: "Switch", ExcludableCheckboxGroup: "XCheckbox" };
var __filterInputs = {
  FilterTypes: FilterTypes,
  isPickerValue: function (q) { return q.type === FilterTypes.Picker && typeof q.value === "string"; },
  isCheckboxValue: function (q) { return q.type === FilterTypes.CheckboxGroup && Array.isArray(q.value); },
  isSwitchValue: function (q) { return q.type === FilterTypes.Switch && typeof q.value === "boolean"; },
  isTextValue: function (q) { return q.type === FilterTypes.TextInput && typeof q.value === "string"; },
  isXCheckboxValue: function (q) { return q.type === FilterTypes.ExcludableCheckboxGroup && typeof q.value === "object" && !Array.isArray(q.value); }
};
var __isUrlAbsolute = function (url) {
  if (url) {
    if (url.indexOf("//") === 0) return true;
    if (url.indexOf("://") === -1) return false;
    if (url.indexOf(".") === -1) return false;
    if (url.indexOf("/") === -1) return false;
    if (url.indexOf(":") > url.indexOf("/")) return false;
    if (url.indexOf("://") < url.indexOf(".")) return true;
  }
  return false;
};
var __storage = {
  storage: {
    get: function (key, raw) { var v = __h.lnGet(String(key)); if (v === undefined) return undefined; var item = JSON.parse(v); return raw ? item : item.value; },
    set: function (key, value, expires) { __h.lnSet(String(key), JSON.stringify({ created: new Date().toISOString(), value: value, expires: expires instanceof Date ? expires.getTime() : expires })); },
    delete: function (key) { __h.lnDel(String(key)); },
    clearAll: function () { __h.lnClear(); },
    getAllKeys: function () { return JSON.parse(__h.lnKeys()); }
  },
  localStorage: { get: function () { return undefined; } },
  sessionStorage: { get: function () { return undefined; } }
};
function require(name) {
  switch (name) {
    case "cheerio": return __mods.cheerio;
    case "htmlparser2": return __mods.htmlparser2;
    case "dayjs": return __mods.dayjs;
    case "urlencode": return { encode: __urlencode, decode: decodeURIComponent };
    case "@libs/fetch": return { fetchApi: __h.fetchApi, fetchText: __h.fetchText, fetchProto: function () { throw new Error("fetchProto is not supported"); } };
    case "@libs/novelStatus": return { NovelStatus: NovelStatus };
    case "@libs/isAbsoluteUrl": return { isUrlAbsolute: __isUrlAbsolute };
    case "@libs/filterInputs": return __filterInputs;
    case "@libs/defaultCover": return { defaultCover: "https://raw.githubusercontent.com/LNReader/lnreader-plugins/refs/heads/master/public/static/coverNotAvailable.webp" };
    case "@libs/storage": return __storage;
    default: __h.log("require(" + name + ") is not available in Yomi"); return {};
  }
}
`

// ---- engine --------------------------------------------------------------------------------------------------------
const msgOf = (e) => (e && (e.message || e.stack)) || String(e)
const PREF_KINDS = ['listPreference', 'switchPreferenceCompat', 'checkBoxPreference', 'multiSelectListPreference', 'editTextPreference']

function createEngine({ source, code, language, deps = {} }) {
  const log = deps.log || (() => {})
  const doFetch = deps.fetch || globalThis.fetch
  const lang = language === 'lnreader' ? 'lnreader' : 'mangayomi'
  const src = {
    apiUrl: source.apiUrl ?? '', baseUrl: source.baseUrl ?? '', dateFormat: source.dateFormat ?? '', dateFormatLocale: source.dateFormatLocale ?? '',
    hasCloudflare: !!source.hasCloudflare, id: source.id ?? null, isFullData: !!source.isFullData, lang: source.lang ?? '', name: source.name ?? '',
    additionalParams: source.additionalParams ?? '', notes: source.notes ?? '',
  }

  // DOM handle table (extensions only ever see integers)
  let nodes = new Map()
  let nextId = 1
  const reg = (o) => { const id = nextId++; nodes.set(id, o); return id }
  const NULL_EL = new Elem(null)
  nodes.set(0, NULL_EL)
  const pack = (v) => {
    if (v instanceof Elem) return { __el: v.node ? reg(v) : 0 }
    if (Array.isArray(v) && v.length && v.every((x) => x instanceof Elem)) return { __els: v.map((x) => reg(x)) }
    return v
  }

  // ---- preferences ---------------------------------------------------------------------------------------------------
  const prefStore = deps.prefs || { get: () => undefined, set: () => {}, delete: () => {}, keys: () => [], clear: () => {} }
  let prefDefs = null
  const defsMangayomi = () => {
    if (prefDefs) return prefDefs
    try { prefDefs = JSON.parse(vm.runInContext('JSON.stringify(extention.getSourcePreferences())', ctx, { timeout: 5000 })) } catch { prefDefs = [] }
    return prefDefs
  }
  const defaultOf = (p) => {
    if (p.listPreference) { const l = p.listPreference; return l.entryValues?.[l.valueIndex ?? 0] }
    if (p.switchPreferenceCompat) return p.switchPreferenceCompat.value
    if (p.checkBoxPreference) return p.checkBoxPreference.value
    if (p.multiSelectListPreference) return p.multiSelectListPreference.values
    if (p.editTextPreference) return p.editTextPreference.value
    return undefined
  }
  const prefGet = (key) => {
    const stored = prefStore.get('p:' + key)
    if (stored !== undefined) return stored
    const def = defsMangayomi().find((p) => p.key === key)
    return def ? defaultOf(def) ?? null : null
  }

  // ---- network -------------------------------------------------------------------------------------------------------
  function decodeBody(buf, contentType) {
    let cs = /charset=["']?([\w-]+)/i.exec(contentType || '')?.[1]
    if (!cs && /html/i.test(contentType || '')) cs = /<meta[^>]+charset=["']?([\w-]+)/i.exec(buf.subarray(0, 2048).toString('latin1'))?.[1]
    try { return new TextDecoder(cs || 'utf-8').decode(buf) } catch { return buf.toString('utf8') }
  }
  async function http(method, url, headersJson, bodyJson, bodyKind) {
    if (!/^https?:\/\//i.test(url)) throw new Error('Only http(s) addresses can be requested: ' + url)
    const given = JSON.parse(headersJson || '{}') || {}
    const headers = { 'user-agent': UA }
    for (const [k, v] of Object.entries(given)) if (v != null && !/[\r\n]/.test(k + v)) headers[k.toLowerCase()] = String(v)
    let body
    if (bodyKind !== 'none' && method !== 'GET' && method !== 'HEAD') {
      const b = JSON.parse(bodyJson)
      if (b != null) {
        if (/application\/json/i.test(headers['content-type'] || '')) body = typeof b === 'string' ? b : JSON.stringify(b)
        else if (typeof b === 'string') body = b
        else if (Array.isArray(b)) body = Buffer.from(b)
        else {
          body = new URLSearchParams(Object.entries(b).map(([k, v]) => [k, String(v)])).toString()
          headers['content-type'] ||= 'application/x-www-form-urlencoded; charset=utf-8'
        }
      }
    }
    const res = await doFetch(url, { method, headers, body, redirect: 'follow', signal: AbortSignal.timeout(HTTP_TIMEOUT) })
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length > MAX_BODY) throw new Error('Response is too large')
    const hs = {}
    res.headers.forEach((v, k) => { hs[k] = v })
    if (res.headers.getSetCookie) { const sc = res.headers.getSetCookie(); if (sc.length) hs['set-cookie'] = sc.join(', ') }
    return JSON.stringify({
      body: decodeBody(buf, hs['content-type']), headers: hs, isRedirect: false, persistentConnection: true, reasonPhrase: res.statusText || '', statusCode: res.status,
      request: { contentLength: null, finalized: true, followRedirects: true, headers, maxRedirects: 5, method, persistentConnection: true, url: res.url || url },
    })
  }
  const fetchApi = (url, init = {}) => {
    const headers = { 'user-agent': UA, ...(init.headers && typeof init.headers.forEach === 'function' && !Array.isArray(init.headers) ? Object.fromEntries(init.headers.entries?.() || []) : init.headers || {}) }
    return doFetch(url, { redirect: 'follow', signal: AbortSignal.timeout(HTTP_TIMEOUT), ...init, headers })
  }
  const fetchText = async (url, init, encoding) => {
    const res = await fetchApi(url, init)
    const buf = Buffer.from(await res.arrayBuffer())
    try { return new TextDecoder(encoding || 'utf-8').decode(buf) } catch { return buf.toString('utf8') }
  }

  // ---- host surface visible to the context ---------------------------------------------------------------------------
  const lnKey = (k) => 'ln:' + k
  const h = {
    log: (m) => log(String(m)),
    http,
    newDoc: (html) => reg(new Doc(html)),
    node: (id, member, argsJson) => {
      const o = nodes.get(id)
      if (!o) return 'null'
      const v = o[member]
      const r = typeof v === 'function' ? v.apply(o, JSON.parse(argsJson)) : v
      const out = pack(r)
      return JSON.stringify(out === undefined ? null : out)
    },
    prefGet: (key) => JSON.stringify(prefGet(key) ?? null),
    strGet: (key, def) => { const v = prefStore.get('s:' + key); return v === undefined ? def : String(v) },
    strSet: (key, value) => { prefStore.set('s:' + key, value); return true },
    crypto: (name, argsJson) => {
      const a = JSON.parse(argsJson)
      const r = bridge[name](...a)
      return typeof r === 'string' ? r : JSON.stringify(r)
    },
    fetchApi,
    fetchText,
    lnGet: (k) => { const v = prefStore.get(lnKey(k)); return v === undefined ? undefined : String(v) },
    lnSet: (k, v) => { prefStore.set(lnKey(k), v) },
    lnDel: (k) => { prefStore.delete(lnKey(k)) },
    lnClear: () => { for (const k of prefStore.keys()) if (k.startsWith('ln:')) prefStore.delete(k) },
    lnKeys: () => JSON.stringify(prefStore.keys().filter((k) => k.startsWith('ln:')).map((k) => k.slice(3))),
  }

  const sandbox = {
    __h: h,
    __mods: { cheerio, htmlparser2, dayjs },
    setTimeout, clearTimeout, setInterval, clearInterval,
    URL, URLSearchParams, TextEncoder, TextDecoder, Headers: globalThis.Headers, FormData: globalThis.FormData, Blob: globalThis.Blob,
    AbortController, atob: globalThis.atob, btoa: globalThis.btoa, structuredClone,
    fetch: lang === 'lnreader' ? fetchApi : undefined,
    console: {},
  }
  const ctx = vm.createContext(sandbox, { codeGeneration: { strings: true, wasm: false } })
  const run = (s, name, timeout = 15000) => vm.runInContext(s, ctx, { filename: name, timeout })

  let loaded = false
  function load() {
    if (loaded) return
    run(COMMON_SETUP, 'common-setup')
    if (lang === 'mangayomi') {
      run(MANGAYOMI_SETUP.replace('__SOURCE__', JSON.stringify(src)), 'mangayomi-setup')
      try { run(String(code) + '\nvar extention = new DefaultExtension();', `source-${src.name}.js`) }
      catch (e) { throw new Error(`Extension "${src.name}" failed while loading the source: ${msgOf(e)}`) }
    } else {
      run(LNREADER_SETUP, 'lnreader-setup')
      try { run(String(code) + '\nvar extension = module.exports.default || module.exports;', `plugin-${src.name}.js`) }
      catch (e) { throw new Error(`Plugin "${src.name}" failed while loading: ${msgOf(e)}`) }
    }
    loaded = true
  }

  // Evaluate `expr` in the context, await it, and return its JSON-decoded result.
  async function evalJson(expr, what) {
    load()
    if (nodes.size > 200000) { nodes = new Map([[0, NULL_EL]]); nextId = 1 }
    let out
    try {
      out = await run(`jsonStringify(() => (${expr}))`, what, 20000)
    } catch (e) {
      const m = msgOf(e)
      if (/not implemented/.test(m)) throw Object.assign(new Error(m), { notImplemented: true })
      throw new Error(`Extension "${src.name}" failed while ${what}: ${m}`)
    }
    return out === undefined ? undefined : JSON.parse(out)
  }
  const evalSync = (expr, what, fallback) => {
    load()
    try { const r = run(`JSON.stringify(${expr})`, what, 5000); return r === undefined ? fallback : JSON.parse(r) } catch (e) {
      if (/not implemented/.test(msgOf(e))) return fallback
      throw new Error(`Extension "${src.name}" failed while ${what}: ${msgOf(e)}`)
    }
  }
  const J = JSON.stringify

  // ---- normalisation -------------------------------------------------------------------------------------------------
  const str = (v) => (v == null ? '' : String(v))
  const strs = (v) => (Array.isArray(v) ? v.map(String) : typeof v === 'string' && v ? v.split(',').map((s) => s.trim()).filter(Boolean) : [])
  const ms = (v) => { const n = Number(v); if (Number.isFinite(n) && n > 0) return n; const t = Date.parse(String(v ?? '')); return Number.isFinite(t) ? t : 0 }
  const entry = (m) => ({ title: str(m.name), url: str(m.link), cover: str(m.imageUrl), description: str(m.description), author: str(m.author), artist: str(m.artist), genre: strs(m.genre), status: Number.isInteger(m.status) ? m.status : 5 })
  const guessNumber = (name) => {
    const s = String(name || '')
    const m = /(?:chapter|chap|ch|episode|ep|vol|volume|#)\.?\s*(\d+(?:\.\d+)?)/i.exec(s) || /(\d+(?:\.\d+)?)/.exec(s)
    return m ? Number(m[1]) : null
  }
  const LN_STATUS = { Ongoing: 0, Completed: 1, 'Publishing Finished': 4, 'On Hiatus': 2, Cancelled: 3 }

  const api = {
    language: lang,
    source: src,

    supportsLatest() {
      if (lang === 'lnreader') return true
      return evalSync('extention.supportsLatest', 'checking latest support', true) !== false
    },

    async headers() {
      if (lang === 'lnreader') return evalSync('(extension.imageRequestInit && extension.imageRequestInit.headers) || extension.headers || {}', 'reading headers', {}) || {}
      const r = evalSync(`extention.getHeaders(${J(src.baseUrl)})`, 'reading headers', {})
      return r && typeof r === 'object' ? Object.fromEntries(Object.entries(r).map(([k, v]) => [k, String(v)])) : {}
    },

    async popular(page) { return this._list('popular', page) },
    async latest(page) { return this._list('latest', page) },
    async search(query, page, filters = []) { return this._list('search', page, query, filters) },

    async _list(kind, page, query = '', filters = []) {
      if (lang === 'lnreader') {
        const expr = kind === 'search'
          ? `extension.searchNovels(${J(query)}, ${page})`
          : `extension.popularNovels(${page}, { showLatestNovels: ${kind === 'latest'}, filters: ${filtersExprLn(filters)} })`
        const items = (await evalJson(expr, kind === 'search' ? 'searching' : 'loading the list')) || []
        const list = items.filter((n) => n && n.path).map((n) => ({ title: str(n.name), url: str(n.path), cover: str(n.cover), description: '', author: '', artist: '', genre: [], status: 5 }))
        return { list, hasNextPage: list.length > 0 }
      }
      const expr = kind === 'popular' ? `extention.getPopular(${page})` : kind === 'latest' ? `extention.getLatestUpdates(${page})` : `extention.search(${J(query)}, ${page}, ${J(filters || [])})`
      const r = (await evalJson(expr, kind === 'search' ? 'searching' : 'loading the list')) || {}
      return { list: (r.list || []).map(entry), hasNextPage: !!r.hasNextPage }
    },

    // chapters come back OLDEST FIRST
    async detail(url) {
      if (lang === 'lnreader') {
        const n = (await evalJson(`extension.parseNovel(${J(url)})`, 'loading details')) || {}
        let chapters = n.chapters || []
        const total = Number(n.totalPages) || 0
        if (chapters.length === 0 || total > 1) {
          const all = [...chapters]
          let pageNum = all.length ? 2 : 1
          const max = total || 50
          while (pageNum <= max) {
            let p
            try { p = await evalJson(`extension.parsePage(${J(n.path || url)}, ${J(String(pageNum))})`, 'loading a chapter page') } catch { break }
            const more = p?.chapters || []
            if (!more.length) break
            all.push(...more)
            if (!total && (more.length < 5 || pageNum >= 50)) break
            pageNum++
          }
          if (all.length) chapters = all
        }
        return {
          title: str(n.name), url: str(n.path || url), cover: str(n.cover), description: str(n.summary), author: str(n.author), artist: str(n.artist),
          genre: strs(n.genres), status: LN_STATUS[n.status] ?? 5,
          chapters: chapters.filter((c) => c && c.path).map((c) => ({ name: str(c.name), url: str(c.path), date: ms(c.releaseTime), scanlator: Array.isArray(c.scanlator) ? c.scanlator.join(', ') : str(c.scanlator), number: Number.isFinite(Number(c.chapterNumber)) && c.chapterNumber != null ? Number(c.chapterNumber) : guessNumber(c.name) })),
        }
      }
      const m = (await evalJson(`extention.getDetail(${J(url)})`, 'loading details')) || {}
      const list = Array.isArray(m.chapters) ? m.chapters : Array.isArray(m.episodes) ? m.episodes : []
      return {
        ...entry(m), url: str(m.link || url),
        chapters: list.filter((c) => c && c.url).map((c) => ({ name: str(c.name), url: str(c.url), date: ms(c.dateUpload), scanlator: str(c.scanlator), number: guessNumber(c.name) })).reverse(),
      }
    },

    async pages(url) {
      if (lang === 'lnreader') throw new Error('Novel plugins have text chapters, not image pages')
      const r = (await evalJson(`extention.getPageList(${J(url)})`, 'loading pages')) || []
      const seen = new Set()
      return r.filter((p) => p != null).map((p) => (typeof p === 'string' ? { url: p.trim(), headers: {} } : { url: str(p.url).trim(), headers: p.headers || {} })).filter((p) => p.url && !seen.has(p.url) && seen.add(p.url))
    },

    async videos(url) {
      if (lang === 'lnreader') throw new Error('Novel plugins have no videos')
      const r = (await evalJson(`extention.getVideoList(${J(url)})`, 'loading video links')) || []
      const seen = new Set()
      return r.filter((v) => v && v.url).map((v) => ({
        url: str(v.url).trim(), originalUrl: str(v.originalUrl || v.url).trim(), quality: str(v.quality).trim(), headers: v.headers || {},
        subtitles: (v.subtitles || []).filter((t) => t?.file).map((t) => ({ file: str(t.file).trim(), label: str(t.label).trim() })),
        // Any array field with "audio" in its name; items may use file or url, label or lang.
        audios: Object.keys(v).filter((k) => /audio/i.test(k) && Array.isArray(v[k])).flatMap((k) => v[k]).map((t) => (typeof t === 'string' ? { file: t, label: '' } : { file: t?.file || t?.url, label: t?.label || t?.lang || t?.name || '' }))
          .filter((t) => t.file).map((t) => ({ file: str(t.file).trim(), label: str(t.label).trim() })),
        fields: Object.keys(v).map((k) => (Array.isArray(v[k]) ? `${k}[${v[k].length}]` : k)).join(', '),
      })).filter((v) => !seen.has(v.url + v.originalUrl) && seen.add(v.url + v.originalUrl))
    },

    // Text of a chapter (novels)
    async html(name, url) {
      if (lang === 'lnreader') return str(await evalJson(`extension.parseChapter(${J(url)})`, 'loading the chapter'))
      let html = str(await evalJson(`extention.getHtmlContent(${J(name || '')}, ${J(url)})`, 'loading the chapter'))
      try { html = str(await evalJson(`extention.cleanHtmlContent(${J(html)})`, 'cleaning the chapter')) } catch (e) { if (!e.notImplemented) throw e }
      return html
    },

    async filters() {
      if (lang === 'lnreader') return { kind: 'lnreader', filters: evalSync('extension.filters', 'reading filters', {}) || {} }
      return { kind: 'mangayomi', filters: evalSync('extention.getFilterList()', 'reading filters', []) || [] }
    },

    // Normalised preference list: [{ key, type, title, summary, value, entries?, entryValues? }]
    async prefs() {
      if (lang === 'lnreader') {
        const s = evalSync('extension.pluginSettings', 'reading settings', null)
        if (!s || typeof s !== 'object') return []
        return Object.entries(s).filter(([, v]) => v && typeof v === 'object').map(([key, v]) => {
          const stored = prefStore.get('p:' + key)
          const type = /switch|checkbox/i.test(v.type || '') || typeof v.value === 'boolean' ? 'switch' : 'text'
          return { key, type, title: str(v.label || key), summary: str(v.description), value: stored !== undefined ? stored : v.value ?? (type === 'switch' ? false : '') }
        })
      }
      defsMangayomi()
      return prefDefs.filter((p) => p?.key && PREF_KINDS.some((k) => p[k])).map((p) => {
        const stored = prefStore.get('p:' + p.key)
        const base = { key: p.key }
        if (p.listPreference) { const l = p.listPreference; return { ...base, type: 'list', title: str(l.title), summary: str(l.summary), entries: l.entries || [], entryValues: l.entryValues || [], value: stored !== undefined ? stored : l.entryValues?.[l.valueIndex ?? 0] } }
        if (p.multiSelectListPreference) { const l = p.multiSelectListPreference; return { ...base, type: 'multi', title: str(l.title), summary: str(l.summary), entries: l.entries || [], entryValues: l.entryValues || [], value: stored !== undefined ? stored : l.values || [] } }
        if (p.switchPreferenceCompat || p.checkBoxPreference) { const l = p.switchPreferenceCompat || p.checkBoxPreference; return { ...base, type: 'switch', title: str(l.title), summary: str(l.summary), value: stored !== undefined ? stored : !!l.value } }
        const l = p.editTextPreference
        return { ...base, type: 'text', title: str(l.title), summary: str(l.summary), value: stored !== undefined ? stored : str(l.value) }
      })
    },
    setPref(key, value) { prefStore.set('p:' + String(key), value); return true },

    dispose() { nodes = new Map(); loaded = false },
  }

  // LNReader's filters are a { key: {type, value, ...} } object; the UI may send { key: newValue } overrides.
  function filtersExprLn(sel) {
    if (sel && typeof sel === 'object' && !Array.isArray(sel) && Object.keys(sel).length) {
      return `(function(){ var f = JSON.parse(JSON.stringify(extension.filters || {})); var s = ${J(sel)}; for (var k in s) if (f[k]) f[k].value = s[k]; return f; })()`
    }
    return 'extension.filters'
  }

  return api
}

module.exports = { createEngine, UA }
