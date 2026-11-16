'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const zlib = require('zlib')
const { PassThrough } = require('stream')
const { EventEmitter } = require('events')

const { decodeIndex } = require('../main/extensions/pb')
const { Sidecar } = require('../main/extensions/sidecar')
const mgr = require('../main/extensions/manager')
const { parseJavaMajor } = require('../main/extensions/runtime')
const { proxyResponse, proxyUrlFor, b64 } = require('../main/serve')
const { Store } = require('../main/store')
const { createApi } = require('../main/api')

// ---- protobuf fixture writer ------------------------------------------------------------------------------------------
const varint = (n) => { n = BigInt(n); const o = []; do { let b = Number(n & 0x7fn); n >>= 7n; if (n) b |= 0x80; o.push(b) } while (n); return Buffer.from(o) }
const fStr = (f, s) => { const b = Buffer.isBuffer(s) ? s : Buffer.from(s); return Buffer.concat([varint((f << 3) | 2), varint(b.length), b]) }
const fInt = (f, n) => Buffer.concat([varint(f << 3), varint(n)])
function pbExtension({ name, pkg, apkUrl, iconUrl, code, version, warn, sources }) {
  return Buffer.concat([fStr(1, name), fStr(2, pkg), fStr(3, Buffer.concat([fStr(1, apkUrl), fStr(2, iconUrl)])), fInt(5, code), fStr(6, version), fInt(7, warn),
    ...sources.map((s) => fStr(8, Buffer.concat([fInt(1, s.id), fStr(2, s.name), fStr(3, s.lang), fStr(4, s.home)])))])
}
const pbIndex = (exts) => fStr(101, Buffer.concat(exts.map((e) => fStr(1, pbExtension(e)))))

const BIG_ID = '6404943692147160087' // > 2^53: must survive as a string
const SAMPLE = [
  { name: 'MangaDex', pkg: 'eu.kanade.tachiyomi.extension.all.mangadex', apkUrl: 'https://example.org/rel/tachiyomi-all.mangadex-v1.6.4.apk', iconUrl: 'https://example.org/i.png', code: 106004, version: '1.6.4', warn: 2, sources: [{ id: BIG_ID, name: 'MangaDex', lang: 'all', home: 'https://mangadex.org' }] },
  { name: 'Some Anime', pkg: 'eu.kanade.tachiyomi.animeextension.en.some', apkUrl: 'https://example.org/rel/aniyomi-en.some-v14.1.apk', iconUrl: '', code: 1, version: '14.1', warn: 0, sources: [{ id: 7, name: 'Some', lang: 'en', home: '' }] },
]

test('pb: decodes an index.pb, keeping 64-bit source ids exact', () => {
  const out = decodeIndex(pbIndex(SAMPLE))
  assert.equal(out.length, 2)
  assert.equal(out[0].pkg, 'eu.kanade.tachiyomi.extension.all.mangadex')
  assert.equal(out[0].sources[0].id, BIG_ID)
  assert.equal(out[0].apk, 'tachiyomi-all.mangadex-v1.6.4.apk')
  assert.equal(out[0].nsfw, 1)
  assert.equal(out[1].nsfw, 0)
  assert.deepEqual(decodeIndex(Buffer.from('garbage')), [])
})

