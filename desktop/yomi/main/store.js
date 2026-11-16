'use strict'
// Small JSON-file store: settings, reading/watching progress, local-library links and an API cache.
const fs = require('fs')
const path = require('path')

const DEFAULTS = {
  theme: 'sky', // one of the Android app's colour schemes (renderer/css/theme.css)
  colorMode: 'system', // dark | light | system (Windows apps follow the OS)
  amoled: false, // pure-black background in dark mode
  startupAnimation: true, // Yomi splash screen
  bannerStyle: 'spotlight', // spotlight | card | panels  (Yugen "Home layout")
  autoRotate: true,
  titleLanguage: 'romaji', // romaji | english | native
  hideAdult: true,
  metaSource: 'tracker', // legacy (tracker | source); fromTracker below wins once it has been set
  metadata: 'anilist', // anilist | mal | mangabaka: the tracker whose catalogue drives Home, search and titles opened from sources (global, every mode)
  fromTracker: true, // covers and info on a title opened from a source come from the tracker (true) or from the source (false)
  catalogue: false, // Home lists what the installed sources offer instead of the tracker's catalogue
  catalogueSource: { anime: '', manga: '', novel: '' }, // the source whose catalogue Home shows, per mode ('' = every installed source of that kind)
  homeFilters: { include: [], exclude: [] }, // genre filters applied to Home rows
  incognito: false,
  floatingButtons: true, // the floating preferences + mode buttons
  customTitlebar: false,
  mode: 'anime', // anime | manga | novel
  demo: false,
  hiddenCarousels: [], // keys of HomeCarousel rows that are switched off
  libraryPaths: { anime: [], manga: [], novel: [] },
  player: { mode: 'builtin', mpvPath: 'mpv', autoPick: true, quality: 'auto', audio: 'sub', subLang: 'en', autoNext: true, skipSeconds: 85, volumeScope: process.platform === 'win32' ? 'player' : 'system' }, // mode: builtin | mpv
  trackers: {
    anilist: { clientId: '56845', token: '', user: null }, // the app's own AniList client (redirect: https://anilist.co/api/v2/oauth/pin)
    mal: { token: '' },
    mangabaka: { token: '' },
  },
  sources: { mangadex: true },
  extensions: { repos: [], javaPath: '', bridgeJar: '', acceptedRisk: false, autoLangs: ['en', 'all'] },
  // Windows-only options (Settings > Windows). material: auto | mica | acrylic | tabbed | none
  windows: { material: 'auto', systemAccent: false, closeToTray: false, minimizeToTray: false, startWithWindows: false, notifications: true, taskbarProgress: true, checkUpdates: true },
}

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v)
function merge(base, patch) {
  const out = { ...base }
  for (const [k, v] of Object.entries(patch || {})) {
    out[k] = isObj(v) && isObj(base[k]) ? merge(base[k], v) : v
  }
  return out
}

class TtlCache {
  constructor(max = 200) {
    this.map = new Map()
    this.max = max
    this.dirty = false
  }
  get(key, { stale = false } = {}) {
    const e = this.map.get(key)
    if (!e) return undefined
    if (e.exp < Date.now() && !stale) return undefined
    return e.val
  }
  set(key, val, ttl) {
    this.map.delete(key)
    this.map.set(key, { val, exp: Date.now() + ttl })
    while (this.map.size > this.max) this.map.delete(this.map.keys().next().value)
    this.dirty = true
  }
  clear() {
    this.map.clear()
    this.dirty = true
  }
  toJSON() {
    return [...this.map.entries()]
  }
  load(entries) {
    for (const [k, v] of entries || []) this.map.set(k, v)
  }
}

