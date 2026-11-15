'use strict'
// Finds (or downloads) the two things the extension sidecar needs: a Java 17+ runtime and the bridge JAR.
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { execFile } = require('child_process')

const BRIDGE_REPO = 'https://github.com/RyanYuuki/AnymeXExtensionRuntimeBridge/releases/latest'
const BRIDGE_URL = BRIDGE_REPO + '/download/anymex_desktop_runtime.jar'
const JRE_BASE = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/'
const JRE_FILES = { x64: 'OpenJDK17U-jre_x64_linux_hotspot_17.0.12_7.tar.gz', arm64: 'OpenJDK17U-jre_aarch64_linux_hotspot_17.0.12_7.tar.gz' }
const MIN_JAVA = 17
const SEGMENTS = 6 // parallel connections per big download; a single GitHub stream is often far below the line speed
const MIN_SEGMENT = 4 * 1048576 // below ~2 segments' worth, one stream is simpler and just as fast
const IDLE_MS = 20000 // abort a stalled connection and resume from where it stopped
const UPDATE_TTL = 6 * 3600 * 1000 // how long an "is there a newer bridge?" answer is reused
const mb = (n) => (n / 1048576).toFixed(1)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const run = (cmd, args, opts = {}) =>
  new Promise((resolve) => execFile(cmd, args, { timeout: 15000, ...opts }, (err, stdout, stderr) => resolve({ err, out: String(stdout || '') + String(stderr || '') })))

// "openjdk version "21.0.12.1" ..." / "java version "1.8.0_402"" -> 21 / 8
function parseJavaMajor(text) {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text || '')
  if (!m) return 0
  return m[1] === '1' ? Number(m[2] || 0) : Number(m[1])
}

