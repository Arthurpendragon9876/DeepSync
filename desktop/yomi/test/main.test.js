'use strict'
// Run with: npm test   (uses Node's built-in test runner, no dependencies)
// Fixtures are generated on the fly so the suite is self-contained.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const zlib = require('zlib')

const { Zip } = require('../main/zip')
const scanner = require('../main/scanner')
const epub = require('../main/epub')
const { createClient, similarity } = require('../main/anilist')
const { TtlCache, Store } = require('../main/store')
const mangadex = require('../main/sources/mangadex')
const { createApi } = require('../main/api')

// --- tiny zip writer for fixtures (stored + deflate) ----------------------------------------------------------
function makeZip(entries) {
  const crc = (buf) => {
    let c, crc = 0xffffffff
    for (const b of buf) {
      c = (crc ^ b) & 0xff
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      crc = (crc >>> 8) ^ c
    }
    return (crc ^ 0xffffffff) >>> 0
  }
  const locals = []
  const centrals = []
  let off = 0
  for (const [name, data, deflate] of entries) {
    const raw = Buffer.from(data)
    const body = deflate ? zlib.deflateRawSync(raw) : raw
    const n = Buffer.from(name)
    const lh = Buffer.alloc(30)
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0x800, 6); lh.writeUInt16LE(deflate ? 8 : 0, 8)
    lh.writeUInt32LE(crc(raw), 14); lh.writeUInt32LE(body.length, 18); lh.writeUInt32LE(raw.length, 22); lh.writeUInt16LE(n.length, 26)
    locals.push(lh, n, body)
    const ch = Buffer.alloc(46)
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x800, 8); ch.writeUInt16LE(deflate ? 8 : 0, 10)
    ch.writeUInt32LE(crc(raw), 16); ch.writeUInt32LE(body.length, 20); ch.writeUInt32LE(raw.length, 24); ch.writeUInt16LE(n.length, 28); ch.writeUInt32LE(off, 42)
    centrals.push(ch, n)
    off += 30 + n.length + body.length
  }
  const cd = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16)
  return Buffer.concat([...locals, cd, end])
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-test-'))
const w = (rel, data) => {
  const f = path.join(tmp, rel)
  fs.mkdirSync(path.dirname(f), { recursive: true })
  fs.writeFileSync(f, data)
  return f
}

test('zip: reads stored and deflated entries, natural order', async () => {
  const f = w('a.zip', makeZip([['b.txt', 'hello', false], ['dir/c.txt', 'x'.repeat(5000), true]]))
  const z = await Zip.open(f)
  assert.deepEqual(z.list().sort(), ['b.txt', 'dir/c.txt'])
  assert.equal(await z.readText('b.txt'), 'hello')
  assert.equal((await z.read('dir/c.txt')).length, 5000)
  await assert.rejects(() => z.read('nope'))
  await z.close()
  await assert.rejects(() => Zip.open(w('bad.zip', 'not a zip')))
})

test('scanner: episode and chapter parsing', () => {
  const p = scanner.parseEpisode
  assert.deepEqual(p('/x/[Grp] Tougen Anki - 07 (1080p) [AB12].mkv'), { title: 'Tougen Anki', episode: 7 })
  assert.deepEqual(p('/x/Show.Name.S01E05.720p.mp4'), { title: 'Show Name', episode: 5 })
  assert.deepEqual(p('/x/Another Show EP12.mkv'), { title: 'Another Show', episode: 12 })
  assert.equal(p('/x/Movie Title.mkv').episode, null)
  assert.equal(scanner.parseChapter('Hollow Crown - Chapter 012.cbz', 1), 12)
  assert.equal(scanner.parseChapter('c045.5.cbz', 1), 45.5)
})

test('scanner: scans anime, manga (cbz + folders) and novels', async () => {
  for (const n of [1, 2, 12]) w(`lib/anime/Tougen/[G] Tougen - ${String(n).padStart(2, '0')} (1080p).mkv`, 'x')
  const png = Buffer.from('89504e470d0a1a0a', 'hex')
  for (const c of [1, 2]) w(`lib/manga/Hollow Crown/Hollow Crown - Chapter ${c}.cbz`, makeZip([['10.png', png], ['2.png', png], ['1.png', png]]))
  w('lib/manga/Hollow Crown/Chapter 3/01.jpg', 'x')
  w('lib/novel/book.epub', makeEpub())
  const r = await scanner.scan({ anime: [path.join(tmp, 'lib/anime')], manga: [path.join(tmp, 'lib/manga')], novel: [path.join(tmp, 'lib/novel')] })
  assert.equal(r.anime.length, 1)
  assert.deepEqual(r.anime[0].files.map((f) => f.episode), [1, 2, 12])
  assert.equal(r.manga.length, 1)
  assert.deepEqual(r.manga[0].files.map((f) => f.chapter), [1, 2, 3])
  assert.equal(r.novel[0].title, 'Test & Novel')
  const pages = await scanner.chapterPages(r.manga[0].files[0])
  assert.deepEqual(pages.map((p) => p.entry), ['1.png', '2.png', '10.png'])
  const folder = await scanner.chapterPages(r.manga[0].files[2])
  assert.equal(folder[0].kind, 'file')
})

