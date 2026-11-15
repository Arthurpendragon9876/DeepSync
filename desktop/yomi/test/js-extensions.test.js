'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')

const { Doc } = require('../main/extensions/js/dom')
const bridge = require('../main/extensions/js/bridge')
const { createEngine } = require('../main/extensions/js/engine')
const { createHost } = require('../main/extensions/js/host')
const { cleanNovelHtml } = require('../main/extensions/js/sanitize')
const jsx = require('../main/extensions/js')
const mgr = require('../main/extensions/manager')

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'ds-js-'))
const memPrefs = () => { const m = new Map(); return { get: (k) => m.get(k), set: (k, v) => m.set(k, v), delete: (k) => m.delete(k), keys: () => [...m.keys()], m } }

const SITE = {
  '/popular?page=1': '<div class="item"><a href="/m/1"><img data-src="/c1.jpg"><h3>Alpha</h3></a></div><div class="item"><a href="/m/2"><img src="/c2.jpg"><h3>Beta</h3></a></div>',
  '/m/1': '<h1>Alpha</h1><ul><li><a href="/m/1/c2">Chapter 2</a><i>Jan 5, 2024</i></li><li><a href="/m/1/c1">Chapter 1</a><i>2 days ago</i></li></ul>',
}
const MG_CODE = `class DefaultExtension extends MProvider {
  getHeaders() { return { Referer: this.source.baseUrl }; }
  async getPopular(page) {
    const r = await new Client().get(this.source.baseUrl + '/popular?page=' + page, this.getHeaders());
    const doc = new Document(r.body);
    return { list: doc.select('div.item').map(e => ({ name: e.selectFirst('h3').text, link: e.selectFirst('a').getHref, imageUrl: e.selectFirst('img').getDataSrc || e.selectFirst('img').getSrc })), hasNextPage: false };
  }
  async getDetail(url) {
    const r = await new Client().get(this.source.baseUrl + url);
    const doc = new Document(r.body);
    const lis = doc.select('li');
    const d = parseDates(lis.map(l => l.selectFirst('i').text), 'MMM d, yyyy', 'en');
    return { name: doc.selectFirst('h1').text, chapters: lis.map((l, i) => ({ name: l.selectFirst('a').text, url: l.selectFirst('a').getHref, dateUpload: d[i] })) };
  }
}`

function withServer(routes, fn) {
  return new Promise((resolve, reject) => {
    const srv = http.createServer((req, res) => {
      if (req.url === '/hang') return // never answers
      const body = routes[req.url]
      res.writeHead(body ? 200 : 404, { 'content-type': 'text/html; charset=utf-8' })
      res.end(body || 'nope')
    })
    srv.listen(0, '127.0.0.1', async () => {
      try { resolve(await fn(`http://127.0.0.1:${srv.address().port}`)) } catch (e) { reject(e) } finally { srv.closeAllConnections?.(); srv.close() }
    })
  })
}

test('dom: Mangayomi/Jsoup semantics (empty elements, pseudos, helpers)', () => {
  const d = new Doc('<div><ul><li class="a">One</li><li>Two <b>x</b></li><li>Three</li></ul><a href="/x" data-src="/d.png">Link</a></div>')
  assert.deepEqual(d.select('li:contains(two)').map((e) => e.text), ['Two x'])
  assert.deepEqual(d.select('li:eq(2)').map((e) => e.text), ['Three'])
  assert.deepEqual(d.select('li:gt(0)').map((e) => e.text), ['Two x', 'Three'])
  assert.deepEqual(d.select('li:containsOwn(two)').length, 1)
  assert.equal(d.selectFirst('a').getHref, '/x')
  assert.equal(d.selectFirst('a').getDataSrc, '/d.png')
  // a miss gives an empty element, never null or a throw
  const none = d.selectFirst('.missing')
  assert.equal(none.text, ''); assert.equal(none.attr('x'), ''); assert.equal(none.getHref, ''); assert.deepEqual(none.select('a'), []); assert.equal(none.selectFirst('b').text, '')
  assert.equal(d.select('li:bad-selector((').length, 0)
  assert.equal(d.selectFirst('li').nextElementSibling.text, 'Two x')
  assert.equal(d.selectFirst('li').hasAttr('class'), true)
  assert.deepEqual(d.xpath('//li/text()'), ['One', 'Two', 'Three'])
  assert.equal(d.xpathFirst('//a/@href'), '/x')
})

