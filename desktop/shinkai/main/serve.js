'use strict'
// Shared by Electron's deepsync:// protocol handler and the browser dev server.
const fs = require('fs')
const path = require('path')
const { openCached } = require('./zip')

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif',
  '.avif': 'image/avif', '.bmp': 'image/bmp', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.css.map': 'application/json',
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska', '.mov': 'video/quicktime', '.avi': 'video/x-msvideo',
  '.xhtml': 'application/xhtml+xml', '.ttc': 'font/collection', '.otf': 'font/otf',
}
const mime = (p) => MIME[path.extname(p).toLowerCase()] || 'application/octet-stream'

// Ranged file read: <video> needs 206 responses to seek.
async function fileResponse(p, rangeHeader) {
  const st = await fs.promises.stat(p)
  let start = 0
  let end = st.size - 1
  let status = 200
  const m = /bytes=(\d*)-(\d*)/.exec(rangeHeader || '')
  if (m && (m[1] || m[2])) {
    status = 206
    if (m[1] === '') {
      start = Math.max(0, st.size - Number(m[2]))
    } else {
      start = Number(m[1])
      if (m[2]) end = Math.min(Number(m[2]), end)
    }
  }
  if (start > end || start >= st.size) return { status: 416, headers: { 'Content-Range': `bytes */${st.size}` }, body: null }
  const headers = { 'Content-Type': mime(p), 'Accept-Ranges': 'bytes', 'Content-Length': String(end - start + 1) }
  if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${st.size}`
  return { status, headers, body: st.size ? fs.createReadStream(p, { start, end }) : Buffer.alloc(0) }
}

async function zipResponse(file, entry) {
  const z = await openCached(file)
  const body = await z.read(entry)
  return { status: 200, headers: { 'Content-Type': mime(entry), 'Content-Length': String(body.length), 'Cache-Control': 'max-age=3600' }, body }
}

// Fetches an online image on behalf of the UI so extension-supplied headers (Referer, User-Agent, cookies) are applied.
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
const BLOCKED = /^(host|content-length|connection|transfer-encoding|accept-encoding)$/i
async function proxyResponse(u, h, fetchImpl = globalThis.fetch) {
  const url = unb64(u)
  if (!/^https?:\/\//i.test(url)) return { status: 400, headers: {}, body: 'Bad address' }
  let given = {}
  try { given = JSON.parse(unb64(h) || '{}') } catch {}
  const headers = { 'User-Agent': UA, Accept: 'image/avif,image/webp,image/*,*/*;q=0.8' }
  for (const [k, v] of Object.entries(given)) if (typeof v === 'string' && !/[\r\n]/.test(k + v) && !BLOCKED.test(k)) headers[k] = v
  let res
  try {
    res = await fetchImpl(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(30000) })
  } catch {
    return { status: 502, headers: {}, body: 'Upstream unreachable' }
  }
  const body = Buffer.from(await res.arrayBuffer())
  if (body.length > 60 * 1048576) return { status: 413, headers: {}, body: 'Too large' }
  return { status: res.status, headers: { 'Content-Type': res.headers.get('content-type') || 'application/octet-stream', 'Content-Length': String(body.length), 'Cache-Control': 'max-age=3600' }, body }
}
const proxyUrlFor = (prefix) => (url, headers) =>
  /^https?:\/\//i.test(url || '') ? `${prefix}?u=${b64(url)}${headers && Object.keys(headers).length ? '&h=' + b64(JSON.stringify(headers)) : ''}` : url

// ---- video streams from extensions ------------------------------------------------------------------------------------
// Online video needs the source's headers (Referer, User-Agent) and often has no CORS headers, so the player talks to
// us instead. HLS playlists are rewritten so every segment, key and sub-playlist also comes back through here.
const isPlaylist = (url, type, head) => /mpegurl/i.test(type || '') || /\.m3u8(\?|#|$)/i.test(url) || String(head || '').startsWith('#EXTM3U')
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges' }

function rewritePlaylist(text, base, headers, mk) {
  const abs = (u) => { try { return new URL(u, base).href } catch { return null } }
  const wrap = (u) => { const a = abs(u); return a ? mk(a, headers) : u }
  return text.split(/\r?\n/).map((line) => {
    if (!line.trim()) return line
    if (line.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${wrap(u)}"`)
    return wrap(line.trim())
  }).join('\n')
}

async function streamResponse(u, h, range, mk, fetchImpl = globalThis.fetch) {
  const url = unb64(u)
  if (!/^https?:\/\//i.test(url)) return { status: 400, headers: CORS, body: 'Bad address' }
  let given = {}
  try { given = JSON.parse(unb64(h) || '{}') } catch {}
  const headers = { 'User-Agent': UA }
  for (const [k, v] of Object.entries(given)) if (typeof v === 'string' && !/[\r\n]/.test(k + v) && !BLOCKED.test(k)) headers[k] = v
  if (range) headers.Range = range
  let res
  try {
    res = await fetchImpl(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(45000) })
  } catch { return { status: 502, headers: CORS, body: 'Upstream unreachable' } }
  const type = res.headers.get('content-type') || ''
  const looksText = /mpegurl|text|octet-stream|json/i.test(type) || /\.m3u8/i.test(url)
  if (looksText && res.ok && !range) {
    const buf = Buffer.from(await res.arrayBuffer())
    const head = buf.subarray(0, 8).toString('utf8')
    if (isPlaylist(url, type, head)) {
      const text = rewritePlaylist(buf.toString('utf8'), res.url || url, given, mk)
      return { status: 200, headers: { ...CORS, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' }, body: text }
    }
    return { status: res.status, headers: { ...CORS, 'Content-Type': type || 'application/octet-stream', 'Content-Length': String(buf.length) }, body: buf }
  }
  const out = { ...CORS }
  for (const k of ['content-type', 'content-length', 'content-range', 'accept-ranges']) if (res.headers.get(k)) out[k] = res.headers.get(k)
  return { status: res.status, headers: out, body: res.body ? require('stream').Readable.fromWeb(res.body) : '' }
}
const streamUrlFor = (prefix) => (url, headers) =>
  /^https?:\/\//i.test(url || '') ? `${prefix}?u=${b64(url)}${headers && Object.keys(headers).length ? '&h=' + b64(JSON.stringify(headers)) : ''}` : url

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64url')
const unb64 = (s) => Buffer.from(s || '', 'base64url').toString('utf8')

module.exports = { fileResponse, zipResponse, proxyResponse, proxyUrlFor, streamResponse, streamUrlFor, rewritePlaylist, mime, b64, unb64 }
