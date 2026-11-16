'use strict'
// Silent auto-updates through electron-updater (NSIS installs only; delta downloads come from the installer's blockmap).
// Inert unless this is a packaged build that shipped an app-update.yml, which electron-builder writes only when a publish target is configured
// (set YOMI_UPDATE_URL when building, see electron-builder.win.js).
const fs = require('fs')
const path = require('path')

function createUpdater({ app, onEvent = () => {}, log = () => {} }) {
  let au = null
  let timer = null
  const configured = () => app.isPackaged && fs.existsSync(path.join(process.resourcesPath, 'app-update.yml'))

  function load() {
    if (au) return au
    if (!configured()) return null
    try {
      ;({ autoUpdater: au } = require('electron-updater'))
    } catch (e) {
      log('electron-updater unavailable: ' + e.message)
      return null
    }
    au.autoDownload = true // downloads in the background, differentially when the blockmap allows it
    au.autoInstallOnAppQuit = true // installs silently the next time DeepSync closes
    au.on('update-available', (i) => onEvent({ type: 'available', version: i.version }))
    au.on('update-downloaded', (i) => onEvent({ type: 'downloaded', version: i.version }))
    au.on('error', (e) => (log('update error: ' + (e && e.message)), onEvent({ type: 'error', message: String(e && e.message) })))
    return au
  }

  async function check() {
    const u = load()
    if (!u) return { enabled: false }
    try {
      const r = await u.checkForUpdates()
      return { enabled: true, version: r?.updateInfo?.version }
    } catch (e) {
      return { enabled: true, error: e.message }
    }
  }

  function start() {
    if (!load()) return
    setTimeout(check, 15000).unref?.()
    timer = setInterval(check, 6 * 3600 * 1000)
    timer.unref?.()
  }
  const stop = () => timer && clearInterval(timer)
  const installNow = () => au && au.quitAndInstall(true, true) // isSilent, forceRunAfter

  return { start, stop, check, installNow, enabled: configured }
}

module.exports = { createUpdater }