test('parseIndex: JSON (plain + gzip), pb, repo.json pointer, type detection and apk url building', () => {
  const json = [{ name: 'Tachiyomi: Foo', pkg: 'eu.kanade.tachiyomi.extension.en.foo', apk: 'tachiyomi-en.foo-v1.4.1.apk', lang: 'en', version: '1.4.1', nsfw: 0, sources: [{ id: '1', name: 'Foo', lang: 'en' }] },
    { name: 'Aniyomi: Bar', pkg: 'eu.kanade.tachiyomi.animeextension.en.bar', apk: 'aniyomi-en.bar-v14.1.apk', lang: 'en', version: '14.1' },
    { name: 'Bad', pkg: '../../etc/passwd', apk: 'x.apk' }]
  const url = 'https://host/repo/index.min.json'
  for (const bytes of [Buffer.from(JSON.stringify(json)), zlib.gzipSync(Buffer.from(JSON.stringify(json)))]) {
    const { list } = mgr.parseIndex(bytes, url)
    assert.equal(list.length, 2, 'unsafe package names are dropped')
    assert.equal(list[0].name, 'Foo'); assert.equal(list[0].type, 'manga'); assert.equal(list[1].type, 'anime')
    assert.equal(list[0].apkUrl, 'https://host/repo/apk/tachiyomi-en.foo-v1.4.1.apk')
    assert.equal(list[0].iconUrl, 'https://host/repo/icon/eu.kanade.tachiyomi.extension.en.foo.png')
  }
  assert.equal(mgr.parseIndex(pbIndex(SAMPLE), 'https://h/index.pb').list[1].type, 'anime')
  assert.equal(mgr.parseIndex(pbIndex(SAMPLE), 'https://h/index.pb').list[0].apkUrl, SAMPLE[0].apkUrl)
  assert.equal(mgr.parseIndex(Buffer.from('{"index_v2":"https://x/index.pb","meta":{"name":"K"}}'), 'https://x/repo.json').follow, 'https://x/index.pb')
})

test('versions and repo links', () => {
  assert.ok(mgr.cmpVersion('1.4.23', '1.4.9') > 0)
  assert.ok(mgr.cmpVersion('14.1', '14.1.0') === 0)
  assert.ok(mgr.cmpVersion('1.0', '1.0.1') < 0)
  assert.equal(mgr.normaliseRepoUrl('aniyomi://add-repo?url=https%3A%2F%2Fa.b%2Findex.min.json'), 'https://a.b/index.min.json')
  assert.throws(() => mgr.normaliseRepoUrl('file:///etc/passwd'))
  assert.throws(() => mgr.normaliseRepoUrl('javascript:alert(1)'))
})

test('runtime: java version parsing', () => {
  assert.equal(parseJavaMajor('openjdk version "21.0.12.1" 2026-08-18'), 21)
  assert.equal(parseJavaMajor('openjdk version "17.0.12" 2024-07-16'), 17)
  assert.equal(parseJavaMajor('java version "1.8.0_402"'), 8)
  assert.equal(parseJavaMajor('command not found'), 0)
})

// ---- fake Java process ------------------------------------------------------------------------------------------------
function fakeSpawn(handler) {
  return () => {
    const proc = new EventEmitter()
    proc.stdout = new PassThrough(); proc.stderr = new PassThrough(); proc.stdin = new PassThrough()
    proc.exitCode = null; proc.killed = false
    proc.kill = () => { proc.killed = true; proc.exitCode = 0; proc.emit('exit', 0) }
    let buf = ''
    proc.stdin.on('data', (d) => {
      buf += d
      let i
      while ((i = buf.indexOf('\n')) >= 0) {
        const req = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1)
        Promise.resolve(handler(req, proc)).then((res) => res !== undefined && proc.stdout.write(JSON.stringify({ id: req.id, ...res }) + '\n'))
      }
    })
    setImmediate(() => proc.stderr.write('AnymeX Sidecar Process Started\n'))
    return proc
  }
}

test('sidecar: request/response by id, errors, noise on stdout, exit rejects pending', async () => {
  const sc = new Sidecar({ javaPath: 'java', jarPath: 'x.jar', spawnImpl: fakeSpawn((req, proc) => {
    if (req.method === 'ping') return { data: 'pong' }
    if (req.method === 'boom') return { error: 'kaput' }
    if (req.method === 'noise') { proc.stdout.write('not json at all\n'); return { data: [1, 2] } }
    if (req.method === 'die') { proc.exitCode = 1; proc.emit('exit', 1); return undefined }
  }) })
  assert.equal(await sc.call('ping'), 'pong')
  await assert.rejects(sc.call('boom'), /kaput/)
  assert.deepEqual(await sc.call('noise'), [1, 2])
  await assert.rejects(sc.call('die'), /exited/)
  sc.stop()
})

test('sidecar: timeout sends a cancel and rejects', async () => {
  const seen = []
  const sc = new Sidecar({ javaPath: 'java', jarPath: 'x.jar', spawnImpl: fakeSpawn((req) => { seen.push(req.method) }) })
  await assert.rejects(sc.call('slow', {}, { timeout: 40 }), /timed out/)
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(seen.includes('cancel'))
  sc.stop()
})

