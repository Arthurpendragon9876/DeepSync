'use strict'
// Every operation the UI can request. No Electron imports here, so the same handlers run under
// Electron IPC (main.js), the browser dev harness (tools/dev-server.js) and the test-suite.
const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')
const { createClient, modeFilter } = require('./anilist')
const metadataLib = require('./metadata')
const demo = require('./demo')
const scanner = require('./scanner')
const epub = require('./epub')
const mangadex = require('./sources/mangadex')
const extensionsManager = require('./extensions/manager')
const sysvolume = require('./sysvolume')

function createApi({ store, zipUrl, proxyUrl, streamUrl = (u) => u, dialogs = {}, fetchImpl }) {
  const al = createClient({ getToken: () => store.settings.trackers.anilist.token, cache: store.cache, fetchImpl })
  const metadata = metadataLib.createMetadata({ fetchImpl, cache: store.cache, getHideAdult: () => store.settings.hideAdult, getClientId: () => store.settings.trackers.mal?.clientId })
  const sources = { mangadex: mangadex.create({ fetchImpl, getHideAdult: () => store.settings.hideAdult }) }
  const ext = extensionsManager.create({ dir: store.dir, store, fetchImpl, proxyUrl })
  const opened = new Set() // files the user explicitly picked; media protocol may serve them
  let lastScan = null
  const demoLists = {} // in-memory demo tracker lists so the tracking UI can be exercised offline

  const S = () => store.settings
  const isDemo = () => S().demo
  const hasToken = () => !!S().trackers.anilist.token
  const adult = () => (S().hideAdult ? { isAdult: false } : {})

  // Run a live query; when AniList is unreachable fall back to demo data and say so.
  async function live(fn, fallback) {
    if (isDemo()) return { data: fallback(), offline: true }
    try {
      return { data: await fn(), offline: false }
    } catch (e) {
      if (e.code === 'OFFLINE') return { data: fallback(), offline: true }
      throw e
    }
  }
  const demoList = (type) => (demoLists[type] ||= demo.userList(type))
  const serialise = (e) => ({ code: e.code, message: e.message })

  const isSrc = (id) => typeof id === 'string' && (id.startsWith('src-') || id.startsWith('meta-')) // (meta- = a MAL / MangaBaka title with no AniList match) titles opened straight from a source have no tracker id
  const h = {
    // ---- settings --------------------------------------------------------------------------------------------
    'settings:get': async () => store.publicSettings(),
    'settings:set': async (patch) => {
      // Tokens arrive here only from the Trackers screen.
      store.patchSettings(patch)
      return store.publicSettings()
    },

    // ---- metadata (AniList) ----------------------------------------------------------------------------------
    'anilist:browse': ({ mode = 'anime', ...o }) =>
      live(() => al.browse({ ...modeFilter(mode), ...adult(), ...o }), () => demo.browse({ ...modeFilter(mode), ...o })),
    // ---- metadata providers (AniList / MyAnimeList / MangaBaka), the same calls the mobile app makes ---------------------------
    'meta:supports': ({ provider, mode }) => metadata.supports(provider, mode),
    'meta:list': async ({ provider = 'anilist', kind = 'TRENDING', mode = 'anime', perPage = 20, include = [], exclude = [] }) => {
      if (provider === 'anilist') {
        const sort = kind === 'POPULAR' ? 'POPULARITY_DESC' : kind === 'RECENT' ? 'START_DATE_DESC' : 'TRENDING_DESC'
        const f = { ...modeFilter(mode), ...adult(), sort, perPage, genre_in: include, genre_not_in: exclude, ...(kind === 'RECENT' ? { status: 'RELEASING' } : {}) }
        return live(async () => (await al.browse(f)).items, () => demo.browse({ ...modeFilter(mode), sort, perPage }).items)
      }
      const has = (m, g) => m.genres.some((x) => x.toLowerCase() === g.toLowerCase())
      const items = (await metadata.list({ provider, kind, mode, limit: perPage * (include.length || exclude.length ? 3 : 1) }))
        .filter((m) => include.every((g) => has(m, g)) && !exclude.some((g) => has(m, g))).slice(0, perPage)
      return { data: items, offline: false }
    },
    'meta:search': async ({ provider = 'anilist', query, mode = 'anime', perPage = 24 }) => {
      if (provider === 'anilist') return live(async () => (await al.browse({ ...modeFilter(mode), ...adult(), search: query, sort: 'SEARCH_MATCH', perPage })).items, () => [])
      return { data: await metadata.list({ provider, mode, limit: perPage, search: query, kind: 'POPULAR' }), offline: false }
    },
    'meta:byId': async ({ provider, id, mode }) => (provider === 'anilist' ? al.details(Number(id)) : metadata.byId({ provider, id, mode })),
    // The tracker's entry for a title (opened from a source or a MAL / MangaBaka list): search by each name, rank by name + kind, null when unsure.
    'meta:resolve': async ({ provider = 'anilist', names = [], tags = [], sourceName = '', mode = 'anime' }) => {
      const profile = metadataLib.profileOf({ names, tags, sourceName })
      const pool = new Map()
      for (const name of profile.names.slice(0, 3)) {
        const found = provider === 'anilist'
          ? (await al.browse({ ...modeFilter(mode), search: name, sort: 'SEARCH_MATCH', perPage: 10 }).catch(() => ({ items: [] }))).items
          : await metadata.list({ provider, mode, limit: 10, search: name, kind: 'POPULAR' }).catch(() => [])
        found.forEach((m) => pool.has(m.id) || pool.set(m.id, m))
        if (pool.size >= 3 && metadataLib.bestMatch(profile, [...pool.values()])) break
      }
      return metadataLib.bestMatch(profile, [...pool.values()])
    },
    'anilist:details': ({ id }) => live(() => al.details(id), () => demo.details(id)),
    'anilist:schedule': ({ from, to }) => live(() => al.schedule(from, to), () => demo.schedule(from, to)),
    'anilist:recent': async () => {
      const now = Math.floor(Date.now() / 1000)
      return live(
        async () => {
          const items = await al.schedule(now - 3 * 86400, now)
          const seen = new Set()
          return items.filter((i) => !i.media.isAdult || !S().hideAdult).reverse().filter((i) => (seen.has(i.media.id) ? false : seen.add(i.media.id)))
        },
        () => demo.MEDIA.filter((m) => m.type === 'ANIME' && m.status === 'RELEASING').map((m, i) => ({ airingAt: now - i * 5400, episode: (m.nextAiringEpisode?.episode || 2) - 0, media: m })),
      )
    },

    // ---- tracker account -------------------------------------------------------------------------------------
    'anilist:login': async ({ token, clientId }) => {
      const t = S().trackers.anilist
      if (clientId != null) store.patchSettings({ trackers: { anilist: { clientId } } })
      if (!token) return { user: t.user }
      const prev = t.token
      store.patchSettings({ trackers: { anilist: { token: token.trim() } } })
      try {
        const user = await al.viewer()
        store.patchSettings({ trackers: { anilist: { user: { id: user.id, name: user.name, avatar: user.avatar?.large || user.avatar?.medium } } } })
        store.cache.clear()
        return { user: S().trackers.anilist.user }
      } catch (e) {
        store.patchSettings({ trackers: { anilist: { token: prev } } })
        throw e
      }
    },
    'anilist:logout': async () => {
      store.patchSettings({ trackers: { anilist: { token: '', user: null } } })
      store.cache.clear()
      return true
    },
    'anilist:viewer': async () => (isDemo() ? { id: 1, name: 'DemoUser', avatar: demo.viewer().avatar.large } : S().trackers.anilist.user),

    // ---- tracker lists / progress ----------------------------------------------------------------------------
    'anilist:list': async ({ mode = 'anime' }) => {
      const type = mode === 'anime' ? 'ANIME' : 'MANGA'
      let entries
      if (isDemo()) entries = demoList(type)
      else if (!hasToken()) return { data: [], needsLogin: true, offline: false }
      else {
        try {
          entries = await al.userList(S().trackers.anilist.user.id, type)
        } catch (e) {
          if (e.code === 'OFFLINE') return { data: demoList(type).filter((x) => (mode === 'novel') === (x.media.format === 'NOVEL')), offline: true }
          throw e
        }
      }
      return { data: entries.filter((x) => (mode === 'novel' ? x.media.format === 'NOVEL' : mode === 'manga' ? x.media.format !== 'NOVEL' : true)), offline: isDemo() }
    },
    'anilist:entry': async ({ mediaId, mode }) => {
      if (isSrc(mediaId)) return { data: store.progress['src:' + mediaId] ? { mediaId, ...store.progress['src:' + mediaId] } : null }
      if (isDemo()) return { data: demoList(mode === 'anime' ? 'ANIME' : 'MANGA').find((e) => e.mediaId === mediaId) || null }
      if (!hasToken()) return { data: null }
      return { data: await al.entryFor(mediaId) }
    },
    'anilist:save': async ({ mediaId, status, progress, score, mode }) => {
      if (isSrc(mediaId)) {
        const cur = store.progress['src:' + mediaId] || {}
        store.setProgress('src:' + mediaId, { status: status || cur.status || 'CURRENT', ...(progress != null && { progress }), ...(score != null && { score }) })
        return { mediaId, ...store.progress['src:' + mediaId] }
      }
      if (isDemo()) {
        const list = demoList(mode === 'anime' ? 'ANIME' : 'MANGA')
        let e = list.find((x) => x.mediaId === mediaId)
        if (!e) {
          const media = demo.MEDIA.find((m) => m.id === mediaId)
          list.push((e = { id: 9000 + mediaId, mediaId, status: 'PLANNING', progress: 0, score: 0, updatedAt: 0, media }))
        }
        Object.assign(e, { ...(status && { status }), ...(progress != null && { progress }), ...(score != null && { score }), updatedAt: Math.floor(Date.now() / 1000) })
        return e
      }
      return al.saveEntry({ mediaId, status, progress, score })
    },
    'anilist:remove': async ({ entryId, mediaId, mode }) => {
      if (isSrc(mediaId)) { delete store.progress['src:' + mediaId]; return true }
      if (isDemo()) {
        const type = mode === 'anime' ? 'ANIME' : 'MANGA'
        demoLists[type] = demoList(type).filter((e) => e.mediaId !== mediaId)
        return true
      }
      return al.deleteEntry(entryId)
    },
    // Called by the player / readers. Honours incognito and never moves progress backwards.
    'track:progress': async ({ mode, mediaId, progress, total }) => {
      if (S().incognito) return { skipped: 'incognito' }
      store.setProgress(`${mode}:${mediaId}`, { mode, mediaId, progress })
      if (!mediaId || (!isDemo() && !hasToken())) return { skipped: 'signed-out' }
      if (isSrc(mediaId)) return { saved: await h['anilist:save']({ mediaId, mode, status: total && progress >= total ? 'COMPLETED' : 'CURRENT', progress: Math.max(progress, store.progress['src:' + mediaId]?.progress || 0) }) }
      const cur = (await h['anilist:entry']({ mediaId, mode })).data
      if (cur && cur.progress >= progress && cur.status !== 'PLANNING') return { skipped: 'not-newer' }
      const status = total && progress >= total ? 'COMPLETED' : 'CURRENT'
      return { saved: await h['anilist:save']({ mediaId, mode, status, progress }) }
    },
    'progress:list': async () => store.progress,
    'progress:set': async ({ key, value }) => {
      if (S().incognito) return false
      store.setProgress(key, value)
      return true
    },

    // ---- local library ---------------------------------------------------------------------------------------
    'library:scan': async () => {
      lastScan = await scanner.scan(S().libraryPaths)
      return { ...lastScan, links: store.links }
    },
    'library:get': async () => (lastScan ? { ...lastScan, links: store.links } : h['library:scan']()),
    'library:link': async ({ localKey, mediaId }) => (store.setLink(localKey, mediaId), true),
    'library:match': async ({ title, mode }) => {
      if (isDemo()) {
        const m = demo.MEDIA.find((x) => x.title.romaji.toLowerCase() === title.toLowerCase())
        return m ? { media: m, score: 1 } : null
      }
      try {
        return await al.match(title, mode)
      } catch (e) {
        if (e.code === 'OFFLINE') return null
        throw e
      }
    },
    'library:pages': async ({ file }) => {
      if (!isAllowed(file.path)) throw new Error('Path is outside your library folders')
      return scanner.chapterPages(file)
    },
    'epub:open': async ({ path: p }) => (assertAllowed(p), epub.openEpub(p)),
    'epub:chapter': async ({ path: p, href }) => (assertAllowed(p), epub.chapterHtml(p, href, zipUrl)),

    // ---- online sources ----------------------------------------------------------------------------------------
    'sources:list': async () => Object.values(sources).map((s) => ({ id: s.id, name: s.name, enabled: !!S().sources[s.id] })),
    'sources:search': ({ source, query }) => sources[source].search(query),
    'sources:chapters': ({ source, id }) => sources[source].chapters(id),
    'sources:pages': ({ source, id }) => sources[source].pages(id),

    // ---- extensions (Aniyomi/Mihon APKs, run by the AnymeX bridge JAR) -------------------------------------------
    'ext:status': () => ext.status(),
    'ext:progress': () => ({ ...ext.runtime.state }),
    'ext:setup': ({ force } = {}) => ext.ensure({ force }),
    'ext:checkUpdate': ({ force } = {}) => ext.checkUpdate({ force }),
    'ext:setJava': async ({ javaPath }) => (store.patchSettings({ extensions: { javaPath: String(javaPath || '') } }), ext.stop(), ext.status()),
    'ext:accept': async () => (store.patchSettings({ extensions: { acceptedRisk: true } }), true),
    'ext:repos': async () => ext.repos(),
    'ext:repoAdd': ({ url }) => ext.addRepo(url),
    'ext:repoRemove': ({ url }) => ext.removeRepo(url),
    'ext:available': ({ type }) => ext.available(type),
    'ext:installed': () => ext.loadInstalled(true),
    'ext:install': (a) => {
      if (!S().extensions.acceptedRisk) throw new Error('Confirm the extension warning first')
      return ext.install(a)
    },
    'ext:uninstall': (a) => ext.uninstall(a),
    'ext:browse': (a) => ext.browse(a),
    'ext:detail': (a) => ext.detail(a),
    'ext:pages': (a) => ext.pages(a),
    'ext:videos': (a) => ext.videos(a),
    'ext:filters': (a) => ext.filters(a),
    'ext:autoMatch': (a) => ext.autoMatch(a),
    'ext:chapterText': (a) => ext.chapterText(a),
    'ext:prefs': ({ sourceId }) => ext.js.prefs(sourceId),
    'ext:setPref': ({ sourceId, key, value }) => ext.js.setPref(sourceId, key, value),
    // Turns an extension video into URLs the built-in player can load (headers applied by our local proxy).
    'ext:stream': ({ video }) => ({
      url: streamUrl(video.url, video.headers),
      hls: /\.m3u8(\?|#|$)/i.test(video.url),
      subtitles: (video.subtitles || []).map((t) => ({ label: t.label, url: streamUrl(t.url, video.headers) })),
      // Sources that keep the sound in its own file (Mangayomi `audios`, Aniyomi `audioTracks`): the player plays it alongside the video.
      audios: (video.audios || []).map((t) => ({ label: t.label || '', url: streamUrl(t.url, video.headers), hls: /\.m3u8(\?|#|$)/i.test(t.url) })),
    }),
    'ext:streamLog': () => require('./serve').streamLog.slice(-30),
    'ext:stop': async () => (ext.stop(), true),

    // ---- system ------------------------------------------------------------------------------------------------
    'system:volume': ({ delta }) => sysvolume.change(delta),
    'player:mpv': ({ file }) => (isAllowed(file) ? launchMpv(['--force-window=yes', ...mpvLangArgs(), file]) : { error: 'Path is outside your library folders' }),
    // Online (extension) video: headers such as Referer/User-Agent are passed through to mpv.
    'ext:mpv': ({ url, headers = {}, subtitles = [], audios = [], title }) => {
      if (!/^https?:\/\//i.test(url || '')) return { error: 'Not a web video address' }
      const args = ['--force-window=yes', ...mpvLangArgs()]
      for (const [k, v] of Object.entries(headers)) {
        if (/[\r\n]/.test(k + v)) continue
        if (/^user-agent$/i.test(k)) args.push('--user-agent=' + v)
        else if (/^referer$/i.test(k)) args.push('--referrer=' + v)
        else args.push('--http-header-fields-append=' + k + ': ' + v)
      }
      for (const t of subtitles) if (/^https?:\/\//i.test(t.url || '')) args.push('--sub-file=' + t.url)
      for (const t of audios) if (/^https?:\/\//i.test(t.url || '')) args.push('--audio-file=' + t.url)
      if (title) args.push('--force-media-title=' + String(title).replace(/[\r\n]/g, ' '))
      return launchMpv([...args, url])
    },

    'cache:clear': async () => (store.cache.clear(), true),
    'dialog:folder': async () => {
      const p = await dialogs.folder?.()
      return p || null
    },
    'dialog:file': async ({ kind }) => {
      const p = await dialogs.file?.(kind)
      if (p) opened.add(p)
      return p || null
    },
    'app:openDataDir': async () => (dialogs.openDataDir?.(), true),
    // ---- favourites, categories, history, stats ----------------------------------------------------------------
    'fav:list': async () => ({ categories: store.favourites.categories, items: Object.values(store.favourites.items) }),
    'fav:set': async ({ item, cats }) => (store.setFavourite(item, cats), { items: Object.values(store.favourites.items) }),
    'fav:categories': async ({ categories }) => {
      const clean = (categories || []).filter((c) => c?.id && String(c.name || '').trim()).map((c) => ({ id: String(c.id), name: String(c.name).trim().slice(0, 40) }))
      if (!clean.length) throw new Error('Keep at least one category')
      store.setCategories(clean)
      return { categories: store.favourites.categories }
    },
    'history:list': async () => Object.values(store.history).sort((a, b) => b.at - a.at).slice(0, 500),
    'history:add': async ({ entry }) => {
      if (S().incognito || !entry?.key) return false
      store.addHistory(entry)
      return true
    },
    'history:remove': async ({ key }) => (delete store.history[key], store._later('history', () => store.history), true),
    'history:clear': async () => ((store.history = {}), store._later('history', () => store.history), true),
    'stats:get': async () => {
      const day = (t) => new Date(t).toISOString().slice(0, 10)
      const byDay = {}, byMode = { anime: 0, manga: 0, novel: 0 }
      for (const a of store.activity) { byDay[day(a.t)] = (byDay[day(a.t)] || 0) + a.n; byMode[a.mode] = (byMode[a.mode] || 0) + a.n }
      let streak = 0
      for (let t = Date.now(); byDay[day(t)]; t -= 86400000) streak++
      if (!streak) for (let t = Date.now() - 86400000; byDay[day(t)]; t -= 86400000) streak++ // today may not have started yet
      const titles = Object.values(store.history)
      return { byDay, byMode, total: store.activity.length, streak, titles: titles.length, favourites: Object.keys(store.favourites.items).length, top: titles.sort((a, b) => (b.count || 0) - (a.count || 0)).slice(0, 5) }
    },
    'backup:export': async () => ({ settings: store.publicSettings(), progress: store.progress, links: store.links, favourites: store.favourites, history: store.history, activity: store.activity, exportedAt: new Date().toISOString() }),
    'backup:importAndroid': async ({ b64 }) => {
      const { convertBuffer } = require('./android-backup')
      const { favourites, history, report } = await convertBuffer(Buffer.from(String(b64 || ''), 'base64'))
      await h['backup:import']({ data: { favourites, history } })
      return report
    },
    'backup:import': async ({ data }) => {
      if (data.progress) Object.assign(store.progress, data.progress)
      if (data.links) Object.assign(store.links, data.links)
      if (data.favourites) {
        const ids = new Set(store.favourites.categories.map((c) => c.id))
        for (const c of data.favourites.categories || []) if (!ids.has(c.id)) store.favourites.categories.push(c)
        Object.assign(store.favourites.items, data.favourites.items || {})
        store._later('favourites', () => store.favourites)
      }
      if (data.history) { Object.assign(store.history, data.history); store._later('history', () => store.history) }
      if (Array.isArray(data.activity) && !store.activity.length) { store.activity = data.activity; store._later('activity', () => store.activity) }
      if (data.settings) {
        const s = JSON.parse(JSON.stringify(data.settings))
        for (const t of Object.values(s.trackers || {})) delete t.connected
        delete s.trackers // never import accounts
        store.patchSettings(s)
      }
      store.flush()
      return true
    },
  }

  // Same Sub/Dub choice as the built-in player: Sub = original audio + subtitles in your language, Dub = English audio, no subtitles.
  const MPV_LANGS = { en: 'eng,en', es: 'spa,es', pt: 'por,pt', fr: 'fre,fra,fr', de: 'ger,deu,de', it: 'ita,it', ar: 'ara,ar', ru: 'rus,ru', id: 'ind,id', tr: 'tur,tr', vi: 'vie,vi', th: 'tha,th', pl: 'pol,pl', zh: 'chi,zho,zh', ko: 'kor,ko', ja: 'jpn,ja' }
  const mpvLangArgs = () => {
    const pl = S().player || {}
    if (pl.audio === 'dub') return ['--alang=eng,en', '--sid=no']
    const sl = pl.subLang || 'en'
    return ['--alang=jpn,ja,jp', ...(sl === 'off' ? ['--sid=no'] : [`--slang=${MPV_LANGS[sl] || sl}`])]
  }

  function launchMpv(args) {
    return new Promise((resolve) => {
      const p = spawn(S().player.mpvPath || 'mpv', args, { detached: true, stdio: 'ignore' })
      p.once('error', (e) => resolve({ error: e.code === 'ENOENT' ? 'mpv was not found. Install it (e.g. `sudo pacman -S mpv`) or set its path in Settings > Player.' : e.message }))
      p.once('spawn', () => (p.unref(), resolve({ ok: true })))
    })
  }

  // A path is servable if it lives under a library folder or was picked through a dialog.
  function isAllowed(p) {
    if (!p) return false
    let real
    try {
      real = fs.realpathSync(p)
    } catch {
      return false
    }
    if (opened.has(p) || opened.has(real)) return true
    return Object.values(S().libraryPaths)
      .flat()
      .some((root) => {
        try {
          const r = fs.realpathSync(root)
          return real === r || real.startsWith(r + path.sep)
        } catch {
          return false
        }
      })
  }
  const assertAllowed = (p) => {
    if (!isAllowed(p)) throw new Error('Path is outside your library folders')
  }

  async function call(channel, args = {}) {
    try {
      const fn = Object.hasOwn(h, channel) && h[channel]
      if (!fn) throw new Error('Unknown channel: ' + channel)
      return { ok: true, value: await fn(args || {}) }
    } catch (e) {
      return { ok: false, error: serialise(e) }
    }
  }

  return { call, isAllowed, allow: (p) => opened.add(p), handlers: h, ext, shutdown: () => ext.stopAll() }
}

module.exports = { createApi }
