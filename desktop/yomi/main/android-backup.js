'use strict'
// Reads a DeepSync/Yugen (Android) backup, "*.bk.zip", and converts the parts both apps can share into Yomi's
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
    if (!index || index.format_version == null) throw new Error('Not an Android DeepSync backup (no index entry)')
    if (index.format_version > SUPPORTED_FORMAT) throw new Error(`Backup format ${index.format_version} is newer than this Yomi understands`)
    return {
      index,
      categories: await readSection(zip, 'categories'),
      favourites: await readSection(zip, 'favourites'),
      history: await readSection(zip, 'history'),
      scrobbling: await readSection(zip, 'scrobbling'),
    }
  } finally {
    await zip.close()
  }
}

// -> { favourites, history, report } in Yomi's backup shape (manga only: the Android app has no anime or novels).
function toYomi(b) {
  const anilist = new Map() // android manga id -> AniList id
  for (const s of b.scrobbling) if (s.scrobbler === ANILIST_SCROBBLER && s.target_id > 0) anilist.set(s.manga_id, s.target_id)

  const categories = b.categories.slice().sort((a, c) => a.sort_key - c.sort_key).map((c) => ({ id: catId(c.category_id), name: c.title }))
  const items = {}
  const unmatched = []
  let favCount = 0
  for (const f of b.favourites) {
    const id = anilist.get(f.manga_id)
    if (!id) { unmatched.push({ title: f.manga.title, source: f.manga.source, where: 'favourites' }); continue }
    const key = `manga:${id}`
    const cat = catId(f.category_id)
    const it = items[key] || (items[key] = { key, mode: 'manga', id, title: f.manga.title, cover: f.manga.cover_url || null, cats: [], at: f.created_at })
    if (!it.cats.includes(cat)) it.cats.push(cat)
    favCount++
  }

  const history = {}
  for (const h of b.history) {
    const id = anilist.get(h.manga_id)
    if (!id) { if (!unmatched.some((u) => u.title === h.manga.title)) unmatched.push({ title: h.manga.title, source: h.manga.source, where: 'history' }); continue }
    const key = `manga:${id}`
    const prev = history[key]
    if (prev && prev.at >= h.updated_at) continue
    history[key] = { key, mode: 'manga', id, title: h.manga.title, cover: h.manga.cover_url || null, number: null, label: 'Imported from Android', count: 1, at: h.updated_at }
  }

  // keep only categories that exist in the backup; make sure favourites never point at a missing one
  const known = new Set(categories.map((c) => c.id))
  for (const it of Object.values(items)) it.cats = it.cats.filter((c) => known.has(c))
  for (const k of Object.keys(items)) if (!items[k].cats.length) delete items[k]

  return {
    favourites: { categories, items },
    history,
    report: {
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
