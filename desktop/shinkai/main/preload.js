'use strict'
// Sandboxed preload: the only bridge between the UI and the main process.
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('deepsync', {
  call: (channel, args) => ipcRenderer.invoke('ds:call', channel, args),
  win: {
    action: (name) => ipcRenderer.invoke('ds:win', name),
    onState: (cb) => ipcRenderer.on('ds:win-state', (_e, state) => cb(state)),
  },
  platform: process.platform,
  mediaBase: 'deepsync://media/',
  zipBase: 'deepsync://zip/',
})
