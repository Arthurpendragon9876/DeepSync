'use strict'
// Everything that makes Yomi feel native on Windows: Mica / Acrylic, native caption buttons (Snap Layouts), tray, jump list,
// taskbar progress, toast notifications, window-state restore inside the work area, system accent colour, login item and updates.
// Windows-only; main.js loads this file only when process.platform === 'win32'.
const { app, BrowserWindow, Tray, Menu, Notification, nativeImage, nativeTheme, systemPreferences, screen } = require('electron')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')
const H = require('./helpers')
const { createUpdater } = require('./updater')

const APP_ID = 'com.kotatsu.yomi'
const TITLEBAR_H = 36
const WIN_DEFAULTS = { material: 'auto', systemAccent: false, closeToTray: false, minimizeToTray: false, startWithWindows: false, notifications: true, taskbarProgress: true, checkUpdates: true }

function create({ store, getWin, api, iconPath, quit }) {
  const cfg = () => ({ ...WIN_DEFAULTS, ...(store.settings.windows || {}) })
  // Theme colours reported by the renderer (see setLook): caption-button symbols, the solid window colour and the system light/dark scheme.
  let look = H.lookVars((store.settings.windows || {}).look)
  const stateFile = path.join(app.getPath('userData'), 'window-state.json')
  let tray = null
  let quitting = false
  let ready = false
  let busy = 0
  let trayHintShown = false
  const queue = []
  const toasts = new Set()
  const release = os.release()

  // ---- OS facts -------------------------------------------------------------------------------------------------
  const transparencyOn = () => {
    try {
      const out = execFileSync('reg', ['query', 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize', '/v', 'EnableTransparency'], { timeout: 2000, windowsHide: true, encoding: 'utf8' })
      return H.parseTransparency(out)
    } catch {
      return true
    }
  }
  const material = () => H.pickMaterial({ choice: cfg().material, release, transparency: transparencyOn(), highContrast: nativeTheme.shouldUseHighContrastColors })
  const accent = () => {
    if (!cfg().systemAccent) return null
    try {
      return H.accentVars(systemPreferences.getAccentColor())
    } catch {
      return null
    }
  }
  const info = () => ({ platform: 'win32', build: H.windowsBuild(release), win11: H.isWin11(release), material: material(), accent: accent(), titlebarHeight: TITLEBAR_H, version: app.getVersion(), tray: !!tray, startWithWindows: loginEnabled(), updates: updater.enabled() && cfg().checkUpdates })
  const loginEnabled = () => {
    try {
      return !!app.getLoginItemSettings().openAtLogin
    } catch {
      return false
    }
  }
  const push = () => getWin() && !getWin().isDestroyed() && getWin().webContents.send('ds:win-info', info())

  // ---- window creation ------------------------------------------------------------------------------------------
  function loadState() {
    try {
      const s = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
      if (![s.x, s.y, s.width, s.height].every(Number.isFinite)) return null
      const wa = screen.getDisplayMatching(s).workArea
      return { ...H.fitToWorkArea(s, wa), maximized: !!s.maximized }
    } catch {
      return null
    }
  }

  // Options merged into `new BrowserWindow(...)`. Native caption buttons (titleBarOverlay) are what give Windows 11 Snap Layouts on the maximise button.
  function windowOptions() {
    const m = material()
    const saved = loadState()
    return {
      ...(saved && { x: saved.x, y: saved.y, width: saved.width, height: saved.height }),
      show: false, // shown on ready-to-show so there is no white flash
      titleBarStyle: 'hidden',
      titleBarOverlay: { color: m === 'none' ? look.solid : '#00000000', symbolColor: look.symbol, height: TITLEBAR_H },
      backgroundColor: m === 'none' ? look.solid : '#00000000',
      ...(m !== 'none' && { backgroundMaterial: m }),
      _maximized: !!(saved && saved.maximized),
    }
  }

  function attach(win, { maximized, hidden }) {
    nativeTheme.themeSource = look.mode // follows the app's Dark / Light / System choice, so Mica / Acrylic and system menus match
    win.once('ready-to-show', () => {
      if (maximized) win.maximize()
      if (hidden && cfg().closeToTray) return // launched at login: stay in the tray
      win.show()
    })
    let t
    const save = () => {
      clearTimeout(t)
      t = setTimeout(() => {
        if (win.isDestroyed() || win.isMinimized() || win.isFullScreen()) return
        const b = win.isMaximized() ? win.getNormalBounds() : win.getBounds()
        try {
          fs.writeFileSync(stateFile, JSON.stringify({ ...b, maximized: win.isMaximized() }))
        } catch {}
      }, 400)
    }
    for (const ev of ['resize', 'move', 'maximize', 'unmaximize']) win.on(ev, save)

    // Taskbar moved, auto-hide toggled, monitor unplugged or DPI changed: pull the window back inside the work area (taskbar excluded).
    const refit = () => {
      if (win.isDestroyed() || win.isMaximized() || win.isFullScreen() || !win.isVisible()) return
      const b = win.getBounds()
      const fit = H.fitToWorkArea(b, screen.getDisplayMatching(b).workArea)
      if (fit.x !== b.x || fit.y !== b.y || fit.width !== b.width || fit.height !== b.height) win.setBounds(fit)
    }
    for (const ev of ['display-metrics-changed', 'display-removed', 'display-added']) screen.on(ev, refit)

    win.on('close', (e) => {
      if (!quitting && cfg().closeToTray && tray) {
        e.preventDefault()
        win.hide()
        if (!trayHintShown) ((trayHintShown = true), notify({ title: 'Yomi is still running', body: 'It stays in the notification area. Right-click the tray icon to quit.', force: true }))
      }
    })
    win.on('minimize', () => cfg().minimizeToTray && tray && win.hide())
    win.on('hide', () => throttledJumpList())
    win.on('closed', () => clearTimeout(t))
    win.webContents.on('did-finish-load', () => (ready = false))
  }

  // ---- tray -----------------------------------------------------------------------------------------------------
  const show = () => {
    const w = getWin()
    if (!w) return
    if (w.isMinimized()) w.restore()
    w.show()
    w.focus()
  }
  const navigate = (route) => (show(), send('ds:navigate', route))
  function buildTray() {
    if (tray) return
    const img = nativeImage.createFromPath(iconPath).resize({ width: 32, height: 32 })
    tray = new Tray(img)
    tray.setToolTip('Yomi')
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: 'Open Yomi', click: show },
        { type: 'separator' },
        { label: 'Home', click: () => navigate('/') },
        { label: 'Local library', click: () => navigate('/library') },
        { label: 'History', click: () => navigate('/history') },
        { label: 'Settings', click: () => navigate('/settings') },
        { type: 'separator' },
        { label: 'Quit Yomi', click: () => ((quitting = true), quit()) },
      ]),
    )
    tray.on('click', () => (getWin()?.isVisible() && getWin().isFocused() ? getWin().hide() : show()))
  }
  const destroyTray = () => {
    tray?.destroy()
    tray = null
  }

  // ---- notifications (Windows toast; Focus Assist / quiet hours are applied by the OS, so toasts land in the Action Center) -----------
  function notify({ title, body, route, force } = {}) {
    if (!Notification.isSupported() || (!force && !cfg().notifications)) return false
    const w = getWin()
    if (!force && w && w.isVisible() && w.isFocused()) return false // you are looking at it already
    const n = new Notification({ title: String(title || 'Yomi').slice(0, 80), body: String(body || '').slice(0, 240), icon: iconPath })
    toasts.add(n)
    n.on('click', () => (route ? navigate(route) : show()))
    n.on('close', () => toasts.delete(n))
    n.show()
    return true
  }

  // ---- taskbar progress -----------------------------------------------------------------------------------------
  const setProgress = (v) => {
    const w = getWin()
    if (!w || w.isDestroyed()) return
    if (!cfg().taskbarProgress || v == null || v < 0) return w.setProgressBar(-1)
    w.setProgressBar(v >= 2 ? 2 : v, { mode: v >= 2 ? 'indeterminate' : 'normal' })
  }
  async function whileBusy(fn, doneToast) {
    busy++
    setProgress(2)
    try {
      return await fn()
    } finally {
      if (--busy === 0) setProgress(-1)
      if (doneToast) notify({ title: 'Yomi', body: doneToast })
    }
  }
  // Mirrors the Java runtime / extension-bridge download into the taskbar button and toasts when it finishes.
  function trackRuntime(getState) {
    let was = 'idle'
    const iv = setInterval(() => {
      let st
      try {
        st = getState()
      } catch {
        return
      }
      if (!st) return
      if (!busy) setProgress(H.progressFor(st))
      if (was !== st.phase) {
        if (['downloading', 'extracting'].includes(was) && st.phase === 'ready') notify({ title: 'Extension runtime ready', body: 'Java and the extension bridge are installed. Aniyomi / Mihon sources can run now.', route: '/extensions' })
        if (st.phase === 'error') notify({ title: 'Extension setup failed', body: String(st.error || 'Open Extensions to try again.'), route: '/extensions' })
        was = st.phase
      }
    }, 500)
    iv.unref?.()
  }

  // ---- jump list ------------------------------------------------------------------------------------------------
  let jlAt = 0
  const throttledJumpList = () => Date.now() - jlAt > 30000 && refreshJumpList()
  function refreshJumpList() {
    if (!app.isPackaged) return // the jump list launches this exe; in dev that would be electron.exe
    jlAt = Date.now()
    try {
      const { recent, tasks } = H.jumpListModel(Object.values(store.history || {}))
      const item = (t) => ({ type: 'task', title: t.title, description: t.description, program: process.execPath, args: `--open=${t.route}`, iconPath: process.execPath, iconIndex: 0 })
      const removed = new Set((app.getJumpListSettings().removedItems || []).map((i) => i.args))
      const cats = []
      const rec = recent.filter((t) => !removed.has(`--open=${t.route}`))
      if (rec.length) cats.push({ type: 'custom', name: 'Recent titles', items: rec.map(item) })
      cats.push({ type: 'tasks', items: tasks.map(item) })
      app.setJumpList(cats)
    } catch {}
  }

  // ---- launch arguments, deep links, file associations -----------------------------------------------------------------
  function send(channel, payload) {
    const w = getWin()
    if (w && !w.isDestroyed() && ready) w.webContents.send(channel, payload)
    else queue.push([channel, payload])
  }
  function handleLaunch(argv, focus = true) {
    const a = H.parseLaunchArgs(argv)
    if (a.repo) {
      api.call('ext:repoAdd', { url: a.repo }).then((r) => {
        send('ds:navigate', '/extensions')
        send('ds:toast', r.ok ? 'Repo added from link' : String(r.error?.message || 'Could not add that repo'))
      })
    }
    if (a.file) (api.allow(a.file.path), send('ds:open-file', a.file))
    if (a.route) send('ds:navigate', a.route)
    if (focus) show()
  }
  const markReady = () => {
    ready = true
    for (const [c, p] of queue.splice(0)) getWin()?.webContents.send(c, p)
    push()
  }

  // ---- theme ------------------------------------------------------------------------------------------------------
  // The renderer owns the colours (Material themes, light / dark / system); it reports what the window chrome needs whenever they change.
  function setLook(l) {
    const next = H.lookVars(l)
    if (JSON.stringify(next) === JSON.stringify(look)) return info()
    look = next
    try {
      store.patchSettings({ windows: { look } }) // remembered so the next launch opens with the right colours before the page loads
    } catch {}
    nativeTheme.themeSource = look.mode // fires 'updated', which re-applies the material and caption colours
    return apply()
  }

  // ---- apply settings ---------------------------------------------------------------------------------------------
  function apply() {
    const w = getWin()
    const c = cfg()
    const m = material()
    if (w && !w.isDestroyed()) {
      try {
        w.setBackgroundMaterial(m === 'none' ? 'none' : m)
        w.setBackgroundColor(m === 'none' ? look.solid : '#00000000')
        w.setTitleBarOverlay({ color: m === 'none' ? look.solid : '#00000000', symbolColor: look.symbol, height: TITLEBAR_H })
      } catch {}
    }
    c.closeToTray || c.minimizeToTray ? buildTray() : destroyTray()
    if (app.isPackaged) {
      try {
        app.setLoginItemSettings({ openAtLogin: !!c.startWithWindows, args: ['--hidden'] })
      } catch {}
    }
    push()
    return info()
  }

  // ---- updates ----------------------------------------------------------------------------------------------------
  const updater = createUpdater({
    app,
    log: (m) => console.warn('[updater]', m),
    onEvent: (e) => {
      if (e.type === 'downloaded') notify({ title: `Yomi ${e.version} is ready`, body: 'It installs quietly the next time you close Yomi.', force: true })
      send('ds:update', e)
    },
  })

  function start() {
    app.setAppUserModelId(APP_ID)
    apply()
    refreshJumpList()
    const iv = setInterval(refreshJumpList, 10 * 60 * 1000)
    iv.unref?.()
    if (cfg().checkUpdates) updater.start()
    try {
      systemPreferences.on('accent-color-changed', push)
    } catch {}
    nativeTheme.on('updated', () => (apply(), undefined))
  }

  return {
    windowOptions, attach, start, apply, setLook, info, notify, setProgress, whileBusy, trackRuntime, handleLaunch, markReady, refreshJumpList,
    setQuitting: () => (quitting = true),
    checkUpdates: () => updater.check(),
    installUpdate: () => updater.installNow(),
    hiddenLaunch: () => process.argv.includes('--hidden'),
    shutdown: () => (updater.stop(), destroyTray()),
  }
}

module.exports = { create, WIN_DEFAULTS, APP_ID }
