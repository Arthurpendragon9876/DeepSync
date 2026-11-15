'use strict'
// Sandboxed preload: the only bridge between the UI and the main process.
const { contextBridge, ipcRenderer } = require('electron')

const on = (channel) => (cb) => ipcRenderer.on(channel, (_e, payload) => cb(payload))

contextBridge.exposeInMainWorld('deepsync', {
  call: (channel, args) => ipcRenderer.invoke('ds:call', channel, args),
  win: {
    action: (name, arg) => ipcRenderer.invoke('ds:win', name, arg),
    onState: on('ds:win-state'),
    // Windows-only extras (all of these are harmless no-ops on Linux)
    info: () => ipcRenderer.invoke('ds:win', 'info'),
    apply: () => ipcRenderer.invoke('ds:win', 'apply'),
    setLook: (l) => ipcRenderer.invoke('ds:win', 'look', l),
    ready: () => ipcRenderer.invoke('ds:win', 'ready'),
    notify: (n) => ipcRenderer.invoke('ds:win', 'notify', n),
    progress: (v) => ipcRenderer.invoke('ds:win', 'progress', v),
    checkUpdates: () => ipcRenderer.invoke('ds:win', 'checkUpdates'),
    installUpdate: () => ipcRenderer.invoke('ds:win', 'installUpdate'),
    onInfo: on('ds:win-info'),
    onNavigate: on('ds:navigate'),
    onOpenFile: on('ds:open-file'),
    onToast: on('ds:toast'),
    onUpdate: on('ds:update'),
  },
  platform: process.platform,
  mediaBase: 'deepsync://media/',
  zipBase: 'deepsync://zip/',
})