test('bridge: AES round trips, CryptoJS format, GCM, packer, dates', () => {
  const key = '0123456789abcdef', iv = 'fedcba9876543210'
  assert.equal(bridge.cryptoHandler(bridge.cryptoHandler('secret text', iv, key, true), iv, key, false), 'secret text')
  assert.equal(bridge.decryptAESCryptoJS(bridge.encryptAESCryptoJS('hello', 'pass'), 'pass'), 'hello')
  // known CryptoJS vector: AES.encrypt('Message','Secret Passphrase') style (salted) must at least start with Salted__
  assert.match(Buffer.from(bridge.encryptAESCryptoJS('x', 'p'), 'base64').toString('latin1'), /^Salted__/)
  const crypto = require('crypto'); const k = crypto.randomBytes(32), n = crypto.randomBytes(12)
  const c = crypto.createCipheriv('aes-256-gcm', k, n); const ct = Buffer.concat([c.update('gcm!'), c.final()]); const tag = c.getAuthTag()
  assert.equal(bridge.decryptAESGCM(ct.toString('base64'), k.toString('hex'), n.toString('hex'), tag.toString('hex')), 'gcm!')
  assert.equal(bridge.decryptAESGCM('AAAA', k.toString('hex'), n.toString('hex')), 'AAAA', 'failure hands the input back')
  const packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('0 1=\"2\";',3,3,'var|x|hello'.split('|')))"
  assert.equal(bridge.unpackJs(packed), 'var x="hello";')
  const [a, b, c2] = bridge.parseDates(['Jan 5, 2024', '3 hours ago', 'yesterday'], 'MMM d, yyyy', 'en').map(Number)
  assert.equal(new Date(a).getFullYear(), 2024); assert.equal(new Date(a).getMonth(), 0)
  assert.ok(Math.abs(Date.now() - 3 * 3600000 - b) < 5000)
  assert.ok(Math.abs(Date.now() - 86400000 - c2) < 5000)
})

test('engine: Mangayomi source end to end (Client, Document, dates, preferences, not-implemented)', async () => {
  const seen = []
  const fakeFetch = async (url, init) => { seen.push({ url, init }); const p = new URL(url).pathname + new URL(url).search; return SITE[p] ? new Response(SITE[p], { status: 200 }) : new Response('x', { status: 404 }) }
  const prefs = memPrefs()
  const e = createEngine({ source: { id: 1, name: 'T', baseUrl: 'https://site.test', lang: 'en' }, code: MG_CODE + `
    ;DefaultExtension.prototype.getSourcePreferences = function () { return [{ key: 'q', listPreference: { title: 'Q', entries: ['L', 'H'], entryValues: ['l', 'h'], valueIndex: 1 } }]; }`, language: 'mangayomi', deps: { fetch: fakeFetch, prefs } })
  const pop = await e.popular(1)
  assert.deepEqual(pop.list.map((x) => [x.title, x.url, x.cover]), [['Alpha', '/m/1', '/c1.jpg'], ['Beta', '/m/2', '/c2.jpg']])
  assert.equal(seen[0].init.headers.referer, 'https://site.test', 'extension headers reach the request')
  assert.match(seen[0].init.headers['user-agent'], /Mozilla/)
  const d = await e.detail('/m/1')
  assert.deepEqual(d.chapters.map((c) => c.name), ['Chapter 1', 'Chapter 2'], 'oldest first')
  assert.deepEqual(d.chapters.map((c) => c.number), [1, 2])
  assert.equal(new Date(d.chapters[1].date).getFullYear(), 2024)
  assert.equal((await e.prefs())[0].value, 'h')
  e.setPref('q', 'l'); assert.equal((await e.prefs())[0].value, 'l')
  await assert.rejects(e.search('x', 1), (er) => er.notImplemented === true)
  assert.equal(e.supportsLatest(), true)
})

test('engine: a source that throws names itself; broken code fails at load', async () => {
  const bad = createEngine({ source: { id: 2, name: 'Boom', baseUrl: 'https://b.test' }, language: 'mangayomi', deps: {}, code: `class DefaultExtension extends MProvider { async getPopular() { throw new Error('kaput'); } }` })
  await assert.rejects(bad.popular(1), /Extension "Boom" failed while loading the list: .*kaput/)
  const broken = createEngine({ source: { id: 3, name: 'Syn', baseUrl: '' }, language: 'mangayomi', deps: {}, code: 'class DefaultExtension extends {' })
  await assert.rejects(broken.popular(1), /Syn.*failed while loading the source/)
})

