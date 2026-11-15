'use strict'
// Entry point of the child process that hosts ONE extension (see host.js). Runs as plain Node, or as Electron with
// ELECTRON_RUN_AS_NODE=1. Talks to the parent over IPC with small JSON messages.
const { createEngine } = require('./engine')

let engine = null
const prefs = new Map()
const send = (m) => { try { process.send(m) } catch {} }

const prefStore = {
  get: (k) => prefs.get(k),
  set: (k, v) => { prefs.set(k, v); send({ type: 'pref', op: 'set', key: k, value: v }) },
  delete: (k) => { prefs.delete(k); send({ type: 'pref', op: 'delete', key: k }) },
  keys: () => [...prefs.keys()],
}

// An extension's stray timer callback must never take the whole process down silently.
process.on('uncaughtException', (e) => send({ type: 'log', msg: 'uncaught: ' + (e && e.message) }))
process.on('unhandledRejection', (e) => send({ type: 'log', msg: 'unhandled rejection: ' + (e && e.message) }))

const METHODS = new Set(['popular', 'latest', 'search', 'detail', 'pages', 'videos', 'html', 'filters', 'prefs', 'setPref', 'headers', 'supportsLatest'])

process.on('message', async (m) => {
  if (!m || typeof m !== 'object') return
  try {
    if (m.type === 'init') {
      for (const [k, v] of Object.entries(m.prefs || {})) prefs.set(k, v)
      engine = createEngine({ source: m.source, code: m.code, language: m.language, deps: { log: (msg) => send({ type: 'log', msg }), prefs: prefStore } })
      return send({ id: m.id, ok: true })
    }
    if (m.type === 'pref') { if (m.op === 'set') prefs.set(m.key, m.value); else prefs.delete(m.key); return }
    if (m.type === 'call') {
      if (!engine || !METHODS.has(m.method)) throw new Error('Unknown extension call: ' + m.method)
      const result = await engine[m.method](...(m.args || []))
      return send({ id: m.id, ok: true, result: result === undefined ? null : result })
    }
  } catch (e) {
    send({ id: m.id, ok: false, error: { message: (e && e.message) || String(e), notImplemented: !!(e && e.notImplemented) } })
  }
})

send({ type: 'ready' })
