import { SUB_LANGS } from '../subs.js'
import { PROVIDERS, activeProvider, catalogueOn, fromTracker, openMetadataSheet } from '../metadata.js'
import { mountFab } from '../fab.js'
import { PLAYER_ACTIONS, bindings, defaultKeys, assign, unassign, isBindable, tokenOf, labelOf } from '../keys.js'
import { h, icon, S, api, go, saveSettings, switchEl, segmented, toast, clear } from '../core.js'
import { applyTheme, MODES } from '../theme.js'
import { setStartupAnimation } from '../splash.js'
import { THEMES } from '../theme-data.js'
import { CAROUSELS } from './home.js'
import { mangaPrefs, novelPrefs, setGlobalManga, setGlobalNovel, NOVEL_THEMES } from '../reader-settings.js'
import { groupPreset, setGroupPreset, clearAllGroupPrefs, skipDuplicates, setSkipDuplicates } from '../prefs.js'

const PANES = [
  ['appearance', 'Appearance', 'Theme, color mode, banner style', 'gear'], ['home', 'Home layout', 'Choose which rows appear', 'grid'], ['trackers', 'Trackers', 'AniList and other services', 'sync'],
  ['chapters', 'Chapters', 'Official / unofficial scanlations', 'list'], ['reader', 'Readers', 'Manga and novel reader defaults', 'book'], ['library', 'Library', 'Anime, manga and novel folders', 'folder'], ['player', 'Player', 'Built-in player or mpv', 'play'], ['content', 'Content & privacy', 'Adult filter, incognito, demo mode', 'eyeoff'],
  ['data', 'Data & backup', 'Export, import, cache', 'download'], ['about', 'About', 'Version, licence, credits', 'info'],
]
const row = (title, desc, control) => h('div', { class: 's-row' }, h('div', null, h('b', null, title), desc && h('div', { class: 'd' }, desc)), control)
const sel = (value, opts, onChange) => {
  const el = h('select', { class: 'input', style: { width: 'auto', minWidth: 150 } }, opts.map(([v, l]) => h('option', { value: v, selected: v === value }, l)))
  el.onchange = () => onChange(el.value)
  return el
}

// Settings → Player → Keyboard shortcuts. Each action shows its keys as chips (× removes one); "Add key" waits for the next key press.
// The same table drives the full-screen player and the player on the episode page.
function shortcutsEditor() {
  const box = h('div', { class: 'ks' })
  let capturing = null, stop = null
  const save = async (map) => { await saveSettings({ player: { keys: map } }); draw() }
  const listen = (id) => {
    stop?.(); capturing = id; draw()
    const onKey = async (e) => {
      if (!box.isConnected) return stop()
      if (['Shift', 'Control', 'Alt', 'Meta'].includes(e.key)) return // wait for the real key
      e.preventDefault(); e.stopImmediatePropagation()
      const t = tokenOf(e)
      stop()
      if (t === 'escape') return draw()
      if (e.ctrlKey || e.metaKey || e.altKey) return (toast('Shortcuts can’t use Ctrl, Alt or ⌘', true), draw())
      if (!isBindable(t)) return (toast(/^[0-9]$/.test(t) ? 'Number keys are reserved for jumping to 0-90 % of the video' : 'That key can’t be used', true), draw())
      const { map, stolen } = assign(S.settings.player.keys, id, t)
      if (stolen) toast(`${labelOf(t)} moved here from “${PLAYER_ACTIONS.find((a) => a[0] === stolen)[1]}”`)
      save(map)
    }
    document.addEventListener('keydown', onKey, true)
    stop = () => (document.removeEventListener('keydown', onKey, true), (capturing = null), (stop = null))
  }
  function draw() {
    const map = bindings(S.settings.player.keys)
    const custom = PLAYER_ACTIONS.some(([id, , d]) => map[id].join('|') !== d.join('|'))
    box.replaceChildren(
      ...PLAYER_ACTIONS.map(([id, name]) => h('div', { class: 's-row ks-row' }, h('b', null, name),
        h('div', { class: 'ks-keys' },
          ...map[id].map((t) => h('span', { class: 'ks-chip' }, h('kbd', null, labelOf(t)), h('button', { class: 'ks-x', title: 'Remove this key', 'aria-label': 'Remove ' + labelOf(t), onclick: () => save(unassign(S.settings.player.keys, id, t)) }, '×'))),
          !map[id].length && h('span', { class: 'd' }, 'No key'),
          h('button', { class: 'btn sm' + (capturing === id ? ' primary' : ''), onclick: () => (capturing === id ? (stop?.(), draw()) : listen(id)) }, capturing === id ? 'Press a key… (Esc cancels)' : '+ Add key')))),
      h('div', { style: { display: 'flex', gap: 12, alignItems: 'center', margin: '4px 0 6px' } },
        h('button', { class: 'btn', disabled: !custom, onclick: () => { stop?.(); save(defaultKeys()) } }, 'Reset to defaults'),
        h('span', { class: 'note' }, 'Number keys 0-9 (jump to 0-90 %) and Esc stay fixed. A key does one thing, so adding one that’s taken moves it.')))
  }
  draw()
  return box
}