test('engine: LNReader plugin (require shims, storage, pagination, text)', async () => {
  const pages = { '/l/1': '<div class="n"><a href="/n/1"><b>Nov</b></a></div>', '/n/1': '<h1>Nov</h1><div class="c"><a href="/n/1/1">C1</a></div>', '/n/1/1': '<div id="t"><p>Hi</p><script>x()</script></div>', '/pg/2': '' }
  const fakeFetch = async (url) => { const p = new URL(url).pathname; return pages[p] != null ? new Response(pages[p]) : new Response('x', { status: 404 }) }
  const store = memPrefs()
  const code = `var cheerio = require('cheerio'), f = require('@libs/fetch'), st = require('@libs/storage');
    exports.default = { name: 'N', site: 'https://l.test', filters: {},
      async popularNovels(p) { st.storage.set('seen', p); const $ = cheerio.load(await f.fetchText('https://l.test/l/' + p)); return $('div.n').map((i, e) => ({ name: $(e).text(), path: $(e).find('a').attr('href') })).get(); },
      async parseNovel(path) { const $ = cheerio.load(await f.fetchText('https://l.test' + path)); return { path, name: $('h1').text(), status: 'Completed', chapters: $('.c a').map((i, a) => ({ name: $(a).text(), path: $(a).attr('href') })).get() }; },
      async parseChapter(path) { const $ = cheerio.load(await f.fetchText('https://l.test' + path)); return $('#t').html(); },
      async searchNovels() { return []; } };`
  const e = createEngine({ source: { id: 'lnr-n', name: 'N', baseUrl: 'https://l.test' }, code, language: 'lnreader', deps: { fetch: fakeFetch, prefs: store } })
  const pop = await e.popular(1)
  assert.deepEqual(pop.list.map((x) => [x.title, x.url]), [['Nov', '/n/1']])
  assert.equal(JSON.parse(store.m.get('ln:seen')).value, 1)
  const d = await e.detail('/n/1')
  assert.equal(d.status, 1); assert.deepEqual(d.chapters.map((c) => c.url), ['/n/1/1'])
  assert.match(await e.html('C1', '/n/1/1'), /<p>Hi<\/p>/)
})

test('sanitize: keeps reading markup, drops scripts/handlers/links, proxies images', () => {
  const out = cleanNovelHtml('<p onclick="x()">Hi <b>there</b><script>alert(1)</script><iframe src="//e"></iframe><a href="javascript:x()">link</a></p><img src="/i.png" onerror="x()"><img src="data:text/html,x"><img data-src="https://cdn.t/z.jpg" src="placeholder.gif">',
    { baseUrl: 'https://s.test/n/1', proxyUrl: (u) => 'proxy://' + u })
  assert.ok(!/script|iframe|onclick|onerror|javascript|<a /i.test(out), out)
  assert.match(out, /<p>Hi <b>there<\/b>link<\/p>/)
  assert.match(out, /src="proxy:\/\/https:\/\/s\.test\/i\.png"/)
  assert.match(out, /proxy:\/\/https:\/\/cdn\.t\/z\.jpg/)
  assert.equal((out.match(/<img/g) || []).length, 2, 'data: images are dropped')
})

test('repo index: Mangayomi JS and LNReader entries parse; Dart/Aidoku/Aniyomi are left out', () => {
  const arr = [
    { id: 123, name: 'MG', lang: 'en', baseUrl: 'https://mg.test', iconUrl: 'icon/mg.png', sourceCodeUrl: 'src/mg.js', sourceCodeLanguage: 1, itemType: 1, version: '1.2.0', isNsfw: true },
    { id: -5, name: 'Neg', lang: 'fr', baseUrl: 'https://n.test', sourceCodeUrl: 'https://x/n.js', sourceCodeLanguage: 1, itemType: 0 },
    { id: 9, name: 'DartOne', sourceCodeUrl: 'https://x/d.dart', sourceCodeLanguage: 0, itemType: 0 },
    { id: 'novelfull', name: 'NovelFull', site: 'https://nf.test', lang: 'English', version: '2.0', url: 'https://x/nf.js', iconUrl: 'https://x/nf.png' },
    { name: 'Aidoku', file: 'a.aix', id: 'x' },
    { name: 'Tachiyomi: Foo', pkg: 'eu.kanade.tachiyomi.extension.en.foo', apk: 'foo.apk' },
  ]
  const list = jsx.parseIndex(Buffer.from(JSON.stringify(arr)), 'https://repo.test/index.json')
  assert.deepEqual(list.map((e) => [e.pkg, e.type, e.runtime, e.lang]), [['mgy.123', 'anime', 'mangayomi', 'en'], ['mgy.n5', 'manga', 'mangayomi', 'fr'], ['lnr.novelfull', 'novel', 'lnreader', 'en']])
  assert.equal(list[0].codeUrl, 'https://repo.test/src/mg.js'); assert.equal(list[0].nsfw, true)
  assert.equal(jsx.parseIndex(Buffer.from('{"a":1}'), 'https://x'), null)
  // the manager reads both kinds from one address
  const both = mgr.parseAny(Buffer.from(JSON.stringify(arr)), 'https://repo.test/index.json')
  assert.equal(both.list.length, 4)
})

