'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const http = require('http')
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { createRuntime } = require('../main/extensions/runtime')

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'shinkai-rt-'))
const DATA = crypto.randomBytes(20 * 1048576 + 12345) // > 2 segments, not a round size
const SHA = crypto.createHash('sha256').update(DATA).digest('hex')

// Range-capable server; `dropFirst` kills the first ranged response halfway to prove resume works.
function serve({ ranges = true, dropFirst = false, tag = 'v1.0.0' } = {}) {
  let dropped = !dropFirst
  const hits = { ranged: 0, latest: 0 }
  const srv = http.createServer((req, res) => {
    if (req.url === '/releases/latest') {
      hits.latest++
      res.writeHead(302, { location: `/releases/tag/${tag}` }).end()
    } else if (req.url === '/file') {
      const m = ranges && /bytes=(\d+)-(\d*)/.exec(req.headers.range || '')
      if (!m) return res.writeHead(200, { 'content-length': DATA.length }).end(DATA)
      hits.ranged++
      const a = Number(m[1])
      const b = m[2] ? Math.min(Number(m[2]), DATA.length - 1) : DATA.length - 1
      res.writeHead(206, { 'content-range': `bytes ${a}-${b}/${DATA.length}`, 'content-length': b - a + 1 })
      if (!dropped && b - a > 1048576) {
        dropped = true
        res.write(DATA.subarray(a, a + 300000))
        return setTimeout(() => res.destroy(), 20)
      }
      res.end(DATA.subarray(a, b + 1))
    } else res.writeHead(404).end()
  })
  return new Promise((r) => srv.listen(0, '127.0.0.1', () => r({ srv, hits, base: `http://127.0.0.1:${srv.address().port}` })))
}

test('download: parallel ranges reassemble the exact bytes', async () => {
  const { srv, hits, base } = await serve()
  const dir = tmp()
  const rt = createRuntime({ dir, getSettings: () => ({}) })
  const dest = path.join(dir, 'runtime', 'x.bin')
  const sha = await rt.download(base + '/file', dest, 'Test')
  srv.close()
  assert.equal(sha, SHA)
  assert.ok(fs.readFileSync(dest).equals(DATA))
  assert.ok(hits.ranged >= 6, 'used several connections, got ' + hits.ranged)
  assert.equal(fs.existsSync(dest + '.part'), false)
})

test('download: a dropped connection resumes instead of failing', async () => {
  const { srv, base } = await serve({ dropFirst: true })
  const dir = tmp()
  const rt = createRuntime({ dir, getSettings: () => ({}) })
  const dest = path.join(dir, 'runtime', 'x.bin')
  assert.equal(await rt.download(base + '/file', dest, 'Test'), SHA)
  srv.close()
})

test('download: server without Range falls back to one stream', async () => {
  const { srv, base } = await serve({ ranges: false })
  const dir = tmp()
  const rt = createRuntime({ dir, getSettings: () => ({}) })
  assert.equal(await rt.download(base + '/file', path.join(dir, 'x.bin'), 'Test'), SHA)
  srv.close()
})

test('download: HTTP error leaves no partial file', async () => {
  const { srv, base } = await serve()
  const dir = tmp()
  const rt = createRuntime({ dir, getSettings: () => ({}) })
  const dest = path.join(dir, 'x.bin')
  await assert.rejects(rt.download(base + '/missing', dest, 'Test'), /Download failed/)
  srv.close()
  assert.equal(fs.existsSync(dest), false)
  assert.equal(fs.existsSync(dest + '.part'), false)
})

// ---- update check -----------------------------------------------------------------------------------------------------
function withJar(meta, size = 100) {
  const dir = tmp()
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'runtime', 'anymex_desktop_runtime.jar'), Buffer.alloc(size))
  if (meta) fs.writeFileSync(path.join(dir, 'runtime', 'bridge.json'), JSON.stringify(meta))
  return dir
}
const redirectTo = (tag) => async () => ({ status: 302, headers: new Map([['location', `https://github.com/x/y/releases/tag/${tag}`]]), body: null })

test('update check: same tag means no update, newer tag means update', async () => {
  const dir = withJar({ tag: 'v2.7.4' })
  let rt = createRuntime({ dir, getSettings: () => ({}), fetchImpl: redirectTo('v2.7.4') })
  assert.equal((await rt.checkUpdate()).available, false)
  assert.equal((await rt.status()).bridge.update, null)
  rt = createRuntime({ dir, getSettings: () => ({}), fetchImpl: redirectTo('v2.8.0') })
  assert.equal((await rt.checkUpdate()).available, true)
  assert.deepEqual((await rt.status()).bridge.update, { latest: 'v2.8.0' })
})

test('update check: offline or unreadable answers mean no update', async () => {
  const dir = withJar({ tag: 'v2.7.4' })
  const rt = createRuntime({ dir, getSettings: () => ({}), fetchImpl: async () => { throw new Error('offline') } })
  assert.equal((await rt.checkUpdate()).available, false)
})

test('update check: install from an older Shinkai (no tag) is compared by size, then remembered', async () => {
  const dir = withJar({ sha256: 'x' }, 100)
  const same = async (url, o) => (o?.headers?.range ? { status: 206, headers: new Map([['content-range', 'bytes 0-0/100']]), body: null } : redirectTo('v2.7.4')())
  let rt = createRuntime({ dir, getSettings: () => ({}), fetchImpl: same })
  assert.equal((await rt.checkUpdate()).available, false)
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, 'runtime', 'bridge.json'), 'utf8')).tag, 'v2.7.4')
  const dir2 = withJar(null, 100)
  const bigger = async (url, o) => (o?.headers?.range ? { status: 206, headers: new Map([['content-range', 'bytes 0-0/200']]), body: null } : redirectTo('v2.7.5')())
  rt = createRuntime({ dir: dir2, getSettings: () => ({}), fetchImpl: bigger })
  assert.equal((await rt.checkUpdate()).available, true)
})

test('update check: a user-chosen bridge JAR is never offered an update', async () => {
  const dir = withJar({ tag: 'v1' })
  const custom = path.join(dir, 'mine.jar')
  fs.writeFileSync(custom, 'x')
  const rt = createRuntime({ dir, getSettings: () => ({ extensions: { bridgeJar: custom } }), fetchImpl: redirectTo('v9') })
  assert.equal((await rt.checkUpdate()).available, false)
})

test('update check: answer is cached, force bypasses the cache', async () => {
  const dir = withJar({ tag: 'v1' })
  let n = 0
  const rt = createRuntime({ dir, getSettings: () => ({}), fetchImpl: async () => (n++, redirectTo('v1')()) })
  await rt.checkUpdate(); await rt.checkUpdate()
  assert.equal(n, 1)
  await rt.checkUpdate({ force: true })
  assert.equal(n, 2)
})
