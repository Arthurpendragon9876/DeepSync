'use strict'
// Runs JavaScript extensions out of process. One child process per source, started on first use and stopped after it
// has been idle for a while. A call that takes too long kills the child (an extension stuck in a loop cannot freeze the
// app). Tests pass `inline: true` to run engines in-process with an injected fetch.
const path = require('path')
const fs = require('fs')
const { fork } = require('child_process')

const WORKER = path.join(__dirname, 'worker.js')
const IDLE_MS = 3 * 60 * 1000
const CALL_TIMEOUT = 60 * 1000

// Persistent per-source preferences: { [sourceId]: { key: value } } in one small JSON file.
function createPrefsStore(file) {
  let data = {}
  try { data = JSON.parse(fs.readFileSync(file, 'utf8')) || {} } catch {}
  let t = null
  const flush = () => { t = null; try { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(data)) } catch {} }
  const later = () => { if (!t) { t = setTimeout(flush, 300); t.unref?.() } }
  return {
    all: (id) => ({ ...(data[id] || {}) }),
    set: (id, k, v) => { (data[id] ||= {})[k] = v; later() },
    delete: (id, k) => { if (data[id]) { delete data[id][k]; later() } },
    drop: (id) => { delete data[id]; later() },
    flush,
  }
}

function createHost({ prefsFile, inline = false, fetchImpl, workerPath = WORKER, forkImpl = fork, onLog = () => {}, idleMs = IDLE_MS } = {}) {
  const prefs = createPrefsStore(prefsFile || path.join(require('os').tmpdir(), `ds-js-prefs-${process.pid}.json`))
  const live = new Map() // sourceKey -> { child | engine, pending, timer, ready }

  function killEntry(key, why) {
    const e = live.get(key)
    if (!e) return
    live.delete(key)
    clearTimeout(e.timer)
    for (const p of e.pending.values()) p.reject(Object.assign(new Error(why || 'The extension was stopped'), { code: 'STOPPED' }))
    e.pending.clear()
    try { e.child?.kill('SIGKILL') } catch {}
    e.engine?.dispose?.()
  }
  const touch = (key, e) => { clearTimeout(e.timer); e.timer = setTimeout(() => killEntry(key, 'idle'), idleMs); e.timer.unref?.() }

  // ---- inline (tests) ------------------------------------------------------------------------------------------------
  function startInline(key, meta) {
    const { createEngine } = require('./engine')
    const local = prefs.all(meta.key)
    const store = {
      get: (k) => local[k], set: (k, v) => { local[k] = v; prefs.set(meta.key, k, v) },
      delete: (k) => { delete local[k]; prefs.delete(meta.key, k) }, keys: () => Object.keys(local),
    }
    const engine = createEngine({ source: meta.source, code: meta.code, language: meta.language, deps: { fetch: fetchImpl, log: onLog, prefs: store } })
    const e = { engine, pending: new Map(), timer: null }
    live.set(key, e)
    return e
  }

  // ---- child process -------------------------------------------------------------------------------------------------
  function startChild(key, meta) {
    const env = { ELECTRON_RUN_AS_NODE: '1' }
    for (const k of ['PATH', 'HOME', 'LANG', 'TZ', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy', 'SSL_CERT_FILE', 'NODE_EXTRA_CA_CERTS']) if (process.env[k]) env[k] = process.env[k]
    const child = forkImpl(workerPath, [], { execPath: process.execPath, env, stdio: ['ignore', 'ignore', 'ignore', 'ipc'], serialization: 'json' })
    const e = { child, pending: new Map(), timer: null, seq: 0 }
    live.set(key, e)
    child.on('message', (m) => {
      if (!m) return
      if (m.type === 'pref') { m.op === 'set' ? prefs.set(meta.key, m.key, m.value) : prefs.delete(meta.key, m.key); return }
      if (m.type === 'log') return onLog(m.msg)
      const p = e.pending.get(m.id)
      if (!p) return
      e.pending.delete(m.id)
      m.ok ? p.resolve(m.result) : p.reject(Object.assign(new Error(m.error?.message || 'Extension failed'), { notImplemented: !!m.error?.notImplemented }))
    })
    const gone = () => { if (live.get(key) === e) killEntry(key, 'The extension process stopped unexpectedly') }
    child.on('exit', gone)
    child.on('error', gone)
    return e
  }
  const rpc = (e, msg, timeout) => new Promise((resolve, reject) => {
    const id = ++e.seq
    const t = setTimeout(() => { e.pending.delete(id); reject(Object.assign(new Error('The source took too long to answer and was stopped.'), { code: 'TIMEOUT' })); for (const [k, v] of live) if (v === e) killEntry(k) }, timeout)
    e.pending.set(id, { resolve: (v) => (clearTimeout(t), resolve(v)), reject: (er) => (clearTimeout(t), reject(er)) })
    try { e.child.send({ ...msg, id }) } catch (er) { e.pending.delete(id); clearTimeout(t); reject(er) }
  })

  // meta: { key, source, code, language }
  async function call(meta, method, args = [], { timeout = CALL_TIMEOUT } = {}) {
    const key = meta.key
    let e = live.get(key)
    if (!e) {
      if (inline) e = startInline(key, meta)
      else {
        e = startChild(key, meta)
        try { await rpc(e, { type: 'init', source: meta.source, code: meta.code, language: meta.language, prefs: prefs.all(meta.key) }, 20000) } catch (er) { killEntry(key); throw er }
      }
    }
    touch(key, e)
    if (inline) {
      let timer
      const guard = new Promise((_, rej) => { timer = setTimeout(() => rej(Object.assign(new Error('The source took too long to answer and was stopped.'), { code: 'TIMEOUT' })), timeout) })
      try { return await Promise.race([Promise.resolve().then(() => e.engine[method](...args)), guard]) } finally { clearTimeout(timer) }
    }
    return rpc(e, { type: 'call', method, args }, timeout)
  }

  // Preference writes from the UI: persist, and tell a running child so its next read agrees.
  function setPref(meta, key, value) {
    const k = 'p:' + key
    prefs.set(meta.key, k, value)
    const e = live.get(meta.key)
    if (!e) return true
    if (inline) e.engine.setPref(key, value)
    else { try { e.child.send({ type: 'pref', op: 'set', key: k, value }) } catch {} }
    return true
  }

  return {
    call, setPref, prefs,
    stop: (key) => killEntry(key, 'stopped'),
    stopAll: () => { for (const k of [...live.keys()]) killEntry(k, 'stopped'); prefs.flush() },
    running: () => live.size,
  }
}

module.exports = { createHost, createPrefsStore }