// ---- manager with a fake bridge ---------------------------------------------------------------------------------------
function setup({ bridge } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-ext-'))
  const store = new Store(dir)
  const jar = path.join(dir, 'bridge.jar'); fs.writeFileSync(jar, 'x')
  store.patchSettings({ extensions: { bridgeJar: jar, javaPath: process.execPath } }) // any executable that answers -version
  const calls = []
  const handler = bridge || ((req) => {
    calls.push(req)
    const a = req.args
    if (req.method === 'convertApk') { fs.writeFileSync(a.outJarPath, 'jar'); return { data: { success: true } } }
    if (req.method === 'loadExtensions') {
      const jars = fs.readdirSync(a.folderPath).filter((f) => f.endsWith('.jar'))
      return { data: jars.flatMap((j) => [{ id: BIG_ID, name: 'MangaDex', lang: 'en', type: 'manga', baseUrl: 'https://mangadex.org', pkgName: j.replace(/\.jar$/, ''), version: '1.6.4', supportsLatest: true, supportsPopular: true }]) }
    }
    if (req.method === 'unloadExtension') return { data: null }
    if (req.method === 'getPopular' || req.method === 'search') return { data: { list: [{ title: 'Berserk', url: '/manga/1', cover: 'https://img/c.jpg' }], hasNextPage: true } }
    if (req.method === 'getDetail') return { data: { title: 'Berserk', url: '/manga/1', cover: 'https://img/c.jpg', genre: ['Dark'], episodes: [{ name: 'Ch 3', url: '/c/3', chapter_number: 3, date_upload: 1 }, { name: 'Ch 2', url: '/c/2', chapter_number: 2 }, { name: 'Special', url: '/c/s', chapter_number: -1 }] } }
    if (req.method === 'getPageList') return { data: [{ url: 'https://img/p1.jpg', headers: { Referer: 'https://ref/' } }, { url: 'https://img/p2.jpg', headers: {} }] }
    if (req.method === 'getVideoList') return { data: [{ title: '1080p', quality: '1080p', url: 'https://v/1.m3u8', headers: { Referer: 'https://x/' }, subtitles: [{ file: 'https://s/en.vtt', label: 'English' }, { file: 'javascript:1', label: 'bad' }] }, { url: 'file:///etc/passwd' }] }
    return { error: 'Unknown method: ' + req.method }
  })
  const apk = Buffer.concat([Buffer.from([0x50, 0x4b, 3, 4]), Buffer.alloc(2000)])
  const fetchImpl = async (url) => {
    if (url.endsWith('index.pb')) return { ok: true, status: 200, arrayBuffer: async () => pbIndex(SAMPLE) }
    if (url.endsWith('.apk')) return { ok: true, status: 200, arrayBuffer: async () => apk }
    return { ok: false, status: 404 }
  }
  const m = mgr.create({ dir, store, fetchImpl, fixer: async () => {}, proxyUrl: proxyUrlFor('p:'), sidecarFactory: () => new Sidecar({ javaPath: 'java', jarPath: 'x', spawnImpl: fakeSpawn(handler) }) })
  return { m, store, dir, calls }
}
const REPO = 'https://example.org/index.pb'

test('manager: add repo, list available, install, update flag, uninstall', async () => {
  const { m, dir, store } = setup()
  assert.deepEqual(await m.addRepo(REPO), [REPO])
  assert.deepEqual(store.settings.extensions.repos, [REPO])
  let av = await m.available('manga')
  assert.equal(av.list.length, 1); assert.equal(av.list[0].installed, false)
  const list = await m.install({ pkg: 'eu.kanade.tachiyomi.extension.all.mangadex', apkUrl: 'http://evil.example/ignored.apk' })
  assert.equal(list.length, 1)
  assert.ok(fs.existsSync(path.join(dir, 'extensions', 'aniyomi', 'eu.kanade.tachiyomi.extension.all.mangadex.jar')))
  av = await m.available('manga')
  assert.equal(av.list[0].installed, true); assert.equal(av.list[0].hasUpdate, false)
  assert.equal((await m.available('anime')).list.length, 1)
  assert.equal((await m.uninstall({ pkg: 'eu.kanade.tachiyomi.extension.all.mangadex' })).length, 0)
  assert.equal(fs.readdirSync(path.join(dir, 'extensions', 'aniyomi')).length, 0)
  m.stop()
})