function createRuntime({ dir, getSettings, fetchImpl = globalThis.fetch }) {
  const runtimeDir = path.join(dir, 'runtime')
  const jreDir = path.join(runtimeDir, 'jre')
  const bridgeJar = path.join(runtimeDir, 'anymex_desktop_runtime.jar')
  const state = { phase: 'idle', label: '', progress: 0, error: null }
  const setState = (s) => Object.assign(state, { error: null }, s)

  const managedJava = () => path.join(jreDir, 'bin', 'java')

  async function probe(javaPath) {
    const { err, out } = await run(javaPath, ['-version'])
    if (err && !out) return null
    const major = parseJavaMajor(out)
    return major ? { path: javaPath, major } : null
  }

  async function findJava() {
    const s = getSettings().extensions || {}
    const candidates = [
      s.javaPath,
      process.env.DEEPSYNC_JAVA,
      fs.existsSync(managedJava()) && managedJava(),
      process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', 'java'),
      'java',
      '/usr/lib/jvm/default/bin/java',
      '/usr/lib/jvm/default-runtime/bin/java',
    ].filter(Boolean)
    // Distro layouts like /usr/lib/jvm/java-21-openjdk/bin/java
    try {
      for (const d of fs.readdirSync('/usr/lib/jvm')) candidates.push(path.join('/usr/lib/jvm', d, 'bin', 'java'))
    } catch {}
    let tooOld = null
    for (const c of [...new Set(candidates)]) {
      const j = await probe(c)
      if (!j) continue
      if (j.major >= MIN_JAVA) return { ...j, managed: c === managedJava() }
      tooOld ||= j
    }
    return tooOld ? { ...tooOld, tooOld: true } : null
  }

  const bridgePath = () => {
    const custom = (getSettings().extensions || {}).bridgeJar
    if (custom && fs.existsSync(custom)) return custom
    return fs.existsSync(bridgeJar) ? bridgeJar : null
  }

  // Several downloads can run at once (Java + bridge); progress is the sum of them.
  const tasks = new Set()
  function publish() {
    let got = 0
    let total = 0
    for (const t of tasks) (got += t.got), (total += t.total)
    state.progress = total ? got / total : 0
    state.label = `${[...tasks].map((t) => t.label).join(' + ')} ${mb(got)}${total ? ' / ' + mb(total) : ''} MB`
  }

  // One cheap request to learn the size, whether the server honours Range, and the post-redirect URL.
  async function probeRemote(url) {
    const res = await fetchImpl(url, { redirect: 'follow', headers: { range: 'bytes=0-0' } })
    res.body?.cancel?.().catch?.(() => {})
    if (res.status === 206) {
      const total = Number(/\/(\d+)\s*$/.exec(res.headers.get('content-range') || '')?.[1]) || 0
      return { total, ranged: total > 0, finalUrl: res.url || url }
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return { total: Number(res.headers.get('content-length')) || 0, ranged: false, finalUrl: res.url || url }
  }

  async function sha256File(file) {
    const hash = crypto.createHash('sha256')
    for await (const chunk of fs.createReadStream(file)) hash.update(chunk)
    return hash.digest('hex')
  }

  // Fetch bytes [start, end] into the file at their own offset. Retries resume from the last byte written.
  async function fetchSegment(urls, start, end, fh, task) {
    let pos = start
    let lastErr
    for (let attempt = 0; attempt < 5 && pos <= end; attempt++) {
      const ac = new AbortController()
      let timer
      const arm = () => (clearTimeout(timer), (timer = setTimeout(() => ac.abort(), IDLE_MS)))
      try {
        arm()
        const res = await fetchImpl(urls[attempt % urls.length], { redirect: 'follow', signal: ac.signal, headers: { range: `bytes=${pos}-${end}` } })
        if (res.status !== 206) throw new Error(`HTTP ${res.status}`)
        for await (const chunk of res.body) {
          arm()
          const buf = pos + chunk.length - 1 > end ? chunk.subarray(0, end - pos + 1) : chunk
          await fh.write(buf, 0, buf.length, pos)
          pos += buf.length
          task.got += buf.length
          publish()
          if (pos > end) break
        }
      } catch (e) {
        lastErr = e
        await sleep(400 * (attempt + 1))
      } finally {
        clearTimeout(timer)
        ac.abort()
      }
    }
    if (pos <= end) throw lastErr || new Error('Download ended early')
  }

  async function download(url, dest, label, { segments = SEGMENTS } = {}) {
    setState({ phase: 'downloading' })
    const task = { label, got: 0, total: 0 }
    tasks.add(task)
    publish()
    const tmp = dest + '.part'
    try {
      fs.mkdirSync(path.dirname(dest), { recursive: true })
      const info = await probeRemote(url).catch((e) => {
        throw new Error(`Download failed (${e.message}) for ${label}`)
      })
      task.total = info.total
      if (info.ranged && info.total >= 2 * MIN_SEGMENT) {
        const n = Math.max(2, Math.min(segments, Math.floor(info.total / MIN_SEGMENT)))
        const size = Math.ceil(info.total / n)
        const urls = info.finalUrl === url ? [url] : [info.finalUrl, url] // signed CDN link first; the original redirects afresh if it expires
        const fh = await fs.promises.open(tmp, 'w')
        try {
          await fh.truncate(info.total)
          await Promise.all(Array.from({ length: n }, (_, i) => [i * size, Math.min(info.total, (i + 1) * size) - 1]).filter(([a, b]) => a <= b).map(([a, b]) => fetchSegment(urls, a, b, fh, task)))
        } finally {
          await fh.close()
        }
      } else {
        // Server without Range support (or a small file): plain single stream.
        const res = await fetchImpl(url, { redirect: 'follow' })
        if (!res.ok || !res.body) throw new Error(`Download failed (HTTP ${res.status}) for ${label}`)
        task.total = Number(res.headers.get('content-length')) || task.total
        const out = fs.createWriteStream(tmp)
        for await (const chunk of res.body) {
          if (!out.write(chunk)) await new Promise((r) => out.once('drain', r))
          task.got += chunk.length
          publish()
        }
        await new Promise((r, j) => (out.end(), out.once('finish', r), out.once('error', j)))
      }
      fs.renameSync(tmp, dest)
      return await sha256File(dest)
    } catch (e) {
      fs.rmSync(tmp, { force: true })
      throw e
    } finally {
      tasks.delete(task)
    }
  }

  // ---- bridge version tracking: the "latest" page redirects to /releases/tag/<tag>, which needs no API key or rate limit ----
  const metaPath = path.join(runtimeDir, 'bridge.json')
  const readMeta = () => {
    try {
      return JSON.parse(fs.readFileSync(metaPath, 'utf8'))
    } catch {
      return {}
    }
  }
  async function latestTag() {
    try {
      const res = await fetchImpl(BRIDGE_REPO, { redirect: 'manual', signal: AbortSignal.timeout(10000) })
      res.body?.cancel?.().catch?.(() => {})
      const m = /\/releases\/tag\/([^/?#]+)/.exec(res.headers.get('location') || res.url || '')
      return m ? decodeURIComponent(m[1]) : null
    } catch {
      return null
    }
  }
  async function latestBridgeSize() {
    try {
      const r = await probeRemote(BRIDGE_URL)
      return r.total || null
    } catch {
      return null
    }
  }

  let updateInfo = null // { at, available, latest }
  // Is a newer bridge published than the one installed? Never throws; offline or unsure means "no update".
  async function checkUpdate({ force = false } = {}) {
    const jar = bridgePath()
    const custom = (getSettings().extensions || {}).bridgeJar
    if (!jar || (custom && jar === custom)) return (updateInfo = { at: Date.now(), available: false, latest: null })
    if (!force && updateInfo && Date.now() - updateInfo.at < UPDATE_TTL) return updateInfo
    const latest = await latestTag()
    if (!latest) return updateInfo || { at: 0, available: false, latest: null }
    const meta = readMeta()
    let available
    if (meta.tag) available = meta.tag !== latest
    else {
      // Installed by an older Shinkai that did not note the version: compare file size with the current release instead.
      const remote = await latestBridgeSize()
      available = remote != null && remote !== fs.statSync(jar).size
      if (remote != null && !available) fs.writeFileSync(metaPath, JSON.stringify({ ...meta, tag: latest }))
    }
    return (updateInfo = { at: Date.now(), available, latest })
  }

  async function installBridge() {
    const [tag, sha] = await Promise.all([latestTag(), download(BRIDGE_URL, bridgeJar, 'Extension runtime', { segments: 4 })])
    fs.writeFileSync(metaPath, JSON.stringify({ sha256: sha, size: fs.statSync(bridgeJar).size, at: Date.now(), tag }))
    updateInfo = null
  }

  async function installJre() {
    const arch = { x64: 'x64', arm64: 'arm64' }[process.arch]
    if (process.platform !== 'linux' || !arch) throw new Error('Automatic Java download supports Linux x64/arm64 only. Install Java 17+ (Arch: sudo pacman -S jre-openjdk) or set its path.')
    const file = JRE_FILES[arch]
    const archive = path.join(runtimeDir, file)
    // Adoptium publishes <file>.sha256.txt; fetch it while the archive downloads and verify when reachable.
    const wantP = fetchImpl(JRE_BASE + file + '.sha256.txt').then((r) => (r.ok ? r.text() : '')).catch(() => '')
    const sha = await download(JRE_BASE + file, archive, 'Java runtime')
    const want = ((await wantP).trim().split(/\s+/)[0] || '').toLowerCase()
    if (want && want !== sha) {
      fs.rmSync(archive, { force: true })
      throw new Error('Java download failed its checksum; try again.')
    }
    setState({ phase: 'extracting', label: 'Unpacking Java runtime', progress: 0 })
    fs.rmSync(jreDir, { recursive: true, force: true })
    fs.mkdirSync(jreDir, { recursive: true })
    const { err } = await run('tar', ['-xzf', archive, '-C', jreDir, '--strip-components=1'], { timeout: 180000 })
    fs.rmSync(archive, { force: true })
    if (err) throw new Error('Could not unpack the Java runtime (is `tar` installed?): ' + err.message)
    if (!fs.existsSync(managedJava())) throw new Error('Java runtime unpacked but bin/java is missing')
  }

  // Install whatever is missing. Safe to call repeatedly; concurrent calls share one run.
  let running = null
  function ensure({ force = false } = {}) {
    running ||= (async () => {
      try {
        fs.mkdirSync(runtimeDir, { recursive: true })
        const java = await findJava()
        const jobs = []
        if (!java || java.tooOld) jobs.push(installJre())
        if (force || !bridgePath()) jobs.push(installBridge())
        const done = await Promise.allSettled(jobs) // download both at once; wait for both before reporting a failure
        const failed = done.find((d) => d.status === 'rejected')
        if (failed) throw failed.reason
        setState({ phase: 'ready', label: '', progress: 1 })
      } catch (e) {
        setState({ phase: 'error', error: e.message })
        throw e
      } finally {
        running = null
      }
    })()
    return running
  }

  async function status() {
    const java = await findJava()
    const jar = bridgePath()
    return {
      java: java ? { found: !java.tooOld, major: java.major, path: java.path, managed: !!java.managed, tooOld: !!java.tooOld } : { found: false },
      bridge: { installed: !!jar, path: jar, size: jar ? fs.statSync(jar).size : 0, version: jar ? readMeta().tag || null : null, update: updateInfo?.available ? { latest: updateInfo.latest } : null },
      setup: { ...state, busy: !!running },
      minJava: MIN_JAVA,
    }
  }

  return { ensure, status, checkUpdate, download, findJava, bridgePath, state, runtimeDir, parseJavaMajor }
}

module.exports = { createRuntime, parseJavaMajor, BRIDGE_URL }