class Store {
  constructor(dir) {
    this.dir = dir
    fs.mkdirSync(dir, { recursive: true })
    this.files = {}
    this.timers = {}
    const saved = this._read('settings', {})
    this.settings = merge(DEFAULTS, saved)
    if (saved.metaSource === 'source' && saved.fromTracker === undefined) this.settings.fromTracker = false // the old Source / Tracker choice carries over
    // An empty saved client ID (older versions) falls back to the app's own.
    if (!this.settings.trackers.anilist.clientId) this.settings.trackers.anilist.clientId = DEFAULTS.trackers.anilist.clientId
    this.progress = this._read('progress', {})
    this.links = this._read('links', {})
    this.favourites = this._read('favourites', { categories: [{ id: 'default', name: 'Favourites' }], items: {} })
    this.history = this._read('history', {}) // key -> latest entry for that title
    this.activity = this._read('activity', []) // [{ t, mode, n }] one row per chapter / episode finished (for Stats)
    this.cache = new TtlCache()
    this.cache.load(this._read('cache', []))
    this._cacheTimer = setInterval(() => this._flushCache(), 30000)
    this._cacheTimer.unref?.()
  }

  _file(name) {
    return path.join(this.dir, name + '.json')
  }
  _read(name, fallback) {
    try {
      return JSON.parse(fs.readFileSync(this._file(name), 'utf8'))
    } catch {
      return fallback
    }
  }
  _write(name, value) {
    const f = this._file(name)
    const tmp = f + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(value))
    fs.renameSync(tmp, f)
  }
  _later(name, getter) {
    clearTimeout(this.timers[name])
    this.timers[name] = setTimeout(() => this._write(name, getter()), 400)
  }
  _flushCache() {
    if (this.cache.dirty) {
      this.cache.dirty = false
      try {
        this._write('cache', this.cache)
      } catch {}
    }
  }

  patchSettings(patch) {
    this.settings = merge(this.settings, patch)
    this._later('settings', () => this.settings)
    return this.settings
  }
  // Renderer-safe copy: tokens never leave the main process.
  publicSettings() {
    const s = JSON.parse(JSON.stringify(this.settings))
    for (const t of Object.values(s.trackers)) {
      t.connected = !!t.token
      delete t.token
    }
    return s
  }
  setProgress(key, value) {
    this.progress[key] = { ...(this.progress[key] || {}), ...value, updatedAt: Date.now() }
    this._later('progress', () => this.progress)
  }
  setLink(localKey, mediaId) {
    if (mediaId == null) delete this.links[localKey]
    else this.links[localKey] = mediaId
    this._later('links', () => this.links)
  }
  setFavourite(item, cats) {
    if (!cats || !cats.length) delete this.favourites.items[item.key]
    else this.favourites.items[item.key] = { ...(this.favourites.items[item.key] || {}), ...item, cats, at: Date.now() }
    this._later('favourites', () => this.favourites)
  }
  setCategories(categories) {
    const ids = new Set(categories.map((c) => c.id))
    this.favourites.categories = categories
    for (const it of Object.values(this.favourites.items)) it.cats = it.cats.filter((c) => ids.has(c))
    this._later('favourites', () => this.favourites)
  }
  addHistory(e) {
    const prev = this.history[e.key]
    const changed = !prev || prev.number !== e.number || prev.label !== e.label
    this.history[e.key] = { ...(prev || {}), ...e, count: (prev?.count || 0) + (changed ? 1 : 0), at: Date.now() }
    if (changed) this.activity.push({ t: Date.now(), mode: e.mode, n: 1 })
    if (this.activity.length > 20000) this.activity = this.activity.slice(-15000)
    this._later('history', () => this.history)
    this._later('activity', () => this.activity)
  }
  flush() {
    for (const [name, getter] of [
      ['favourites', () => this.favourites],
      ['history', () => this.history],
      ['activity', () => this.activity],
      ['settings', () => this.settings],
      ['progress', () => this.progress],
      ['links', () => this.links],
    ]) {
      clearTimeout(this.timers[name])
      this._write(name, getter())
    }
    this.cache.dirty = true
    this._flushCache()
  }
}

module.exports = { Store, DEFAULTS, merge, TtlCache }