test('manager: refuses bad package names, unknown packages and non-APK downloads', async () => {
  const { m } = setup()
  await m.addRepo(REPO)
  await assert.rejects(m.install({ pkg: '../evil' }), /Invalid/)
  await assert.rejects(m.install({ pkg: 'com.not.in.repo' }), /not found in your repos/)
  await assert.rejects(m.uninstall({ pkg: '../../x' }), /Invalid/)
  await assert.rejects(m.addRepo('ftp://x/y'), /http/)
  m.stop()
})

test('manager: browse / detail / pages / videos are normalised and routed through the proxy', async () => {
  const { m, calls } = setup()
  await m.addRepo(REPO); await m.install({ pkg: 'eu.kanade.tachiyomi.extension.all.mangadex' })
  const src = (await m.loadInstalled())[0]
  assert.equal(src.id, BIG_ID)
  const r = await m.browse({ sourceId: BIG_ID, kind: 'popular' })
  assert.equal(r.hasNextPage, true)
  assert.match(r.list[0].cover, /^p:\?u=/)
  assert.equal(calls.at(-1).args.isAnime, false)
  await m.browse({ sourceId: BIG_ID, kind: 'search', query: 'ber' })
  assert.equal(calls.at(-1).args.query, 'ber')
  const d = await m.detail({ sourceId: BIG_ID, media: { title: 'Berserk', url: '/manga/1' } })
  assert.deepEqual(d.chapters.map((c) => c.number), [null, 2, 3], 'oldest first; negative numbers become null')
  const pages = await m.pages({ sourceId: BIG_ID, chapter: d.chapters[2] })
  const h1 = JSON.parse(Buffer.from(new URL(pages[0].url.replace('p:', 'http://x/')).searchParams.get('h'), 'base64url'))
  assert.equal(h1.Referer, 'https://ref/')
  assert.equal(pages[0].kind, 'url')
  const v = await m.videos({ sourceId: BIG_ID, episode: { name: 'e', url: '/e' } })
  assert.equal(v.length, 1, 'non-http video links are dropped'); assert.equal(v[0].subtitles.length, 1)
  await assert.rejects(m.browse({ sourceId: 'nope' }), /not installed/)
  m.stop()
})

test('manager: bridge errors surface as messages; missing runtime gives NO_RUNTIME', async () => {
  const { m } = setup({ bridge: (req) => ({ data: { error: 'api.example down' } }) })
  await assert.rejects(m.loadInstalled(true), /api\.example down/)
  m.stop()
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-ext-'))
  const store = new Store(dir)
  store.patchSettings({ extensions: { javaPath: '/nonexistent/java' } })
  const m2 = mgr.create({ dir, store, fetchImpl: async () => ({ ok: false }) })
  await assert.rejects(m2.loadInstalled(), (e) => e.code === 'NO_RUNTIME' || /Java|runtime/.test(e.message))
})

// ---- proxy ------------------------------------------------------------------------------------------------------------
test('proxy: forwards extension headers, drops unsafe ones, rejects non-http', async () => {
  let seen
  const f = async (url, opts) => { seen = { url, headers: opts.headers }; return { status: 200, headers: new Map([['content-type', 'image/png']]), arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer } }
  const hdr = b64(JSON.stringify({ Referer: 'https://r/', 'X-Bad': 'a\r\nb: c', Host: 'evil', Cookie: 'a=b' }))
  const r = await proxyResponse(b64('https://img/x.png'), hdr, f)
  assert.equal(r.status, 200); assert.equal(r.headers['Content-Type'], 'image/png')
  assert.equal(seen.headers.Referer, 'https://r/'); assert.equal(seen.headers.Cookie, 'a=b')
  assert.ok(!('Host' in seen.headers) && !('X-Bad' in seen.headers))
  assert.ok(seen.headers['User-Agent'])
  assert.equal((await proxyResponse(b64('file:///etc/passwd'), '', f)).status, 400)
  assert.equal(proxyUrlFor('/proxy')('data:image/png;base64,AA'), 'data:image/png;base64,AA')
})

