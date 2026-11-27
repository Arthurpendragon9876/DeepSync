'use strict'
// Reads a DeepSync/Yugen (Android) backup, "*.bk.zip" (Kototoro and Usagi write the same layout, so those load too), and converts the parts both apps can share into Yomi's
// own backup shape, so the result goes straight through the normal `backup:import` merge.
//
// What maps, and why only this much:
//   Android (Kotatsu based) identifies a title by *source + URL*; Yomi identifies it by its *AniList id*.
//   The only bridge between the two is the AniList link the Android app stores when a title is tracked
//   (the "scrobbling" section, scrobbler id 2). Titles without that link cannot be placed in Yomi and are
//   returned in `report.unmatched` instead of being guessed.
const fs = require('fs')
const os = require('os')
const path = require('path')
const { Zip } = require('./zip')

const ANILIST_SCROBBLER = 2 // ScrobblerService.ANILIST in the Android app
const SUPPORTED_FORMAT = 1 // BackupIndex.FORMAT_VERSION
// Kototoro and Usagi are Kotatsu forks with the same section layout and the same AniList scrobbler id (2). Their
// index has no format_version, so the app id is what identifies them.
const FOREIGN_APPS = {
  'org.skepsun.kototoro': 'Kototoro', 'org.skepsun.kototoro.debug': 'Kototoro', 'org.skepsun.kototoro.nightly': 'Kototoro',
  'org.draken.usagi': 'Usagi', 'org.draken.usagi.debug': 'Usagi',
}
// Kototoro tags what a tracker entry is for; Yugen and Usagi only ever track manga.
const modeOf = (mediaType) => { const t = String(mediaType || '').toLowerCase(); return t === 'anime' ? 'anime' : t === 'novel' ? 'novel' : 'manga' }
const catId = (id) => `android-${id}`

async function readSection(zip, name) {
  if (!zip.has(name)) return []
  const text = (await zip.readText(name)).trim()
  if (!text) return []
  const data = JSON.parse(text)
  return Array.isArray(data) ? data : [data]
}

async function readAndroidBackup(file) {
  const zip = await Zip.open(file)
  try {
    const [index] = await readSection(zip, 'index')
    const foreign = index && FOREIGN_APPS[index.app_id]
    if (!index || (index.format_version == null && !foreign)) throw new Error('Not an Android DeepSync, Kototoro or Usagi backup (no index entry)')
    if (index.format_version > SUPPORTED_FORMAT) throw new Error(`Backup format ${index.format_version} is newer than this Yomi understands`)
    return {
      index,
      app: foreign || 'DeepSync',
      categories: await readSection(zip, 'categories'),
      favourites: await readSection(zip, 'favourites'),
      history: await readSection(zip, 'history'),
      scrobbling: await readSection(zip, 'scrobbling'),
    }
  } finally {
    await zip.close()
  }
}

// -> { favourites, history, report } in Yomi's backup shape. Titles land in the mode of their AniList entry (Kototoro
// can track anime and novels; Yugen and Usagi are manga only).
function toYomi(b) {
  const anilist = new Map() // android manga id -> { id: AniList id, mode }
  for (const s of b.scrobbling) if (s.scrobbler === ANILIST_SCROBBLER && s.target_id > 0) anilist.set(s.manga_id, { id: s.target_id, mode: modeOf(s.media_type) })

  const categories = b.categories.slice().sort((a, c) => a.sort_key - c.sort_key).map((c) => ({ id: catId(c.category_id), name: c.title }))
  const items = {}
  const unmatched = []
  let favCount = 0
  for (const f of b.favourites) {
    if (f.deleted_at) continue // Kototoro keeps removed favourites as tombstones
    const link = anilist.get(f.manga_id)
    if (!link) { unmatched.push({ title: f.manga.title, source: f.manga.source, where: 'favourites' }); continue }
    const { id, mode } = link
    const key = `${mode}:${id}`
    const cat = catId(f.category_id)
    const it = items[key] || (items[key] = { key, mode, id, title: f.manga.title, cover: f.manga.cover_url || null, cats: [], at: f.created_at })
    if (!it.cats.includes(cat)) it.cats.push(cat)
    favCount++
  }

  const history = {}
  for (const h of b.history) {
    if (h.deleted_at) continue
    const link = anilist.get(h.manga_id)
    if (!link) { if (!unmatched.some((u) => u.title === h.manga.title)) unmatched.push({ title: h.manga.title, source: h.manga.source, where: 'history' }); continue }
    const { id, mode } = link
    const key = `${mode}:${id}`
    const prev = history[key]
    if (prev && prev.at >= h.updated_at) continue
    history[key] = { key, mode, id, title: h.manga.title, cover: h.manga.cover_url || null, number: null, label: 'Imported from Android', count: 1, at: h.updated_at }
  }

  // keep only categories that exist in the backup; make sure favourites never point at a missing one
  const known = new Set(categories.map((c) => c.id))
  for (const it of Object.values(items)) it.cats = it.cats.filter((c) => known.has(c))
  for (const k of Object.keys(items)) if (!items[k].cats.length) delete items[k]

  return {
    favourites: { categories, items },
    history,
    report: {
      app: b.app || 'DeepSync',
      favourites: Object.keys(items).length,
      history: Object.keys(history).length,
      categories: categories.length,
      unmatched,
      totals: { favourites: b.favourites.length, history: b.history.length, anilistLinks: anilist.size },
    },
  }
}

async function convertFile(file) { return toYomi(await readAndroidBackup(file)) }

// For the renderer: it sends the file as base64, we spill it to a temp file because Zip reads by random access.
async function convertBuffer(buf) {
  const tmp = path.join(os.tmpdir(), `yomi-android-backup-${process.pid}-${Date.now()}.zip`)
  await fs.promises.writeFile(tmp, buf)
  try { return await convertFile(tmp) } finally { fs.promises.unlink(tmp).catch(() => {}) }
}

module.exports = { readAndroidBackup, toYomi, convertFile, convertBuffer }
