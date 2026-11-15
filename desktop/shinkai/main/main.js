'use strict'
const { app, BrowserWindow, ipcMain, protocol, dialog, shell, Menu } = require('electron')
const path = require('path')
const fs = require('fs')
const { Readable } = require('stream')
const { Store } = require('./store')
const { createApi } = require('./api')
const { fileResponse, zipResponse, proxyResponse, proxyUrlFor, streamResponse, streamUrlFor, mime, b64, unb64 } = require('./serve')

// The app is called Shinkai (DeepSync is the project). Electron derives the settings folder from the app name, so keep using the
// old ~/.config/DeepSync folder when it exists; otherwise existing settings, library and logins would silently disappear.
app.setName('Shinkai')
{
  const legacy = path.join(app.getPath('appData'), 'DeepSync')
  // Judge by saved data, not by the folder merely existing: an empty or freshly created ~/.config/Shinkai must not hide the old folder.
  const hasData = (d) => fs.existsSync(path.join(d, 'settings.json'))
  if (hasData(legacy) && !hasData(path.join(app.getPath('appData'), 'Shinkai'))) app.setPath('userData', legacy)
}
// Wayland-friendly defaults (Hyprland, Sway, GNOME, KDE). Falls back to X11 automatically.
app.commandLine.appendSwitch('ozone-platform-hint', 'auto')
app.commandLine.appendSwitch('enable-features', 'WaylandWindowDecorations,VaapiVideoDecoder')

protocol.registerSchemesAsPrivileged([{ scheme: 'deepsync', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } }])

if (!app.requestSingleInstanceLock()) app.quit()

const RENDERER = path.join(__dirname, '..', 'renderer')
let win, store, api

const zipUrl = (file, entry) => `deepsync://zip/?p=${b64(file)}&e=${encodeURIComponent(entry)}`

function toResponse(r) {
  const body = r.body && typeof r.body.pipe === 'function' ? Readable.toWeb(r.body) : r.body
  return new Response(body, { status: r.status, headers: r.headers })
}

async function handleProtocol(req) {
  try {
    const u = new URL(req.url)
    if (u.hostname === 'app') {
      const rel = decodeURIComponent(u.pathname === '/' ? '/index.html' : u.pathname)
      const file = path.normalize(path.join(RENDERER, rel))
      if (!file.startsWith(RENDERER + path.sep)) return new Response('Forbidden', { status: 403 })
      return new Response(await fs.promises.readFile(file), { headers: { 'Content-Type': mime(file) } })
    }
    if (u.hostname === 'stream') return toResponse(await streamResponse(u.searchParams.get('u'), u.searchParams.get('h'), req.headers.get('range'), streamUrlFor('deepsync://stream/')))
    if (u.hostname === 'proxy') return toResponse(await proxyResponse(u.searchParams.get('u'), u.searchParams.get('h')))
    const p = unb64(u.searchParams.get('p'))
    if (!api.isAllowed(p)) return new Response('Forbidden', { status: 403 })
    if (u.hostname === 'media') return toResponse(await fileResponse(p, req.headers.get('range')))
    if (u.hostname === 'zip') return toResponse(await zipResponse(p, u.searchParams.get('e')))
    return new Response('Not found', { status: 404 })
  } catch (e) {
    return new Response(String(e.message || e), { status: e.code === 'ENOENT' ? 404 : 500 })
  }
}

function createWindow() {
  const custom = store.settings.customTitlebar
  win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 900, minHeight: 600,
    backgroundColor: '#0d0e11', title: 'Shinkai', icon: path.join(__dirname, '..', 'build', 'icon.png'),
    frame: !custom, autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true },
  })
  win.loadURL('deepsync://app/index.html')
  // Links open in the system browser; the app itself never navigates away.
  win.webContents.setWindowOpenHandler(({ url }) => (/^https?:/.test(url) && shell.openExternal(url), { action: 'deny' }))
  win.webContents.on('will-navigate', (e, url) => !url.startsWith('deepsync://app') && e.preventDefault())
  win.webContents.on('before-input-event', (e, i) => {
    if (i.type === 'keyDown' && (i.key === 'F12' || (i.control && i.shift && i.key.toLowerCase() === 'i'))) win.webContents.toggleDevTools()
  })
  const sendState = () => win.webContents.send('ds:win-state', { maximized: win.isMaximized(), fullscreen: win.isFullScreen() })
  for (const ev of ['maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen']) win.on(ev, sendState)
}

app.whenReady().then(() => {
  store = new Store(app.getPath('userData'))
  api = createApi({
    store, zipUrl, proxyUrl: proxyUrlFor('deepsync://proxy/'), streamUrl: streamUrlFor('deepsync://stream/'),
    dialogs: {
      folder: async () => (await dialog.showOpenDialog(win, { properties: ['openDirectory'] })).filePaths[0],
      file: async (kind) => {
        const filters = { video: ['mkv', 'mp4', 'webm', 'avi', 'mov'], comic: ['cbz', 'zip'], epub: ['epub'] }[kind]
        return (await dialog.showOpenDialog(win, { properties: ['openFile'], filters: filters && [{ name: kind, extensions: filters }] })).filePaths[0]
      },
      openDataDir: () => shell.openPath(app.getPath('userData')),
    },
  })
  protocol.handle('deepsync', handleProtocol)
  ipcMain.handle('ds:call', (e, channel, args) => {
    if (!e.senderFrame.url.startsWith('deepsync://app')) return { ok: false, error: { message: 'Blocked' } }
    return api.call(channel, args)
  })
  ipcMain.handle('ds:win', (_e, action) => {
    if (action === 'min') win.minimize()
    else if (action === 'max') (win.isMaximized() ? win.unmaximize() : win.maximize())
    else if (action === 'close') win.close()
    else if (action === 'fullscreen') win.setFullScreen(!win.isFullScreen())
    else if (action === 'relaunch') (app.relaunch(), app.exit(0))
  })
  Menu.setApplicationMenu(null)
  createWindow()
  app.on('activate', () => BrowserWindow.getAllWindows().length === 0 && createWindow())
})

app.on('second-instance', () => win && (win.isMinimized() && win.restore(), win.focus()))
app.on('before-quit', () => (store?.flush(), api?.shutdown()))
app.on('window-all-closed', () => app.quit())