// ---- api wiring ---------------------------------------------------------------------------------------------------------
test('api: install needs the warning accepted; mpv only takes web addresses; secrets stay out of settings', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-api-'))
  const store = new Store(dir)
  const api = createApi({ store, zipUrl: () => '', fetchImpl: async () => ({ ok: false, status: 500 }) })
  const r = await api.call('ext:install', { pkg: 'a.b.c' })
  assert.equal(r.ok, false); assert.match(r.error.message, /warning/)
  assert.equal((await api.call('ext:mpv', { url: 'file:///etc/passwd' })).value.error, 'Not a web video address')
  assert.equal((await api.call('ext:accept')).ok, true)
  assert.equal(store.settings.extensions.acceptedRisk, true)
  assert.equal((await api.call('ext:repoAdd', { url: 'nope' })).ok, false)
  api.shutdown()
})

// ---- real bridge (opt in) -----------------------------------------------------------------------------------------------
const REAL = process.env.DEEPSYNC_BRIDGE_JAR
test('real bridge JAR: ping, empty scan, bad APK is rejected cleanly', { skip: !REAL && 'set DEEPSYNC_BRIDGE_JAR=/path/anymex_desktop_runtime.jar' }, async () => {
  const sc = new Sidecar({ javaPath: process.env.DEEPSYNC_JAVA || 'java', jarPath: REAL })
  try {
    assert.equal(await sc.call('ping', {}, { timeout: 30000 }), 'pong')
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-real-'))
    assert.deepEqual(await sc.call('loadExtensions', { folderPath: dir }, { timeout: 60000 }), [])
    const bad = path.join(dir, 'bad.apk'); fs.writeFileSync(bad, 'not an apk')
    await assert.rejects(sc.call('convertApk', { apkPath: bad, outJarPath: path.join(dir, 'o.jar') }, { timeout: 60000 }), /dex|zip/i)
  } finally { sc.stop() }
})

test('manager: a bare host name from extension code becomes a readable network error', async () => {
  const { m } = setup({ bridge: () => ({ data: { error: 'api.mangadex.org' } }) })
  await assert.rejects(m.loadInstalled(true), (e) => /Could not reach api\.mangadex\.org/.test(e.message) && e.code === 'OFFLINE')
  m.stop()
})

test('sidecar: falls back when the JVM rejects -noverify (Java 27+)', async () => {
  const tried = []
  const spawnImpl = (java, args) => {
    tried.push(args.filter((a) => /verif/i.test(a)))
    const proc = new EventEmitter()
    proc.stdout = new PassThrough(); proc.stderr = new PassThrough(); proc.stdin = new PassThrough()
    proc.exitCode = null; proc.killed = false
    proc.kill = () => { proc.killed = true; proc.exitCode = 0 }
    if (args.some((a) => /verif/i.test(a))) {
      setImmediate(() => { proc.stderr.write(`Unrecognized option: ${args.find((a) => /verif/i.test(a))}\nError: Could not create the Java Virtual Machine.\n`); proc.exitCode = 1; proc.emit('exit', 1) })
    } else {
      setImmediate(() => proc.stderr.write('AnymeX Sidecar Process Started\n'))
      proc.stdin.on('data', (d) => { const r = JSON.parse(String(d).trim()); proc.stdout.write(JSON.stringify({ id: r.id, data: 'pong' }) + '\n') })
    }
    return proc
  }
  const sc = new Sidecar({ javaPath: 'java', jarPath: 'x.jar', spawnImpl })
  assert.equal(await sc.call('ping'), 'pong')
  assert.deepEqual(tried, [['-noverify'], ['-XX:-BytecodeVerificationRemote'], []])
  sc.stop()
})

