'use strict'
// Client for the AnymeX desktop bridge JAR (a Java process). Protocol: one JSON object per line.
//   request  -> {"method": "...", "args": {...}, "id": "r1"}          on the JAR's stdin
//   response <- {"id": "r1", "data": ...} | {"id": "r1", "error": "..."}   on its stdout (logs go to stderr)
const { spawn } = require('child_process')
const { EventEmitter } = require('events')

class Sidecar extends EventEmitter {
  constructor({ javaPath, jarPath, spawnImpl = spawn, maxHeapMb = 1536 }) {
    super()
    Object.assign(this, { javaPath, jarPath, spawnImpl, maxHeapMb })
    this.proc = null
    this.pending = new Map()
    this.n = 0
    this.logTail = []
  }

  get running() {
    return !!this.proc && this.proc.exitCode === null && !this.proc.killed
  }

  // JVM flags that skip bytecode verification (converted APK classes may need it). Newer JVMs removed them,
  // so each set is tried in turn until one is accepted.
  static FLAG_SETS = [['-noverify'], ['-XX:-BytecodeVerificationRemote'], []]

  async start() {
    if (this.running) return
    this.flagSet ??= 0
    for (let i = this.flagSet; i < Sidecar.FLAG_SETS.length; i++) {
      this.logTail = []
      await this._launch(Sidecar.FLAG_SETS[i])
      if (this.running) return void (this.flagSet = i)
      if (!/Unrecognized (VM )?option|Could not create the Java Virtual Machine/i.test(this.logTail.join('\n'))) break
    }
    throw new Error('Extension runtime failed to start. ' + this.logTail.slice(-3).join(' '))
  }

  async _launch(extra) {
    const args = ['-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8', '-Xms64m', `-Xmx${this.maxHeapMb}m`, ...extra, '-jar', this.jarPath]
    const proc = this.spawnImpl(this.javaPath, args, { stdio: ['pipe', 'pipe', 'pipe'] })
    this.proc = proc
    let ready
    const started = new Promise((res) => (ready = res))
    let out = ''
    proc.stdout.setEncoding('utf8')
    proc.stdout.on('data', (d) => {
      out += d
      let i
      while ((i = out.indexOf('\n')) >= 0) {
        const line = out.slice(0, i).trim()
        out = out.slice(i + 1)
        if (line) this._line(line)
      }
    })
    let err = ''
    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', (d) => {
      err += d
      let i
      while ((i = err.indexOf('\n')) >= 0) {
        const line = err.slice(0, i)
        err = err.slice(i + 1)
        this.logTail.push(line)
        if (this.logTail.length > 200) this.logTail.shift()
        if (line.includes('Sidecar Process Started')) ready()
        this.emit('log', line)
      }
    })
    proc.once('error', (e) => {
      this.logTail.push(e.code === 'ENOENT' ? 'Java was not found' : e.message)
      this._failAll(e.code === 'ENOENT' ? new Error('Java was not found') : e)
      ready()
    })
    proc.once('exit', (code) => {
      if (err.trim()) this.logTail.push(err.trim())
      this._failAll(new Error(`Extension runtime exited (code ${code})`))
      ready()
    })
    proc.stdin.on('error', () => {})
    await Promise.race([started, new Promise((r) => setTimeout(r, 15000))])
    await new Promise((r) => setImmediate(r))
  }

  _line(line) {
    let msg
    try {
      msg = JSON.parse(line)
    } catch {
      return
    }
    const p = msg && this.pending.get(String(msg.id))
    if (!p) return
    if (msg.status === 'partial') return
    this.pending.delete(String(msg.id))
    clearTimeout(p.timer)
    if (msg.error != null) p.reject(new Error(typeof msg.error === 'string' ? msg.error : JSON.stringify(msg.error)))
    else p.resolve(msg.data)
  }

  _failAll(err) {
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer)
      p.reject(err)
      this.pending.delete(id)
    }
  }

  async call(method, args = {}, { timeout = 120000 } = {}) {
    await this.start()
    const id = 'r' + ++this.n
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        try {
          this.proc.stdin.write(JSON.stringify({ method: 'cancel', args: { id } }) + '\n')
        } catch {}
        reject(new Error(`Extension call "${method}" timed out after ${Math.round(timeout / 1000)}s`))
      }, timeout)
      this.pending.set(id, { resolve, reject, timer })
      try {
        this.proc.stdin.write(JSON.stringify({ method, args, id }) + '\n')
      } catch (e) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(e)
      }
    })
  }

  stop() {
    if (!this.proc) return
    try {
      this.proc.stdin.write(JSON.stringify({ method: 'exit', args: {}, id: 'bye' }) + '\n')
    } catch {}
    const p = this.proc
    setTimeout(() => p.exitCode === null && p.kill(), 800).unref?.()
    this.proc = null
  }
}

module.exports = { Sidecar }
