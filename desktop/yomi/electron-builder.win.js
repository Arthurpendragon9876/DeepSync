'use strict'
// Yomi (Windows) packaging.   npm run dist:win  on Windows   |   bash tools/build-yomi.sh  on Linux (see README "Build Yomi")
//   -> dist/Yomi-Setup-<version>-x64.exe  (one-click, per-user NSIS installer, no admin prompt)
//   -> dist/Yomi-Portable-<version>-x64.zip  (only with YOMI_ZIP=1 / build-yomi.sh --zip: unzip anywhere, run Yomi.exe)
//
// Optional auto-update feed: set YOMI_UPDATE_URL to the folder that will hold latest.yml and the installer (any static host / GitHub Releases
// "download" URL). Without it the app is built with updates switched off. The old DEEPSYNC_* names still work.
const env = (n) => process.env['YOMI_' + n] || process.env['DEEPSYNC_' + n]

const publish = env('UPDATE_URL') ? [{ provider: 'generic', url: env('UPDATE_URL') }] : undefined

module.exports = {
  appId: 'com.kotatsu.yomi', // also the AppUserModelID: toasts, jump list and taskbar pinning hang off it
  productName: 'Yomi',
  directories: { buildResources: 'build', output: 'dist' },
  extraMetadata: { description: 'Anime, manga and novels with tracker sync' },
  files: ['main/**', 'renderer/**', 'package.json', '!**/*.md', '!**/test/**'],
  // Java 17 JRE and the extension bridge travel inside the installer (filled by tools/fetch-win-runtime.js), so no separate Java install is needed.
  extraResources: [
    { from: 'build/win/java', to: 'java' },
    { from: 'build/win/bridge', to: 'bridge' },
    { from: 'build/win/mpv', to: 'mpv' }, // optional: drop an mpv build here to bundle it
  ],
  win: {
    target: [{ target: 'nsis', arch: ['x64'] }, ...(env('ZIP') ? [{ target: 'zip', arch: ['x64'] }] : [])],
    icon: 'build/icon.ico', // 16-256 px, generated from build/icon.png
    artifactName: 'Yomi-Portable-${version}-${arch}.${ext}', // used by the zip target; the installer name is set under nsis
    publisherName: 'Yomi',
    legalTrademarks: 'Yomi',
    // Code signing: set CSC_LINK / CSC_KEY_PASSWORD (or Azure Trusted Signing) to sign; unsigned builds trigger SmartScreen.
    // build-yomi.sh sets YOMI_NO_EXE_EDIT: on Linux it brands Yomi.exe itself (see the script) instead of letting electron-builder call wine.
    signAndEditExecutable: !env('NO_EXE_EDIT'),
  },
  nsis: {
    oneClick: true, // single-click install, no wizard
    perMachine: false, // per-user: no UAC prompt, and silent background updates work
    runAfterFinish: true,
    createDesktopShortcut: true,
    createStartMenuShortcut: true,
    shortcutName: 'Yomi',
    installerIcon: 'build/icon.ico',
    uninstallerIcon: 'build/icon.ico',
    deleteAppDataOnUninstall: false, // library links, history and the downloaded runtime survive an uninstall / reinstall
    differentialPackage: true, // blockmap, so updates download only what changed
    artifactName: 'Yomi-Setup-${version}-${arch}.${ext}',
  },
  fileAssociations: [
    { ext: 'cbz', name: 'Comic Book Archive', description: 'Comic Book Archive (CBZ)', role: 'Viewer' },
    { ext: 'epub', name: 'EPUB Book', description: 'EPUB eBook', role: 'Viewer' },
  ],
  protocols: [{ name: 'Aniyomi repository link', schemes: ['aniyomi'] }],
  ...(publish && { publish }),
}
