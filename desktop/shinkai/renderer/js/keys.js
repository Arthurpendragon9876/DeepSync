// Player key bindings. One table, used by every player (the full-screen one opened from Continue / episode sheets and the
// inline one on the episode page), so they can never disagree. Settings → Player → Keyboard shortcuts edits the table.
// A binding is the lower-cased `KeyboardEvent.key` ("k", " ", "arrowleft", ">"). Number keys 0-9 (jump to 0-90 %) and Esc are fixed.

export const PLAYER_ACTIONS = [
  ['playPause', 'Play / pause', [' ', 'k']],
  ['back5', 'Back 5 s', ['arrowleft']],
  ['fwd5', 'Forward 5 s', ['arrowright']],
  ['back10', 'Back 10 s', ['j']],
  ['fwd10', 'Forward 10 s', ['l']],
  ['skip', 'Skip intro (Skip button length)', ['s']],
  ['volUp', 'Volume up', ['arrowup']],
  ['volDown', 'Volume down', ['arrowdown']],
  ['mute', 'Mute', ['m']],
  ['fullscreen', 'Fullscreen', ['f']],
  ['nextEp', 'Next episode', ['n']],
  ['prevEp', 'Previous episode', ['p']],
  ['speedUp', 'Faster', ['>', '.']],
  ['speedDown', 'Slower', ['<', ',']],
  ['fit', 'Fit / fill / stretch', ['r']],
  ['subs', 'Cycle subtitles', ['c']],
]

export const defaultKeys = () => Object.fromEntries(PLAYER_ACTIONS.map(([id, , keys]) => [id, [...keys]]))

const RESERVED = new Set(['escape', 'tab', 'enter', 'shift', 'control', 'alt', 'meta', 'capslock', 'dead', 'unidentified', 'contextmenu'])
export const isBindable = (token) => !!token && !RESERVED.has(token) && !/^[0-9]$/.test(token)

// What the user saved wins per action; anything missing or malformed falls back to the default.
export function bindings(saved) {
  const out = defaultKeys()
  for (const [id] of PLAYER_ACTIONS) {
    const v = saved?.[id]
    if (Array.isArray(v)) out[id] = [...new Set(v.map((t) => String(t).toLowerCase()).filter(isBindable))]
  }
  return out
}

export function actionFor(token, saved) {
  const map = bindings(saved)
  return PLAYER_ACTIONS.map(([id]) => id).find((id) => map[id].includes(token)) || null
}

export const tokenOf = (e) => String(e.key || '').toLowerCase()

const NAMES = { ' ': 'Space', arrowleft: '←', arrowright: '→', arrowup: '↑', arrowdown: '↓', backspace: 'Backspace', delete: 'Delete', home: 'Home', end: 'End', pageup: 'Page Up', pagedown: 'Page Down' }
export const labelOf = (token) => NAMES[token] || (token.length === 1 ? token.toUpperCase() : token[0].toUpperCase() + token.slice(1))

// Give `token` to `id`. A key can only do one thing, so it is taken from whichever action had it. Returns that action's id (or null).
export function assign(saved, id, token) {
  const map = bindings(saved)
  let stolen = null
  for (const [other] of PLAYER_ACTIONS) {
    if (other !== id && map[other].includes(token)) { map[other] = map[other].filter((t) => t !== token); stolen = other }
  }
  if (!map[id].includes(token)) map[id] = [...map[id], token]
  return { map, stolen }
}

export const unassign = (saved, id, token) => ({ ...bindings(saved), [id]: bindings(saved)[id].filter((t) => t !== token) })
