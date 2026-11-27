'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const zlib = require('zlib')
const { convertFile, toYomi } = require('../main/android-backup')

// minimal zip writer (deflate) so the fixture matches what the Android app produces: one JSON entry per section
function makeZip(entries) {
  const table = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = table[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
  const parts = [], central = []
  let offset = 0
  for (const [name, text] of Object.entries(entries)) {
    const raw = Buffer.from(text), data = zlib.deflateRawSync(raw), nm = Buffer.from(name)
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(8, 8)
    local.writeUInt32LE(crc(raw), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(raw.length, 22); local.writeUInt16LE(nm.length, 26)
    const cen = Buffer.alloc(46); cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6); cen.writeUInt16LE(8, 10)
    cen.writeUInt32LE(crc(raw), 16); cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(raw.length, 24); cen.writeUInt16LE(nm.length, 28); cen.writeUInt32LE(offset, 42)
    parts.push(local, nm, data); central.push(cen, nm); offset += 30 + nm.length + data.length
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length / 2, 8); end.writeUInt16LE(central.length / 2, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, cd, end])
}

const manga = (id, title, source = 'MANGADEX') => ({ id, title, url: '/' + id, public_url: 'https://x/' + id, cover_url: `https://c/${id}.jpg`, source })
const fixture = {
  index: JSON.stringify([{ app_id: 'org.koitharu.kotatsu', app_version: 1, created_at: 1, format_version: 1 }]),
  categories: JSON.stringify([{ category_id: 2, created_at: 1, sort_key: 1, title: 'Reading' }, { category_id: 1, created_at: 1, sort_key: 0, title: 'Plan' }]),
  favourites: JSON.stringify([
    { manga_id: 10, category_id: 1, created_at: 5, manga: manga(10, 'Linked A') },
    { manga_id: 10, category_id: 2, created_at: 6, manga: manga(10, 'Linked A') },
    { manga_id: 11, category_id: 1, created_at: 7, manga: manga(11, 'Not tracked') },
  ]),
  history: JSON.stringify([
    { manga_id: 10, created_at: 1, updated_at: 100, chapter_id: 1, page: 0, scroll: 0, manga: manga(10, 'Linked A') },
    { manga_id: 12, created_at: 1, updated_at: 50, chapter_id: 1, page: 0, scroll: 0, manga: manga(12, 'Orphan history') },
  ]),
  scrobbling: JSON.stringify([
    { scrobbler: 2, id: 1, manga_id: 10, target_id: 555 }, // AniList
    { scrobbler: 1, id: 2, manga_id: 11, target_id: 999 }, // Shikimori only: not usable
  ]),
}

test('converts linked favourites, categories and history; reports the rest', async () => {
  const f = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ab-')), 'b.bk.zip')
  fs.writeFileSync(f, makeZip(fixture))
  const { favourites, history, report } = await convertFile(f)
  assert.deepEqual(favourites.categories.map((c) => c.name), ['Plan', 'Reading']) // sorted by sort_key
  assert.deepEqual(Object.keys(favourites.items), ['manga:555'])
  assert.deepEqual(favourites.items['manga:555'].cats.sort(), ['android-1', 'android-2'])
  assert.equal(favourites.items['manga:555'].id, 555)
  assert.equal(history['manga:555'].at, 100)
  assert.deepEqual(report.unmatched.map((u) => u.title).sort(), ['Not tracked', 'Orphan history'])
  assert.deepEqual([report.favourites, report.history], [1, 1])
})

test('rejects a zip that is not an Android backup, and newer formats', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ab-'))
  const a = path.join(dir, 'a.zip'); fs.writeFileSync(a, makeZip({ other: '[]' }))
  await assert.rejects(convertFile(a), /Not an Android/)
  const b = path.join(dir, 'b.zip'); fs.writeFileSync(b, makeZip({ index: JSON.stringify([{ format_version: 99 }]) }))
  await assert.rejects(convertFile(b), /newer/)
})

test('toYomi tolerates empty sections', () => {
  const r = toYomi({ categories: [], favourites: [], history: [], scrobbling: [] })
  assert.deepEqual(r.favourites, { categories: [], items: {} })
})
