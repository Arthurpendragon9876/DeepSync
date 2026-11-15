'use strict'
// Runs the UI in any browser against the real main-process logic (no Electron needed):  npm run dev:web
// Usage: node tools/dev-server.js [--port 5173] [--data <dir>]    env: DEEPSYNC_LIB_ANIME / _MANGA / _NOVEL
const http = require('http')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { Store } = require('../main/store')
const { createApi } = require('../main/api')
const { fileResponse, zipResponse, proxyResponse, proxyUrlFor, streamResponse, streamUrlFor, mime, b64, unb64 } = require('../main/serve')

const arg = (n, d) => (process.argv.includes('--' + n) ? process.argv[process.argv.indexOf('--' + n) + 1] : d)
const port = Number(arg('port', 5173))
const dataDir = arg('data', fs.mkdtempSync(path.join(os.tmpdir(), 'deepsync-dev-')))
const RENDERER = path.join(__dirname, '..', 'renderer')

const store = new Store(dataDir)
const env = (k) => (process.env[k] ? [process.env[k]] : [])
if (env('DEEPSYNC_LIB_ANIME').length || env('DEEPSYNC_LIB_MANGA').length || env('DEEPSYNC_LIB_NOVEL').length)
  store.patchSettings({ libraryPaths: { anime: env('DEEPSYNC_LIB_ANIME'), manga: env('DEEPSYNC_LIB_MANGA'), novel: env('DEEPSYNC_LIB_NOVEL') } })
const api = createApi({ store, zipUrl: (f, e) => `/zip?p=${b64(f)}&e=${encodeURIComponent(e)}`, dialogs: {}, proxyUrl: proxyUrlFor('/proxy'), streamUrl: streamUrlFor('/stream') })
process.on('exit', () => api.shutdown())
process.on('SIGINT', () => process.exit(0))

const SHIM = `window.deepsync = { platform: 'linux', mediaBase: '/media', zipBase: '/zip', win: { action() {}, onState() {} },
  async call(ch, args) { const r = await fetch('/rpc', { method: 'POST', body: JSON.stringify({ ch, args }) }); return r.json() } }`

function send(res, r) {
  res.writeHead(r.status, r.headers)
  if (r.body && r.body.pipe) r.body.pipe(res)
  else res.end(r.body)
}

http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x')
  try {
    if (u.pathname === '/rpc') {
      let body = ''
      for await (const c of req) body += c
      const { ch, args } = JSON.parse(body)
      const out = await api.call(ch, args)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      return res.end(JSON.stringify(out))
    }
    if (u.pathname === '/stream') return send(res, await streamResponse(u.searchParams.get('u'), u.searchParams.get('h'), req.headers.range, streamUrlFor('/stream')))
    if (u.pathname === '/proxy') return send(res, await proxyResponse(u.searchParams.get('u'), u.searchParams.get('h')))
    if (u.pathname === '/__shim.js') return (res.writeHead(200, { 'Content-Type': 'text/javascript' }), res.end(SHIM))
    if (u.pathname === '/media' || u.pathname === '/zip') {
      const p = unb64(u.searchParams.get('p'))
      if (!api.isAllowed(p)) return (res.writeHead(403), res.end('Forbidden'))
      return send(res, u.pathname === '/media' ? await fileResponse(p, req.headers.range) : await zipResponse(p, u.searchParams.get('e')))
    }
    const rel = u.pathname === '/' ? '/index.html' : decodeURIComponent(u.pathname)
    const file = path.normalize(path.join(RENDERER, rel))
    if (!file.startsWith(RENDERER + path.sep)) return (res.writeHead(403), res.end())
    let data = await fs.promises.readFile(file)
    if (rel === '/index.html') data = Buffer.from(data.toString().replace('<!--SHIM-->', '<script src="/__shim.js"></script>'))
    res.writeHead(200, { 'Content-Type': mime(file), 'Cache-Control': 'no-store' })
    res.end(data)
  } catch (e) {
    res.writeHead(e.code === 'ENOENT' ? 404 : 500)
    res.end(String(e.message))
  }
}).listen(port, '127.0.0.1', () => console.log(`Shinkai dev server: http://127.0.0.1:${port}  (data: ${dataDir})`))