test('host: real child process end to end, preferences persist, a hung call is killed', async () => {
  await withServer({ '/popular?page=1': SITE['/popular?page=1'], '/m/1': SITE['/m/1'] }, async (base) => {
    const dir = tmp()
    const host = createHost({ prefsFile: path.join(dir, 'prefs.json'), idleMs: 60000 })
    try {
      const meta = { key: 'mgy-1', language: 'mangayomi', code: MG_CODE, source: { id: 1, name: 'T', baseUrl: base, lang: 'en' } }
      const pop = await host.call(meta, 'popular', [1])
      assert.deepEqual(pop.list.map((x) => x.title), ['Alpha', 'Beta'])
      const d = await host.call(meta, 'detail', ['/m/1'])
      assert.equal(d.chapters.length, 2)
      host.setPref(meta, 'q', 'l'); host.prefs.flush()
      assert.equal(JSON.parse(fs.readFileSync(path.join(dir, 'prefs.json'), 'utf8'))['mgy-1']['p:q'], 'l')
      // a source that never answers is stopped by the timeout, and the next call starts a fresh process
      const hang = { ...meta, key: 'mgy-2', code: `class DefaultExtension extends MProvider { async getPopular() { await new Client().get('${base}/hang'); return { list: [] }; } }` }
      await assert.rejects(host.call(hang, 'popular', [1], { timeout: 700 }), (e) => e.code === 'TIMEOUT')
      assert.equal(host.running(), 1, 'only the healthy source is still running')
    } finally { host.stopAll() }
  })
})

test('manager: install a Mangayomi source from a repo, browse it, uninstall (no Java anywhere)', async () => {
  const { Store } = require('../main/store')
  const dir = tmp()
  const store = new Store(dir)
  const repoUrl = 'https://repo.test/index.json'
  const index = [{ id: 7, name: 'Demo', lang: 'en', baseUrl: 'https://site.test', iconUrl: 'https://repo.test/i.png', sourceCodeUrl: 'https://repo.test/demo.js', sourceCodeLanguage: 1, itemType: 0, version: '1.0.0' }]
  const fetchImpl = async (url, init) => {
    const u = new URL(url)
    if (url === repoUrl) return new Response(JSON.stringify(index))
    if (url === 'https://repo.test/demo.js') return new Response(MG_CODE)
    if (u.host === 'site.test') { const p = u.pathname + u.search; return SITE[p] ? new Response(SITE[p]) : new Response('x', { status: 404 }) }
    return new Response('x', { status: 404 })
  }
  const m = mgr.create({ dir, store, fetchImpl, jsInline: true, proxyUrl: (u) => 'p://' + u })
  try {
    await m.addRepo(repoUrl)
    const av = await m.available('manga')
    assert.equal(av.list.length, 1); assert.equal(av.list[0].pkg, 'mgy.7'); assert.equal(av.list[0].installed, false)
    const list = await m.install({ pkg: 'mgy.7' })
    assert.equal(list.length, 1); assert.equal(list[0].id, 'mgy-7')
    assert.equal((await m.loadInstalled(true))[0].runtime, 'mangayomi')
    const r = await m.browse({ sourceId: 'mgy-7', kind: 'popular', page: 1 })
    assert.equal(r.list[0].title, 'Alpha'); assert.match(r.list[0].cover, /^p:\/\/https:\/\/site\.test\/c1\.jpg/)
    const dt = await m.detail({ sourceId: 'mgy-7', media: { title: 'Alpha', url: '/m/1' } })
    assert.deepEqual(dt.chapters.map((c) => c.number), [1, 2])
    assert.equal((await m.available('manga')).list[0].installed, true)
    await m.uninstall({ pkg: 'mgy.7' })
    await assert.rejects(m.loadInstalled(true), (e) => e.code === 'NO_RUNTIME' || /Java|runtime/i.test(e.message), 'with nothing installed the old Java hint returns')
  } finally { m.stopAll() }
})

test('manager: an extension that fails to load is not installed', async () => {
  const { Store } = require('../main/store')
  const dir = tmp()
  const store = new Store(dir)
  const fetchImpl = async (url) => (url.endsWith('index.json') ? new Response(JSON.stringify([{ id: 8, name: 'Bad', lang: 'en', baseUrl: 'https://b.test', sourceCodeUrl: 'https://repo.test/bad.js', sourceCodeLanguage: 1, itemType: 0 }])) : new Response('class DefaultExtension extends MProvider { constructor() { super(); throw new Error("nope") } }'))
  const m = mgr.create({ dir, store, fetchImpl, jsInline: true })
  try {
    await m.addRepo('https://repo.test/index.json')
    await assert.rejects(m.install({ pkg: 'mgy.8' }), /Bad.*nope/)
    assert.equal(m.js.installed().length, 0)
    assert.ok(!fs.existsSync(path.join(dir, 'extensions', 'js', 'src', 'mgy.8.js')))
  } finally { m.stopAll() }
})
