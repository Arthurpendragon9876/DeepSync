'use strict'
// Downloads what the Windows installer bundles, once, into build/win/ (git-ignored):
//   build/win/java/    Temurin 17 JRE (x64)        -> <install dir>/resources/java
//   build/win/bridge/  AnymeX extension bridge JAR -> <install dir>/resources/bridge
// Re-running skips anything already there. Works on Windows, Linux and macOS (needs `unzip`, `tar` or PowerShell to unpack).
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')
const { JRE_FILES, JRE_BASE, BRIDGE_URL } = (() => {
  const r = require('../main/extensions/runtime')
  return { JRE_FILES: r.JRE_FILES, JRE_BASE: r.JRE_BASE, BRIDGE_URL: r.BRIDGE_URL }
})()

const OUT = path.join(__dirname, '..', 'build', 'win')
const JAVA = path.join(OUT, 'java')
const BRIDGE = path.join(OUT, 'bridge', 'anymex_desktop_runtime.jar')

async function download(url, dest) {
  const res = await fetch(url, { redirect: 'follow' })
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
  const buf = Buffer.from(await res.arrayBuffer())
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.writeFileSync(dest, buf)
  return crypto.createHash('sha256').update(buf).digest('hex')
}

function unzip(archive, dest) {
  const tries = [
    ['unzip', ['-q', '-o', archive, '-d', dest]],
    ['tar', ['-xf', archive, '-C', dest]],
    ['powershell.exe', ['-NoProfile', '-Command', `Expand-Archive -LiteralPath '${archive}' -DestinationPath '${dest}' -Force`]],
  ]
  for (const [cmd, args] of tries) {
    try {
      execFileSync(cmd, args, { stdio: 'ignore' })
      return
    } catch {}
  }
  throw new Error('Could not unpack ' + archive + ' (install unzip, or run this on Windows)')
}

async function jre() {
  if (fs.existsSync(path.join(JAVA, 'bin', 'java.exe'))) return console.log('JRE already present')
  const file = JRE_FILES.win32.x64
  const zip = path.join(OUT, file)
  console.log('Downloading', file)
  const sha = await download(JRE_BASE + file, zip)
  const want = (await (await fetch(JRE_BASE + file + '.sha256.txt', { redirect: 'follow' })).text()).trim().split(/\s+/)[0].toLowerCase()
  if (want !== sha) throw new Error(`Checksum mismatch for ${file}: expected ${want}, got ${sha}`)
  const tmp = path.join(OUT, 'jre.tmp')
  fs.rmSync(tmp, { recursive: true, force: true })
  fs.mkdirSync(tmp, { recursive: true })
  unzip(zip, tmp)
  const [top] = fs.readdirSync(tmp)
  fs.rmSync(JAVA, { recursive: true, force: true })
  fs.renameSync(path.join(tmp, top), JAVA)
  fs.rmSync(tmp, { recursive: true, force: true })
  fs.rmSync(zip, { force: true })
  if (!fs.existsSync(path.join(JAVA, 'bin', 'java.exe'))) throw new Error('JRE unpacked but bin/java.exe is missing')
  console.log('JRE ready:', JAVA)
}

async function bridge() {
  if (fs.existsSync(BRIDGE)) return console.log('Bridge already present')
  console.log('Downloading extension bridge')
  await download(BRIDGE_URL, BRIDGE)
  console.log('Bridge ready:', BRIDGE)
}

;(async () => {
  fs.mkdirSync(path.dirname(BRIDGE), { recursive: true }) // electron-builder expects both source folders to exist
  await jre()
  // The bridge is optional for the build: without it the app offers "Set up runtime" on first use instead.
  await bridge().catch((e) => console.warn('Bridge not bundled (' + e.message + '); the app will download it on first use.'))
})().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