function makeEpub() {
  return makeZip([
    ['mimetype', 'application/epub+zip', false],
    ['META-INF/container.xml', '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>', true],
    ['OEBPS/content.opf', `<package><metadata xmlns:dc="x"><dc:title>Test &amp; Novel</dc:title><dc:creator>A. Writer</dc:creator></metadata>
      <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
      <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>`, true],
    ['OEBPS/nav.xhtml', '<nav><ol><li><a href="text/ch1.xhtml">Prologue</a></li><li><a href="text/ch2.xhtml#s">Chapter Two</a></li></ol></nav>', true],
    ['OEBPS/text/ch1.xhtml', '<html><head><script>alert(1)</script><style>p{}</style></head><body><h1>Prologue</h1><p onclick="x()" style="color:red">Hi</p><img src="../img/a.png"/><a href="javascript:evil()">x</a><iframe src="https://evil"></iframe><object data="x"></object></body></html>', true],
    ['OEBPS/text/ch2.xhtml', '<html><body><p>Two</p></body></html>', true],
  ])
}

test('epub: metadata, TOC labels, sanitised chapter with rewritten resources', async () => {
  const f = w('book.epub', makeEpub())
  const info = await epub.openEpub(f)
  assert.equal(info.title, 'Test & Novel')
  assert.equal(info.author, 'A. Writer')
  assert.deepEqual(info.chapters.map((c) => c.title), ['Prologue', 'Chapter Two'])
  assert.equal(info.chapters[0].href, 'OEBPS/text/ch1.xhtml')
  const html = await epub.chapterHtml(f, info.chapters[0].href, (file, entry) => `zip://${entry}`)
  assert.match(html, /<h1>Prologue<\/h1>/)
  assert.doesNotMatch(html, /script|onclick|javascript:|style=|iframe|object/i)
  assert.match(html, /src="zip:\/\/OEBPS\/img\/a\.png"/)
})

test('anilist: dynamic query only declares provided filters; errors map to codes', async () => {
  let seen
  const ok = (data) => async (url, init) => ((seen = JSON.parse(init.body)), { status: 200, json: async () => ({ data }) })
  const cache = new TtlCache()
  let c = createClient({ getToken: () => '', cache, fetchImpl: ok({ Page: { pageInfo: { hasNextPage: true }, media: [{ id: 1 }] } }) })
  const r = await c.browse({ type: 'ANIME', sort: 'TRENDING_DESC', search: null, genre_in: [], isAdult: false, bogus: 1 })
  assert.deepEqual(r, { items: [{ id: 1 }], hasNext: true })
  assert.match(seen.query, /\$type: MediaType/)
  assert.doesNotMatch(seen.query, /search|genre_in|bogus/)
  assert.equal(seen.variables.isAdult, false)

  c = createClient({ getToken: () => '', cache: new TtlCache(), fetchImpl: async () => { throw new Error('ENOTFOUND') } })
  await assert.rejects(() => c.details(1), { code: 'OFFLINE' })
  c = createClient({ getToken: () => '', cache: new TtlCache(), fetchImpl: async () => ({ status: 429, json: async () => ({}) }) })
  await assert.rejects(() => c.details(1), { code: 'RATE' })
  c = createClient({ getToken: () => '', cache: new TtlCache(), fetchImpl: ok({}) })
  await assert.rejects(() => c.viewer(), { code: 'AUTH' })

  // stale cache is served when offline
  const cache2 = new TtlCache()
  let online = true
  c = createClient({ getToken: () => '', cache: cache2, fetchImpl: async (u, i) => { if (!online) throw new Error('down'); return ok({ Media: { id: 7 } })(u, i) } })
  assert.equal((await c.details(7)).id, 7)
  for (const e of cache2.map.values()) e.exp = 0
  online = false
  assert.equal((await c.details(7)).id, 7)
})

test('anilist: saveEntry sends only provided fields and converts score', async () => {
  let seen
  const cache = new TtlCache()
  const c = createClient({ getToken: () => 'tok', cache, fetchImpl: async (u, i) => ((seen = { body: JSON.parse(i.body), auth: i.headers.Authorization }), { status: 200, json: async () => ({ data: { SaveMediaListEntry: { id: 1 } } }) }) })
  await c.saveEntry({ mediaId: 5, progress: 3.9, score: 8.5 })
  assert.equal(seen.auth, 'Bearer tok')
  assert.deepEqual(seen.body.variables, { mediaId: 5, progress: 3, scoreRaw: 85 })
  assert.doesNotMatch(seen.body.query, /\$status/)
})

