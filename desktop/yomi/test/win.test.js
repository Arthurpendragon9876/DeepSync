'use strict'
// Windows layer: pure helpers and the bundled-Java lookup. Runs on any OS (a fake "java.exe" shell script stands in for the real one).
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')

const H = require('../main/win/helpers')
const { createRuntime, JRE_FILES, windowsJavaDirs } = require('../main/extensions/runtime')

test('Windows build detection and material support', () => {
  assert.equal(H.windowsBuild('10.0.22631'), 22631)
  assert.equal(H.windowsBuild('garbage'), 0)
  assert.equal(H.isWin11('10.0.22000'), true)
  assert.equal(H.isWin11('10.0.19045'), false)
  assert.equal(H.supportsMaterial('10.0.22000'), false) // Windows 11 21H2 has no setBackgroundMaterial
  assert.equal(H.supportsMaterial('10.0.22621'), true)
})

test('pickMaterial falls back to solid when the OS cannot or should not draw it', () => {
  const rel = '10.0.22631'
  assert.equal(H.pickMaterial({ choice: 'auto', release: rel }), 'mica')
  assert.equal(H.pickMaterial({ choice: 'acrylic', release: rel }), 'acrylic')
  assert.equal(H.pickMaterial({ choice: 'tabbed', release: rel }), 'tabbed')
  assert.equal(H.pickMaterial({ choice: 'none', release: rel }), 'none')
  assert.equal(H.pickMaterial({ choice: 'mica', release: '10.0.19045' }), 'none') // Windows 10
  assert.equal(H.pickMaterial({ choice: 'mica', release: rel, transparency: false }), 'none') // transparency effects off
  assert.equal(H.pickMaterial({ choice: 'mica', release: rel, highContrast: true }), 'none')
  assert.equal(H.pickMaterial({ choice: 'bogus', release: rel }), 'mica')
})

test('parseTransparency reads the registry output; missing means on', () => {
  assert.equal(H.parseTransparency('    EnableTransparency    REG_DWORD    0x0\r\n'), false)
  assert.equal(H.parseTransparency('    EnableTransparency    REG_DWORD    0x1\r\n'), true)
  assert.equal(H.parseTransparency(''), true)
})

test('accentVars converts the Windows accent colour and picks a readable foreground', () => {
  assert.deepEqual(H.accentVars('0078d4ff'), { rgb: '0 120 212', on: '#ffffff' })
  assert.equal(H.accentVars('ffe066ff').on, '#0b0b10') // light accent -> dark text
  assert.equal(H.accentVars('nope'), null)
})

test('progressFor maps the runtime setup state to taskbar progress', () => {
  assert.equal(H.progressFor({ phase: 'downloading', progress: 0.4 }), 0.4)
  assert.equal(H.progressFor({ phase: 'downloading', progress: 0 }), 2)
  assert.equal(H.progressFor({ phase: 'extracting', progress: 0 }), 2)
  assert.equal(H.progressFor({ phase: 'ready', progress: 1 }), -1)
  assert.equal(H.progressFor(null), -1)
})

test('parseLaunchArgs accepts routes, aniyomi links and cbz/epub files; rejects the rest', () => {
  const yes = () => true
  const a = H.parseLaunchArgs(['DeepSync.exe', '--open=/media/anime/21', 'aniyomi://add-repo?url=https://x.y/index.min.json', 'C:\\books\\a.EPUB'], yes)
  assert.equal(a.route, '/media/anime/21')
  assert.equal(a.repo, 'aniyomi://add-repo?url=https://x.y/index.min.json')
  assert.equal(a.file.kind, 'epub')
  assert.equal(H.parseLaunchArgs(['x', '--open=/../etc'], yes).route, null)
  assert.equal(H.parseLaunchArgs(['x', '--open=http://evil'], yes).route, null)
  assert.equal(H.parseLaunchArgs(['x', 'movie.mkv'], yes).file, null) // not an association
  assert.equal(H.parseLaunchArgs(['x', 'gone.cbz'], () => false).file, null) // must exist
  assert.equal(H.parseLaunchArgs(['x', 'a.zip'], yes).file, null) // .zip is deliberately not claimed
})

test('jumpListModel lists the five newest titles and always offers the fixed tasks', () => {
  const hist = Array.from({ length: 8 }, (_, i) => ({ mode: 'anime', id: i + 1, title: 'T' + (i + 1), at: i, label: 'Episode ' + i }))
  hist.push({ mode: 'manga', id: null, title: 'no id', at: 99 })
  const m = H.jumpListModel(hist)
  assert.equal(m.recent.length, 5)
  assert.equal(m.recent[0].route, '/media/anime/8')
  assert.deepEqual(m.tasks.map((t) => t.route), ['/library', '/extensions', '/settings'])
  assert.deepEqual(H.jumpListModel(null).recent, [])
})