// Minimal "stored" zip writer so the test needs no archive library.
function zipStore(files) {
  const crcT = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
  const parts = []; const central = []; let off = 0
  for (const [name, data] of Object.entries(files)) {
    const n = Buffer.from(name); const h = Buffer.alloc(30); const c = crc(data)
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt32LE(c, 14); h.writeUInt32LE(data.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(n.length, 26)
    const ch = Buffer.alloc(46); ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt32LE(c, 16); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24); ch.writeUInt16LE(n.length, 28); ch.writeUInt32LE(off, 42)
    parts.push(h, n, data); central.push(Buffer.concat([ch, n])); off += 30 + n.length + data.length
  }
  const cd = Buffer.concat(central); const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length, 8); end.writeUInt16LE(central.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16)
  return Buffer.concat([...parts, cd, end])
}

test('ctorfix: restores a constructor R8 removed, and is idempotent', { skip: !REAL && 'set DEEPSYNC_BRIDGE_JAR' }, () => {
  const { execFileSync } = require('child_process')
  // class A { } declared abstract with no methods at all (so, no <init>)
  const cls = Buffer.from('cafebabe0000003d0005' + '0100014107000101001031' .slice(0, 0) + '010001410700010100106a6176612f6c616e672f4f626a656374070003' + '04200002000400000000000000000000', 'hex')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-fix-'))
  const jar = path.join(dir, 'a.jar'); fs.writeFileSync(jar, zipStore({ 'A.class': cls }))
  const run = () => execFileSync(process.env.DEEPSYNC_JAVA || 'java', ['-cp', REAL + path.delimiter + path.join(__dirname, '..', 'main', 'extensions', 'ctorfix.jar'), 'CtorFix', dir], { encoding: 'utf8' }).trim()
  assert.equal(run(), 'fixed 1')
  const out = execFileSync('unzip', ['-p', jar, 'A.class'])
  assert.ok(out.includes('<init>'), 'constructor present after fixing')
  assert.equal(run(), 'fixed 0', 'second run leaves it alone')
})

// ---- matching ---------------------------------------------------------------------------------------------------------
const match = require('../main/extensions/match')

test('match: title scoring follows the Android matcher', () => {
  assert.equal(match.score('Pick Me Up', 'Pick Me Up!'), 1)
  assert.equal(match.score('The Tea Shop', 'tea-shop (Official)'), 1)
  assert.ok(match.score('Solo Leveling', 'Solo Levelling') >= match.MIN_NAME_SCORE)
  assert.ok(match.score('One Piece', 'One Punch Man') < 0.85)
  assert.equal(match.score('', 'x'), 0)
  const media = { title: { english: 'Attack on Titan', romaji: 'Shingeki no Kyojin', native: '進撃の巨人' }, synonyms: ['AoT'] }
  assert.deepEqual(match.namesOf(media).slice(0, 3), ['Attack on Titan', 'Shingeki no Kyojin', '進撃の巨人'])
  const r = match.rank(media, [{ title: 'Shingeki no Kyojin' }, { title: 'Attack on Titan: Junior High' }, { title: 'Something else' }])
  assert.equal(r[0].item.title, 'Shingeki no Kyojin')
  assert.ok(!r.some((x) => x.item.title === 'Something else'))
})