test('anilist: title similarity', () => {
  assert.equal(similarity('One Piece', 'one piece'), 1)
  assert.ok(similarity('Tougen Anki', 'Tougen Anki: Nikko Kegon Falls Arc') > 0.55)
  assert.ok(similarity('Tougen Anki', 'Completely Different') < 0.4)
})

test('mangadex: parses search, chapters (dedupe, drop external) and pages', async () => {
  const routes = {
    '/manga': { data: [{ id: 'm1', attributes: { title: { en: 'Hollow Crown' }, status: 'ongoing', year: 2020 }, relationships: [{ type: 'cover_art', attributes: { fileName: 'c.jpg' } }] }] },
    '/manga/m1/feed': { total: 3, data: [
      { id: 'c2', attributes: { chapter: '2', pages: 5 } }, { id: 'c1', attributes: { chapter: '1', pages: 5 } },
      { id: 'c1b', attributes: { chapter: '1', pages: 5 } }, { id: 'ext', attributes: { chapter: '3', pages: 0 } } ] },
    '/at-home/server/c1': { baseUrl: 'https://cdn', chapter: { hash: 'h', data: ['1.png', '2.png'] } },
  }
  const md = mangadex.create({ fetchImpl: async (url) => ({ ok: true, json: async () => routes[new URL(url).pathname] }) })
  const s = await md.search('hollow')
  assert.equal(s[0].cover, 'https://uploads.mangadex.org/covers/m1/c.jpg.256.jpg')
  const ch = await md.chapters('m1')
  assert.deepEqual(ch.map((c) => c.chapter), [1, 2])
  assert.deepEqual((await md.pages('c1')).map((p) => p.url), ['https://cdn/data/h/1.png', 'https://cdn/data/h/2.png'])
})

test('api: demo mode, offline fallback, tracking, incognito, path guard', async () => {
  const store = new Store(fs.mkdtempSync(path.join(tmp, 'data-')))
  const offline = async () => { throw new Error('down') }
  const api = createApi({ store, zipUrl: (f, e) => e, fetchImpl: offline })

  let r = await api.call('anilist:browse', { mode: 'novel', sort: 'TRENDING_DESC', perPage: 5 })
  assert.ok(r.ok && r.value.offline && r.value.data.items.length > 0)
  assert.ok(r.value.data.items.every((m) => m.format === 'NOVEL'))
  r = await api.call('anilist:browse', { mode: 'manga', perPage: 50 })
  assert.ok(r.value.data.items.every((m) => m.type === 'MANGA' && m.format !== 'NOVEL'))

  assert.equal((await api.call('anilist:list', { mode: 'anime' })).value.needsLogin, true)
  await api.call('settings:set', { demo: true })
  const list = (await api.call('anilist:list', { mode: 'anime' })).value.data
  assert.ok(list.length > 3)

  const id = list.find((e) => e.status === 'CURRENT').mediaId
  const t = await api.call('track:progress', { mode: 'anime', mediaId: id, progress: 11, total: 12 })
  assert.equal(t.value.saved.progress, 11)
  assert.equal((await api.call('track:progress', { mode: 'anime', mediaId: id, progress: 5, total: 12 })).value.skipped, 'not-newer')
  assert.equal((await api.call('track:progress', { mode: 'anime', mediaId: id, progress: 12, total: 12 })).value.saved.status, 'COMPLETED')

  await api.call('settings:set', { incognito: true })
  assert.equal((await api.call('track:progress', { mode: 'anime', mediaId: id, progress: 1, total: 12 })).value.skipped, 'incognito')

  const pub = (await api.call('settings:get')).value
  assert.equal(pub.trackers.anilist.token, undefined)
  const bad = await api.call('library:pages', { file: { path: '/etc/passwd' } })
  assert.equal(bad.ok, false)
  assert.equal((await api.call('nope')).ok, false)
})

test('store: settings merge, persistence, cache TTL', async () => {
  const dir = fs.mkdtempSync(path.join(tmp, 'persist-'))
  const s = new Store(dir)
  s.patchSettings({ trackers: { anilist: { token: 'abc' } }, accent: 'violet' })
  assert.equal(s.settings.trackers.anilist.token, 'abc')
  assert.equal(s.settings.trackers.mal.token, '') // untouched sibling survives the deep merge
  s.setProgress('anime:1', { progress: 3 })
  s.flush()
  const s2 = new Store(dir)
  assert.equal(s2.settings.accent, 'violet')
  assert.equal(s2.progress['anime:1'].progress, 3)
  const c = new TtlCache()
  c.set('k', 1, -1)
  assert.equal(c.get('k'), undefined)
  assert.equal(c.get('k', { stale: true }), 1)
})