test('fitToWorkArea keeps the window above the taskbar and on its monitor', () => {
  const wa = { x: 0, y: 0, width: 1920, height: 1040 } // 1080 screen minus a 40 px taskbar
  assert.deepEqual(H.fitToWorkArea({ x: 100, y: 100, width: 1000, height: 700 }, wa), { x: 100, y: 100, width: 1000, height: 700 })
  assert.deepEqual(H.fitToWorkArea({ x: -3000, y: 900, width: 1360, height: 860 }, wa), { x: 0, y: 180, width: 1360, height: 860 })
  assert.deepEqual(H.fitToWorkArea({ x: 0, y: 0, width: 5000, height: 5000 }, wa), { x: 0, y: 0, width: 1920, height: 1040 })
  // second monitor to the left with a taskbar on the left edge (work area starts at x = -1840)
  assert.deepEqual(H.fitToWorkArea({ x: -2000, y: 0, width: 900, height: 600 }, { x: -1840, y: 0, width: 1840, height: 1080 }), { x: -1840, y: 0, width: 900, height: 600 })
})

test('the Windows JRE download is a zip and ARM64 uses the x64 build', () => {
  assert.match(JRE_FILES.win32.x64, /windows_hotspot.*\.zip$/)
  assert.equal(JRE_FILES.win32.arm64, JRE_FILES.win32.x64)
  assert.match(JRE_FILES.linux.x64, /linux.*\.tar\.gz$/)
})

test('windowsJavaDirs finds vendor folders under Program Files', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-pf-'))
  fs.mkdirSync(path.join(root, 'Eclipse Adoptium', 'jdk-21.0.4.7-hotspot', 'bin'), { recursive: true })
  const dirs = windowsJavaDirs({ ProgramFiles: root })
  assert.deepEqual(dirs, [path.join(root, 'Eclipse Adoptium', 'jdk-21.0.4.7-hotspot', 'bin', 'java.exe')])
})

test('runtime prefers the JRE and bridge bundled in the installer and never downloads', async () => {
  const res = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-res-'))
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-data-'))
  fs.mkdirSync(path.join(res, 'java', 'bin'), { recursive: true })
  fs.mkdirSync(path.join(res, 'bridge'), { recursive: true })
  const fake = path.join(res, 'java', 'bin', 'java.exe') // a script, so it also runs on Linux/macOS CI
  fs.writeFileSync(fake, '#!/bin/sh\necho \'openjdk version "17.0.12" 2024-07-16\' >&2\n', { mode: 0o755 })
  fs.writeFileSync(path.join(res, 'bridge', 'anymex_desktop_runtime.jar'), 'jar')
  let fetched = 0
  const rt = createRuntime({ dir: data, getSettings: () => ({}), platform: 'win32', resourcesDir: res, fetchImpl: async () => (fetched++, { ok: false }) })
  const st = await rt.status()
  assert.equal(st.java.found, true)
  assert.equal(st.java.major, 17)
  assert.equal(st.java.path, fake)
  assert.equal(st.java.managed, true)
  assert.equal(st.bridge.installed, true)
  assert.equal(st.bridge.path, path.join(res, 'bridge', 'anymex_desktop_runtime.jar'))
  await rt.ensure()
  assert.equal(rt.state.phase, 'ready')
  assert.equal(fetched, 0)
  // a downloaded (newer) bridge in the user's data folder wins over the bundled one
  fs.mkdirSync(path.join(data, 'runtime'), { recursive: true })
  fs.writeFileSync(path.join(data, 'runtime', 'anymex_desktop_runtime.jar'), 'newer')
  assert.equal(rt.bridgePath(), path.join(data, 'runtime', 'anymex_desktop_runtime.jar'))
})

test('lookVars: the theme colours the renderer reports are validated before they reach the window chrome', () => {
  assert.deepEqual(H.lookVars({ mode: 'light', solid: '#F6FAFE', symbol: '#171C20' }), { mode: 'light', solid: '#f6fafe', symbol: '#171c20' })
  assert.deepEqual(H.lookVars({ mode: 'system', solid: '#101418', symbol: '#dfe3e9' }).mode, 'system')
  // anything malformed falls back to the default dark look instead of reaching Electron
  assert.deepEqual(H.lookVars(null), H.DEFAULT_LOOK)
  assert.deepEqual(H.lookVars('light'), H.DEFAULT_LOOK)
  assert.deepEqual(H.lookVars({ mode: 'sepia', solid: 'red', symbol: 'url(x)' }), H.DEFAULT_LOOK)
  assert.equal(H.lookVars({ mode: 'dark', solid: '#12345', symbol: '#dfe3e9' }).solid, H.DEFAULT_LOOK.solid)
})
