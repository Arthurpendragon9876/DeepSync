'use strict'
// Finds (or downloads) the two things the extension sidecar needs: a Java 17+ runtime and the bridge JAR.
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { execFile } = require('child_process')

const BRIDGE_URL = 'https://github.com/RyanYuuki/AnymeXExtensionRuntimeBridge/releases/latest/download/anymex_desktop_runtime.jar'
const JRE_BASE = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/'
const JRE_FILES = { x64: 'OpenJDK17U-jre_x64_linux_hotspot_17.0.12_7.tar.gz', arm64: 'OpenJDK17U-jre_aarch64_linux_hotspot_17.0.12_7.tar.gz' }
const MIN_JAVA = 17

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

  async function download(url, dest, label) {
    setState({ phase: 'downloading', label, progress: 0 })
    const res = await fetchImpl(url, { redirect: 'follow' })
    if (!res.ok || !res.body) throw new Error(`Download failed (HTTP ${res.status}) for ${label}`)
    const total = Number(res.headers.get('content-length')) || 0
    fs.mkdirSync(path.dirname(dest), { recursive: true })
    const tmp = dest + '.part'
    const out = fs.createWriteStream(tmp)
    const hash = crypto.createHash('sha256')
    let got = 0
    try {
      for await (const chunk of res.body) {
        hash.update(chunk)
        if (!out.write(chunk)) await new Promise((r) => out.once('drain', r))
        got += chunk.length
        state.progress = total ? got / total : 0
        state.label = `${label} ${(got / 1048576).toFixed(1)}${total ? ' / ' + (total / 1048576).toFixed(1) : ''} MB`
      }
      await new Promise((r, j) => (out.end(), out.once('finish', r), out.once('error', j)))
    } catch (e) {
      out.destroy()
      fs.rmSync(tmp, { force: true })
      throw e
    }
    fs.renameSync(tmp, dest)
    return hash.digest('hex')
  }

  async function installBridge() {
    const sha = await download(BRIDGE_URL, bridgeJar, 'Extension runtime')
    fs.writeFileSync(path.join(runtimeDir, 'bridge.json'), JSON.stringify({ sha256: sha, size: fs.statSync(bridgeJar).size, at: Date.now() }))
  }

  async function installJre() {
    const arch = { x64: 'x64', arm64: 'arm64' }[process.arch]
    if (process.platform !== 'linux' || !arch) throw new Error('Automatic Java download supports Linux x64/arm64 only. Install Java 17+ (Arch: sudo pacman -S jre-openjdk) or set its path.')
    const file = JRE_FILES[arch]
    const archive = path.join(runtimeDir, file)
    const sha = await download(JRE_BASE + file, archive, 'Java runtime')
    // Adoptium publishes <file>.sha256.txt; verify when reachable.
    try {
      const r = await fetchImpl(JRE_BASE + file + '.sha256.txt')
      if (r.ok) {
        const want = (await r.text()).trim().split(/\s+/)[0].toLowerCase()
        if (want && want !== sha) throw new Error('Java download failed its checksum; try again.')
      }
    } catch (e) {
      if (/checksum/.test(e.message)) {
        fs.rmSync(archive, { force: true })
        throw e
      }
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
        if (!java || java.tooOld) await installJre()
        if (force || !bridgePath()) await installBridge()
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
      bridge: { installed: !!jar, path: jar, size: jar ? fs.statSync(jar).size : 0 },
      setup: { ...state, busy: !!running },
      minJava: MIN_JAVA,
    }
  }

  return { ensure, status, findJava, bridgePath, state, runtimeDir, parseJavaMajor }
}

module.exports = { createRuntime, parseJavaMajor, BRIDGE_URL }
