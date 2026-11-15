'use strict'
const fs = require('fs')
const path = require('path')
const { openCached, IMAGE_RE, natural } = require('./zip')
const { openEpub } = require('./epub')

const VIDEO_RE = /\.(mkv|mp4|webm|avi|mov|m4v|ts)$/i
const COMIC_RE = /\.(cbz|zip)$/i
const EPUB_RE = /\.epub$/i

const norm = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
const key = (type, title) => `${type}:${norm(title)}`

function cleanTitle(s) {
  return s
    .replace(/\[[^\]]*\]|\([^)]*\)|【[^】]*】/g, ' ')
    .replace(/\b(1080p|720p|480p|2160p|4k|x26[45]|h\.?26[45]|hevc|aac|flac|web-?dl|web-?rip|bd-?rip|bluray|10-?bit|multi-?subs?|dual-?audio)\b/gi, ' ')
    .replace(/[_.]+/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/[\s\-–:]+$/g, '')
    .trim()
}

// "[Group] Show Name - 07 (1080p).mkv" -> { title: 'Show Name', episode: 7 }
function parseEpisode(file) {
  const name = path.basename(file).replace(/\.[^.]+$/, '')
  const stripped = name.replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
  const tries = [
    /^(.*?)[\s._-]*\bS\d{1,2}\s?E(\d{1,4})\b/i,
    /^(.*?)[\s._-]*\b(?:EP?|Episode)[\s._]?(\d{1,4})\b/i,
    /^(.*?)\s-\s(\d{1,4})(?:v\d)?\b/,
    /^(.*?)[\s._-]+(\d{1,3})$/,
  ]
  for (const re of tries) {
    const m = re.exec(stripped.trim())
    if (m) return { title: cleanTitle(m[1]), episode: parseInt(m[2], 10) }
  }
  return { title: cleanTitle(stripped), episode: null }
}

function parseChapter(name, fallback) {
  const base = name.replace(/\.[^.]+$/, '')
  const m = /(?:ch(?:apter)?\.?\s*|c)(\d+(?:\.\d+)?)/i.exec(base) || /(\d+(?:\.\d+)?)(?!.*\d)/.exec(base)
  return m ? parseFloat(m[1]) : fallback
}

async function* walk(dir, depth = 0) {
  let items
  try {
    items = await fs.promises.readdir(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const it of items) {
    if (it.name.startsWith('.')) continue
    const full = path.join(dir, it.name)
    if (it.isDirectory()) {
      yield { dir: full, name: it.name, isDir: true, depth }
      if (depth < 4) yield* walk(full, depth + 1)
    } else yield { file: full, name: it.name, depth }
  }
}

async function scanAnime(roots) {
  const groups = new Map()
  for (const root of roots)
    for await (const it of walk(root)) {
      if (!it.file || !VIDEO_RE.test(it.name)) continue
      let { title, episode } = parseEpisode(it.file)
      const parent = path.basename(path.dirname(it.file))
      if (!title || title.length < 2) title = cleanTitle(parent)
      const k = key('anime', title)
      const size = (await fs.promises.stat(it.file)).size
      if (!groups.has(k)) groups.set(k, { key: k, type: 'anime', title, files: [] })
      groups.get(k).files.push({ path: it.file, name: it.name, episode, size })
    }
  const out = [...groups.values()]
  for (const g of out) g.files.sort((a, b) => (a.episode ?? 1e9) - (b.episode ?? 1e9) || natural(a.name, b.name))
  return out.sort((a, b) => natural(a.title, b.title))
}

async function scanManga(roots) {
  const groups = new Map()
  const add = (title, f) => {
    const k = key('manga', title)
    if (!groups.has(k)) groups.set(k, { key: k, type: 'manga', title, files: [] })
    groups.get(k).files.push(f)
  }
  for (const root of roots) {
    const top = await fs.promises.readdir(root, { withFileTypes: true }).catch(() => [])
    for (const t of top) {
      if (t.name.startsWith('.')) continue
      const full = path.join(root, t.name)
      if (t.isFile() && COMIC_RE.test(t.name)) {
        add(cleanTitle(t.name.replace(/\.[^.]+$/, '').replace(/[\s._-]*(?:ch(?:apter)?\.?\s*|c|v|vol\.?\s*)?\d+(?:\.\d+)?$/i, '')) || cleanTitle(t.name), {
          path: full, name: t.name, kind: 'cbz', chapter: parseChapter(t.name, 1),
        })
      } else if (t.isDirectory()) {
        const kids = await fs.promises.readdir(full, { withFileTypes: true }).catch(() => [])
        const title = cleanTitle(t.name)
        const hasImages = kids.some((k) => k.isFile() && IMAGE_RE.test(k.name))
        if (hasImages) add(title, { path: full, name: t.name, kind: 'dir', chapter: parseChapter(t.name, 1) })
        for (const k of kids) {
          if (k.name.startsWith('.')) continue
          const kp = path.join(full, k.name)
          if (k.isFile() && COMIC_RE.test(k.name)) add(title, { path: kp, name: k.name, kind: 'cbz', chapter: parseChapter(k.name, 1) })
          else if (k.isDirectory()) {
            const inner = await fs.promises.readdir(kp).catch(() => [])
            if (inner.some((n) => IMAGE_RE.test(n))) add(title, { path: kp, name: k.name, kind: 'dir', chapter: parseChapter(k.name, 1) })
          }
        }
      }
    }
  }
  const out = [...groups.values()]
  for (const g of out) g.files.sort((a, b) => a.chapter - b.chapter || natural(a.name, b.name))
  return out.sort((a, b) => natural(a.title, b.title))
}

async function scanNovels(roots) {
  const out = []
  for (const root of roots)
    for await (const it of walk(root)) {
      if (!it.file || !EPUB_RE.test(it.name)) continue
      let title = cleanTitle(it.name.replace(/\.epub$/i, ''))
      let author
      try {
        const info = await openEpub(it.file)
        title = info.title || title
        author = info.author
      } catch {}
      out.push({ key: key('novel', title), type: 'novel', title, author, files: [{ path: it.file, name: it.name, kind: 'epub', size: (await fs.promises.stat(it.file)).size }] })
    }
  return out.sort((a, b) => natural(a.title, b.title))
}

async function scan(paths) {
  const [anime, manga, novel] = await Promise.all([scanAnime(paths.anime || []), scanManga(paths.manga || []), scanNovels(paths.novel || [])])
  return { anime, manga, novel, scannedAt: Date.now() }
}

// Pages of one manga chapter (cbz entry names, or image files of a folder), in reading order.
async function chapterPages(file) {
  const st = await fs.promises.stat(file.path)
  if (st.isDirectory()) {
    return (await fs.promises.readdir(file.path)).filter((n) => IMAGE_RE.test(n)).sort(natural).map((n) => ({ kind: 'file', path: path.join(file.path, n) }))
  }
  const zip = await openCached(file.path)
  return zip.list().filter((n) => IMAGE_RE.test(n) && !/(^|\/)__MACOSX\//.test(n)).sort(natural).map((n) => ({ kind: 'zip', path: file.path, entry: n }))
}

module.exports = { scan, chapterPages, parseEpisode, parseChapter, cleanTitle, norm }