test('manager.autoMatch: searches installed sources, ranks, honours language and preference, reports failures', async () => {
  const searches = []
  const { m } = setup({ bridge: (req) => {
    const a = req.args
    if (req.method === 'loadExtensions') return { data: [
      { id: '1', name: 'Alpha', lang: 'en', type: 'manga', pkgName: 'p.a', supportsLatest: true },
      { id: '2', name: 'Beta', lang: 'en', type: 'manga', pkgName: 'p.b', supportsLatest: true },
      { id: '3', name: 'Gamma', lang: 'fr', type: 'manga', pkgName: 'p.c', supportsLatest: true },
      { id: '4', name: 'Delta', lang: 'all', type: 'manga', pkgName: 'p.d', supportsLatest: true },
      { id: '5', name: 'Ani', lang: 'en', type: 'anime', pkgName: 'p.e', supportsLatest: true }] }
    if (req.method === 'search') {
      searches.push(a.sourceId + ':' + a.query)
      if (a.sourceId === '1') return { data: { list: [{ title: 'Pick Me Up!', url: '/a' }, { title: 'Unrelated', url: '/x' }], hasNextPage: false } }
      if (a.sourceId === '2') return { data: { list: [{ title: 'Pick Me Up', url: '/b' }], hasNextPage: false } }
      if (a.sourceId === '4') return { error: 'api.broken.org' }
      return { data: { list: [{ title: 'Pick Me Up', url: '/c' }], hasNextPage: false } }
    }
    return { error: 'Unknown method' }
  } })
  const media = { id: 7, title: { english: 'Pick Me Up', romaji: 'Pick Me Up' } }
  const r = await m.autoMatch({ media, mode: 'manga', preferred: '2' })
  assert.equal(r.best.sourceId, '2', 'tie on score goes to the preferred source')
  assert.equal(r.best.item.url, '/b')
  assert.equal(r.hits.length, 2)
  assert.ok(!searches.some((s) => s.startsWith('3:') || s.startsWith('5:')), 'other languages and anime sources are skipped')
  assert.equal(r.failed.length, 1); assert.match(r.failed[0].error, /Could not reach/)
  const only = await m.autoMatch({ media, mode: 'manga', sourceIds: ['3'] })
  assert.equal(only.best.sourceId, '3', 'an explicit source list overrides the language filter')
  const none = await m.autoMatch({ media: { id: 8, title: { english: 'Totally Different Series' } }, mode: 'manga' })
  assert.equal(none.best, null)
  m.stop()
})

// ---- streams ----------------------------------------------------------------------------------------------------------
const { streamResponse, streamUrlFor, rewritePlaylist } = require('../main/serve')

test('stream: HLS playlists are rewritten through the proxy with the source headers; other files pass through with Range', async () => {
  const mk = streamUrlFor('s:')
  const seen = []
  const playlist = '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\nhttps://cdn.example/seg2.ts?x=1\n'
  const f = async (url, o) => {
    seen.push({ url, headers: o.headers })
    if (url.endsWith('.m3u8')) return { ok: true, status: 200, url, headers: new Map([['content-type', 'application/vnd.apple.mpegurl']]), arrayBuffer: async () => Buffer.from(playlist) }
    return { ok: true, status: 206, url, headers: new Map([['content-type', 'video/mp4'], ['content-range', 'bytes 0-9/100']]), body: new ReadableStream({ start(c) { c.enqueue(new Uint8Array([1, 2, 3])); c.close() } }) }
  }
  const hdr = { Referer: 'https://site/' }
  const r = await streamResponse(b64('https://host/a/master.m3u8'), b64(JSON.stringify(hdr)), null, mk, f)
  assert.equal(r.status, 200); assert.equal(r.headers['Access-Control-Allow-Origin'], '*')
  assert.match(r.body, /URI="s:\?u=/)
  const lines = r.body.split('\n').filter((l) => l.startsWith('s:'))
  assert.equal(lines.length, 2)
  assert.equal(Buffer.from(new URL('http://x/?' + lines[0].split('?')[1]).searchParams.get('u'), 'base64url').toString(), 'https://host/a/seg1.ts')
  assert.equal(Buffer.from(new URL('http://x/?' + lines[1].split('?')[1]).searchParams.get('u'), 'base64url').toString(), 'https://cdn.example/seg2.ts?x=1')
  assert.deepEqual(JSON.parse(Buffer.from(new URL('http://x/?' + lines[0].split('?')[1]).searchParams.get('h'), 'base64url')), hdr, 'headers follow every segment')
  assert.equal(seen[0].headers.Referer, 'https://site/')
  const v = await streamResponse(b64('https://host/v.mp4'), b64('{}'), 'bytes=0-9', mk, f)
  assert.equal(v.status, 206); assert.equal(v.headers['content-range'], 'bytes 0-9/100'); assert.equal(seen[1].headers.Range, 'bytes=0-9')
  assert.equal((await streamResponse(b64('file:///etc/passwd'), '', null, mk, f)).status, 400)
  assert.equal((await streamResponse(b64('https://x/y.m3u8'), '', null, mk, async () => { throw new Error('down') })).status, 502)
  assert.equal(rewritePlaylist('#EXTM3U\n\nseg.ts', 'https://a/b/c.m3u8', {}, (u) => '<' + u + '>'), '#EXTM3U\n\n<https://a/b/seg.ts>')
})