const panes = {
  appearance() {
    const setTheme = async (patch) => { await saveSettings(patch); applyTheme(S.settings) }
    const grid2 = h('div', { class: 'theme-grid' })
    const drawThemes = () => {
      const dk = document.documentElement.dataset.mode === 'dark'
      grid2.replaceChildren(...THEMES.map(([id, name, pd, pl, cd, cl]) => h('button', { class: 'tcard' + (S.settings.theme === id ? ' on' : ''), title: name, onclick: async () => { await setTheme({ theme: id }); drawThemes() } },
        h('span', { class: 'tpv', style: { background: dk ? cd : cl } }, h('i', { style: { background: dk ? pd : pl } }), h('em', { style: { background: dk ? pd : pl } })), h('b', null, name))))
    }
    drawThemes()
    const sw = null
    const styles = [['spotlight', 'Spotlight', 'Full-width banner', 'pv-sp'], ['card', 'Card', 'Contained card with poster', 'pv-card'], ['panels', 'Panels', 'Tilted panel collage', 'pv-pan']]
    const grid = h('div', { class: 'style-cards' }, styles.map(([k, n, d, cls]) => h('div', { class: 'style-card' + (S.settings.bannerStyle === k ? ' on' : ''), onclick: async () => { await saveSettings({ bannerStyle: k }); grid.querySelectorAll('.style-card').forEach((c, i) => c.classList.toggle('on', styles[i][0] === k)) } },
      h('div', { class: 'pv ' + (k === 'panels' ? '' : cls) }, k === 'card' ? [h('i'), h('em')] : k === 'panels' ? h('div', { class: 'pv-pan' }, [h('i'), h('i'), h('i'), h('i')]) : null), h('b', null, n), h('span', null, d))))
    return [
      row('Color mode', 'Dark, light, or follow your system', segmented(MODES, S.settings.colorMode || 'dark', async (v) => { await setTheme({ colorMode: v }); drawThemes() })),
      row('AMOLED black', 'Pure black background in dark mode', switchEl(!!S.settings.amoled, (v) => setTheme({ amoled: v }))),
      row('Startup animation', 'Play the Shinkai boot screen when the app opens', switchEl(S.settings.startupAnimation !== false, async (v) => { await saveSettings({ startupAnimation: v }); setStartupAnimation(v) })),
      h('div', { class: 'h3' }, 'Theme'), h('p', { class: 'note', style: { marginBottom: 10 } }, 'The same color themes as the Android app.'), grid2,
      h('div', { class: 'h3' }, 'Home banner style'), grid,
      row('Auto-rotate banner', 'Advance to the next title every 8 seconds', switchEl(S.settings.autoRotate, (v) => saveSettings({ autoRotate: v }))),
      row('Metadata source', `Which tracker drives Home, search and titles opened from a source. Now: ${catalogueOn() ? 'Source catalogue' : PROVIDERS[activeProvider()].name}, covers and info from ${fromTracker() ? 'the tracker' : 'the source'}. Same as the floating preferences button.`, h('button', { class: 'btn', onclick: () => openMetadataSheet() }, icon('sliders', 18), 'Configure')),
      row('Floating buttons', 'The preferences and mode buttons in the corner of every page. Tap the mode button to continue, drag it to switch mode, hold it for the last title’s details. Needs a restart of the page.', switchEl(S.settings.floatingButtons !== false, async (v) => { await saveSettings({ floatingButtons: v }); mountFab() })),
      row('Title language', 'How titles are displayed everywhere', sel(S.settings.titleLanguage, [['romaji', 'Romaji'], ['english', 'English'], ['native', 'Native']], (v) => saveSettings({ titleLanguage: v }))),
      row('Custom title bar', 'Draw window buttons inside the app. Leave off to use your window manager’s title bar (Hyprland, KDE, GNOME…). Needs a restart.', switchEl(S.settings.customTitlebar, (v) => { saveSettings({ customTitlebar: v }); toast('Restart Shinkai to apply') })),
    ]
  },
  home() {
    const hidden = new Set(S.settings.hiddenCarousels)
    return [h('p', { class: 'note', style: { marginBottom: 14 } }, 'Switch Home rows on or off. Changes apply the next time you open Home.'),
      ...CAROUSELS.map(([k, n, d]) => row(n, d, switchEl(!hidden.has(k), async (v) => { v ? hidden.delete(k) : hidden.add(k); await saveSettings({ hiddenCarousels: [...hidden] }) })))]
  },
  trackers() {
    const t = S.settings.trackers.anilist
    const box = h('div')
    const draw = () => {
      box.replaceChildren()
      if (S.user) {
        box.append(row(S.user.name, 'Connected to AniList. Progress is synced when you finish an episode or chapter.', h('button', { class: 'btn danger', onclick: async () => { await api('anilist:logout'); S.user = null; window.dispatchEvent(new Event('ds:user')); draw() } }, 'Sign out')))
        return
      }
      const cid = h('input', { class: 'input', placeholder: 'AniList client ID', value: t.clientId || '', onchange: () => api('anilist:login', { clientId: cid.value.trim() }) })
      const tok = h('input', { class: 'input', placeholder: 'Paste access token', type: 'password' })
      box.append(h('div', { class: 'card' },
        h('p', { class: 'note' }, '1. Create a client at ', h('a', { href: '#', onclick: (e) => (e.preventDefault(), window.open('https://anilist.co/settings/developer')) }, 'anilist.co/settings/developer'), ' with the redirect URL ', h('code', null, 'https://anilist.co/api/v2/oauth/pin'), '.', h('br'), '2. Enter its client ID, open the login page and approve access.', h('br'), '3. Paste the token AniList shows you here.'),
        h('div', { style: { display: 'grid', gap: 10, marginTop: 14 } }, cid,
          h('div', null, h('button', { class: 'btn', onclick: () => cid.value.trim() ? window.open(`https://anilist.co/api/v2/oauth/authorize?client_id=${encodeURIComponent(cid.value.trim())}&response_type=token`) : toast('Enter your client ID first', true) }, icon('external', 16), 'Open AniList login')),
          tok, h('div', null, h('button', { class: 'btn primary', onclick: async () => {
            try { const r = await api('anilist:login', { token: tok.value, clientId: cid.value.trim() }); S.user = { ...r.user }; window.dispatchEvent(new Event('ds:user')); toast('Connected as ' + r.user.name); draw() } catch (e) { toast(e.message, true) }
          } }, 'Connect')))))
    }
    draw()
    return [h('h3', { class: 'h3', style: { marginTop: 0 } }, 'AniList'), box, h('h3', { class: 'h3' }, 'Other services'),
      ...['MyAnimeList', 'MangaBaka'].map((n) => row(n, 'Available in the Android app. Not implemented in the desktop version yet.', h('span', { class: 'chip' }, 'Soon')))]
  },
  reader() {
    const m = mangaPrefs(''), n = novelPrefs('')
    const rng = (v, min, max, step, fmt, on) => { const out = h('em', { style: { minWidth: 56, textAlign: 'right' } }, fmt(v)); const r = h('input', { type: 'range', min, max, step, value: v, style: { width: 200 }, oninput: () => { out.textContent = fmt(+r.value); on(+r.value) } }); return h('div', { style: { display: 'flex', gap: 10, alignItems: 'center' } }, r, out) }
    const gm = (k, label, desc, ctl) => row(label, desc, ctl)
    const M = (patch) => setGlobalManga(patch), N = (patch) => setGlobalNovel(patch)
    return [
      h('div', { class: 'h3' }, 'Manga reader'),
      h('p', { class: 'note' }, 'Defaults for titles you open for the first time. Each title then remembers its own choices; use the button at the bottom to make every title follow these again.'),
      gm('autoMode', 'Detect webtoon or manga', 'On the first open of a title, checks its pages and uses Webtoon for long strips (when the default below is not Webtoon)', switchEl(m.autoMode !== false, (v) => M({ autoMode: v }))),
      gm('mode', 'Default reading mode', null, sel(m.mode, [['webtoon', 'Webtoon'], ['ltr', 'Standard'], ['rtl', 'Right to left'], ['vertical', 'Vertical']], (v) => M({ mode: v }))),
      gm('width', 'Webtoon width', null, rng(m.width, 360, 1400, 20, (v) => v + ' px', (v) => M({ width: v }))),
      gm('gap', 'Gap between pages', null, rng(m.gap, 0, 48, 2, (v) => v + ' px', (v) => M({ gap: v }))),
      gm('fit', 'Fit (paged modes)', null, sel(m.fit, [['screen', 'Screen'], ['width', 'Width'], ['height', 'Height']], (v) => M({ fit: v }))),
      gm('bg', 'Background', null, sel(m.bg, [['#0b0b0d', 'Black'], ['#1c1c22', 'Dark'], ['#ffffff', 'White']], (v) => M({ bg: v }))),
      gm('double', 'Double pages', 'Two pages side by side in Standard and Right to left', switchEl(m.double, (v) => M({ double: v }))),
      gm('dpOrder', 'Double page order', 'Manga is read right to left, so the right page comes first. Arrow keys and tap zones stay left = back, right = forward.', sel(m.dpOrder, [['rtl', 'Right to left (manga)'], ['ltr', 'Left to right']], (v) => M({ dpOrder: v }))),
      gm('cover', 'Cover on its own', 'With double pages, show the first page alone', switchEl(m.coverAlone, (v) => M({ coverAlone: v }))),
      gm('anim', 'Page transition', 'Paged modes', sel(m.anim, [['none', 'None'], ['fade', 'Fade'], ['slide', 'Slide']], (v) => M({ anim: v }))),
      gm('auto', 'Next chapter automatically', 'Webtoon: keep scrolling at the end to continue', switchEl(m.autoNext, (v) => M({ autoNext: v }))),
      gm('awake', 'Keep screen awake', null, switchEl(m.awake, (v) => M({ awake: v }))),
      gm('eye', 'Eye-care reminder', 'A gentle nudge to rest your eyes', sel(String(m.eye), [['0', 'Off'], ['20', 'Every 20 min'], ['45', 'Every 45 min'], ['60', 'Every 60 min']], (v) => M({ eye: Number(v) }))),
      gm('counter', 'Page counter', null, switchEl(m.counter, (v) => M({ counter: v }))),
      gm('tap', 'Invert tap zones', 'Left edge goes forward in paged modes', switchEl(m.tapInvert, (v) => M({ tapInvert: v }))),
      gm('speed', 'Auto scroll speed', null, rng(m.autoSpeed, 1, 10, 1, (v) => v, (v) => M({ autoSpeed: v }))),
      h('div', { class: 'h3' }, 'Novel reader'),
      gm('theme', 'Theme', null, sel(n.theme, Object.entries(NOVEL_THEMES).map(([k, t]) => [k, t[2] || k]), (v) => N({ theme: v }))),
      gm('font', 'Font', null, sel(n.font, [['serif', 'Serif'], ['sans', 'Sans'], ['mono', 'Mono']], (v) => N({ font: v }))),
      gm('size', 'Font size', null, rng(n.size, 14, 34, 1, (v) => v + ' px', (v) => N({ size: v }))),
      gm('lh', 'Line spacing', null, rng(n.lh, 1.2, 2.6, 0.05, (v) => v.toFixed(2), (v) => N({ lh: v }))),
      gm('para', 'Paragraph spacing', null, rng(n.para, 0, 2.2, 0.1, (v) => v.toFixed(1) + ' em', (v) => N({ para: v }))),
      gm('nw', 'Text width', 'The text column stays centred', rng(n.width, 400, 1400, 20, (v) => v + ' px', (v) => N({ width: v }))),
      gm('nz', 'Zoom', 'Ctrl + wheel, + / − in the reader', rng(n.zoom, 60, 220, 5, (v) => v + '%', (v) => N({ zoom: v }))),
      gm('al', 'Alignment', null, sel(n.align, [['left', 'Left'], ['justify', 'Justify'], ['center', 'Center']], (v) => N({ align: v }))),
      gm('bio', 'Bionic reading', 'Bolds the start of each word', switchEl(n.bionic, (v) => N({ bionic: v }))),
      row('Make every title follow these', 'Clears the choices each manga and novel remembered for itself', h('button', { class: 'btn', onclick: () => { try { Object.keys(localStorage).filter((k) => /^ds\.(reader|novel):/.test(k)).forEach((k) => localStorage.removeItem(k)); toast('Titles now follow your defaults') } catch {} } }, 'Apply to all')),
    ]
  },
  chapters() {
    return [row('Preferred scanlation', 'Sources like MangaFire list the same chapters under several groups (official and unofficial). Each chapter is taken from the preferred group and falls back to the next group for chapters it does not have. “Most chapters” picks whichever group has the longest list. Applies when you open a series.',
      sel(groupPreset(), [['most', 'Group with the most chapters'], ['unofficial', 'Unofficial first'], ['official', 'Official first']], (v) => { setGroupPreset(v); toast('Saved. Reopen the series to apply') })),
    row('Skip duplicate chapters', 'List, count and read each chapter once even when several groups have it. Also in the reader’s Extra tab.', switchEl(skipDuplicates(), (v) => { setSkipDuplicates(v); toast('Saved. Reopen the series to apply') })),
    row('Per-series choices', 'Groups you switch off and the Duplicates toggle are remembered for each series. Clear them to fall back to the preset everywhere.', h('button', { class: 'btn', onclick: () => { clearAllGroupPrefs(); toast('Per-series choices cleared') } }, 'Clear'))]
  },
  library() {
    const out = h('div')
    const result = h('div', { class: 'note', style: { marginTop: 10 } })
    const draw = () => {
      out.replaceChildren(...[['anime', 'Anime', 'Video files (.mkv .mp4 .webm …) grouped by series'], ['manga', 'Manga', '.cbz/.zip files or folders of images'], ['novel', 'Novels', '.epub files']].map(([k, n, d]) => {
        const input = h('input', { class: 'input', placeholder: '/path/to/' + k })
        const add = async (p) => { p = (p || '').trim(); if (!p) return; const cur = S.settings.libraryPaths[k]; if (!cur.includes(p)) await saveSettings({ libraryPaths: { [k]: [...cur, p] } }); draw() }
        return h('div', { style: { marginBottom: 22 } }, h('div', { class: 'h3', style: { margin: '0 0 4px' } }, n), h('div', { class: 'note' }, d),
          h('div', { class: 'path-list' }, S.settings.libraryPaths[k].map((p) => h('div', { class: 'path-item' }, p, h('button', { class: 'ibtn', style: { width: 30, height: 30 }, onclick: async () => { await saveSettings({ libraryPaths: { [k]: S.settings.libraryPaths[k].filter((x) => x !== p) } }); draw() } }, icon('x', 16))))),
          h('div', { style: { display: 'flex', gap: 8 } }, input, h('button', { class: 'btn', onclick: async () => { const p = await api('dialog:folder'); p ? add(p) : toast('No folder dialog here. Type the path instead.') } }, 'Browse'), h('button', { class: 'btn primary', onclick: () => add(input.value) }, 'Add')))
      }))
    }
    draw()
    return [out, h('button', { class: 'btn primary', onclick: async (e) => { e.currentTarget.disabled = true; result.textContent = 'Scanning…'; const l = await api('library:scan'); e.currentTarget.disabled = false; result.textContent = `Found ${l.anime.length} anime, ${l.manga.length} manga and ${l.novel.length} novels.` } }, icon('sync', 18), 'Scan now'), result]
  },
  player() {
    return [row('Playback', 'Built-in uses Chromium (H.264/VP9/AV1, AAC/Opus). mpv plays everything but doesn’t report progress back automatically.', sel(S.settings.player.mode, [['builtin', 'Built-in player'], ['mpv', 'Always use mpv']], (v) => saveSettings({ player: { mode: v } }))),
      row('Auto-pick stream', 'Choose the stream for you using the quality and audio below, instead of asking', switchEl(S.settings.player.autoPick !== false, (v) => saveSettings({ player: { autoPick: v } }))),
      row('Preferred quality', 'The player picks the closest stream at or below this. Auto takes the best, or the last quality you chose.', sel(String(S.settings.player.quality || 'auto'), [['auto', 'Auto'], ['1080', '1080p'], ['720', '720p'], ['480', '480p'], ['360', '360p']], (v) => saveSettings({ player: { quality: v } }))),
      row('Sub or Dub', 'Sub: original audio, with subtitles switched on automatically. Dub: English audio, subtitles left off. Used when the source offers both.', sel(S.settings.player.audio || 'sub', [['sub', 'Sub (subtitles on)'], ['dub', 'Dub (subtitles off)']], (v) => saveSettings({ player: { audio: v } }))),
      row('Subtitle language', 'The language picked automatically when Sub is selected. Signs & Songs tracks are only used if there is nothing better. You can still change it in the player; that choice sticks for the next episodes.', sel(S.settings.player.subLang || 'en', [...SUB_LANGS, ['off', 'No subtitles']], (v) => saveSettings({ player: { subLang: v } }))),
      row('Play next automatically', 'Start the next episode when one ends', switchEl(S.settings.player.autoNext !== false, (v) => saveSettings({ player: { autoNext: v } }))),
      row('Volume keys and scroll', 'System changes the real system volume, so it can go louder than the player alone. Player only changes the video’s own level, which is scaled by the system volume. The volume slider always changes the player’s level.', sel(S.settings.player.volumeScope || 'system', [['system', 'System volume'], ['player', 'Player only']], (v) => saveSettings({ player: { volumeScope: v } }))),
      row('Skip button length', 'Seconds the Skip button jumps ahead (intro or recap)', sel(String(S.settings.player.skipSeconds || 85), [['30', '30 s'], ['60', '60 s'], ['85', '85 s'], ['90', '90 s'], ['120', '120 s']], (v) => saveSettings({ player: { skipSeconds: Number(v) } }))),
      h('div', { class: 'h3' }, 'Keyboard shortcuts'), shortcutsEditor(),
      row('mpv command', 'Name or full path of the mpv executable', h('input', { class: 'input', style: { width: 220 }, value: S.settings.player.mpvPath, onchange: (e) => saveSettings({ player: { mpvPath: e.target.value.trim() || 'mpv' } }) })),
      h('p', { class: 'note' }, 'Install mpv on Arch with ', h('code', null, 'sudo pacman -S mpv'), ', on Debian/Ubuntu with ', h('code', null, 'sudo apt install mpv'), '.')]
  },
  content() {
    return [row('Hide adult content', 'Filters adult titles from browse, search and schedule', switchEl(S.settings.hideAdult, (v) => saveSettings({ hideAdult: v }))),
      row('Incognito mode', 'Don’t save progress locally or sync it to trackers', switchEl(S.settings.incognito, async (v) => { await saveSettings({ incognito: v }); window.dispatchEvent(new Event('ds:route')) })),
      row('Demo mode', 'Use built-in sample data instead of AniList (handy for trying the app offline)', switchEl(S.settings.demo, async (v) => { await saveSettings({ demo: v }); toast(v ? 'Demo mode on' : 'Demo mode off'); window.dispatchEvent(new Event('ds:boot')) }))]
  },
  data() {
    const file = h('input', { type: 'file', accept: '.json', class: 'hidden', onchange: async (e) => { try { await api('backup:import', { data: JSON.parse(await e.target.files[0].text()) }); toast('Backup imported'); window.dispatchEvent(new Event('ds:boot')) } catch (er) { toast('Invalid backup file: ' + er.message, true) } } })
    const toB64 = (buf) => { let s = ''; const u = new Uint8Array(buf); for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s) }
    const androidFile = h('input', { type: 'file', accept: '.zip', class: 'hidden', onchange: async (e) => { try { const f = e.target.files[0]; if (!f) return; const r = await api('backup:importAndroid', { b64: toB64(await f.arrayBuffer()) }); toast(`Imported ${r.favourites} favourites, ${r.history} history entries` + (r.unmatched.length ? ` (${r.unmatched.length} skipped: not linked to AniList)` : '')); window.dispatchEvent(new Event('ds:boot')) } catch (er) { toast('Cannot read Android backup: ' + er.message, true) } e.target.value = '' } })
    return [row('Export backup', 'Settings, progress, library links, favourites, history and stats as a JSON file. Tracker tokens are never included.', h('button', { class: 'btn', onclick: async () => {
      const blob = new Blob([JSON.stringify(await api('backup:export'), null, 2)], { type: 'application/json' })
      const a = h('a', { href: URL.createObjectURL(blob), download: 'shinkai-backup.json' }); document.body.append(a); a.click(); a.remove()
    } }, icon('download', 18), 'Export')),
    row('Import backup', 'Merges a previously exported file', h('button', { class: 'btn', onclick: () => file.click() }, 'Import')), file,
    row('Import Android backup', 'Reads a .bk.zip from the Android app. Only titles tracked with AniList there can be brought over (favourites, categories, history).', h('button', { class: 'btn', onclick: () => androidFile.click() }, 'Import')), androidFile,
    row('Clear cache', 'Forget cached AniList responses', h('button', { class: 'btn', onclick: async () => { await api('cache:clear'); toast('Cache cleared') } }, 'Clear')),
    row('Data folder', 'Where settings and progress are stored', h('button', { class: 'btn', onclick: () => api('app:openDataDir') }, icon('folder', 18), 'Open'))]
  },
  about() {
    return [h('div', { class: 'card', style: { display: 'flex', gap: 18, alignItems: 'center' } }, h('img', { src: 'assets/icon.png', width: 72, height: 72, style: { borderRadius: 18 }, alt: '' }),
      h('div', null, h('h3', { style: { fontSize: 22 } }, 'Shinkai'), h('div', { class: 'note' }, 'Version 0.1.0 · GPL-3.0 · Linux'))),
      h('p', { class: 'note', style: { marginTop: 18 } }, 'Shinkai is the desktop companion to DeepSync (Yugen) for Android; the Android app is built on Kotatsu and DropSauce. The layout takes its design cues from Seanime. Metadata comes from AniList; chapter pages from MangaDex or your own files.')]
  },
}

export async function settings({ params }) {
  const tab = params.tab || 'appearance'
  const menu = h('div', { class: 's-menu' }, PANES.map(([k, n, d, ic]) => h('div', { class: 's-item' + (k === tab ? ' on' : ''), 'data-k': k, onclick: () => go('/settings/' + k) }, h('div', { class: 'ib' }, icon(ic, 18)), h('div', null, n, h('small', null, d)))))
  const title = PANES.find((p) => p[0] === tab)?.[1] || 'Settings'
  return h('div', { class: 'settings' }, menu, h('div', { class: 's-pane' }, h('h2', null, title), ...(panes[tab] || panes.appearance)()))
}
