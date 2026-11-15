'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')

const subs = () => import('../renderer/js/subs.js')

test('subs: picks the track in the wanted language and prefers the full dialogue track', async () => {
  const { pickSubtitle } = await subs()
  const tracks = [
    { label: 'Arabic' }, { label: 'Spanish (Latin America)' }, { label: 'English (Signs & Songs)' }, { label: 'English' }, { label: 'English (SDH)' }, { label: 'French' },
  ]
  assert.equal(tracks[pickSubtitle(tracks, 'en')].label, 'English', 'full English beats Signs & Songs and SDH')
  assert.equal(tracks[pickSubtitle(tracks, 'es')].label, 'Spanish (Latin America)')
  assert.equal(tracks[pickSubtitle(tracks, 'fr')].label, 'French')
  assert.equal(pickSubtitle(tracks, 'de'), -1, 'no German track: nothing is switched on')
  assert.equal(pickSubtitle(tracks, 'off'), -1)
  assert.equal(pickSubtitle([], 'en'), -1)
  // language codes in the lang field, labels that are only codes
  assert.equal(pickSubtitle([{ label: 'Track 1', lang: 'ja' }, { label: 'Track 2', lang: 'en' }], 'en'), 1)
  assert.equal(pickSubtitle([{ label: 'jpn' }, { label: 'eng - Full' }], 'en'), 1)
  // only a Signs & Songs track in that language: still better than nothing
  assert.equal(pickSubtitle([{ label: 'English (Signs & Songs)' }], 'en'), 0)
  // look-alikes must not match ("Bengali" is not English, "Finnish" is not "fi" from another language)
  assert.equal(pickSubtitle([{ label: 'Bengali' }, { label: 'Indonesian' }], 'en'), -1)
  assert.equal(pickSubtitle([{ label: 'Indonesian' }], 'id'), 0)
})

test('subs: langOf recognises the label styles sources use', async () => {
  const { langOf } = await subs()
  assert.equal(langOf('English'), 'en'); assert.equal(langOf('en-US'), 'en'); assert.equal(langOf('Español (España)'), 'es')
  assert.equal(langOf('Português (Brasil)'), 'pt'); assert.equal(langOf('العربية'), 'ar'); assert.equal(langOf('Klingon'), '')
})

test('mpv gets the same Sub/Dub choice as the built-in player', async () => {
  const { Store } = require('../main/store')
  const { createApi } = require('../main/api')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-mpv-'))
  const out = path.join(dir, 'args.txt')
  const fake = path.join(dir, 'mpv')
  fs.writeFileSync(fake, `#!/bin/sh\nprintf '%s\\n' "$@" > "${out}"\n`, { mode: 0o755 })
  const run = async (player) => {
    const store = new Store(dir)
    store.patchSettings({ player: { mpvPath: fake, ...player } })
    const api = createApi({ store, zipUrl: () => '', fetchImpl: async () => ({ ok: false }) })
    fs.rmSync(out, { force: true })
    const r = await api.call('ext:mpv', { url: 'https://v.example/a.m3u8', headers: {}, subtitles: [] })
    assert.equal(r.ok, true)
    for (let i = 0; i < 40 && !fs.existsSync(out); i++) await new Promise((x) => setTimeout(x, 50))
    api.shutdown()
    return fs.readFileSync(out, 'utf8').split('\n')
  }
  let a = await run({ audio: 'sub', subLang: 'en' })
  assert.ok(a.includes('--alang=jpn,ja,jp') && a.includes('--slang=eng,en'), 'sub: Japanese audio, English subtitles')
  a = await run({ audio: 'sub', subLang: 'es' })
  assert.ok(a.includes('--slang=spa,es'))
  a = await run({ audio: 'sub', subLang: 'off' })
  assert.ok(a.includes('--sid=no') && !a.some((x) => x.startsWith('--slang')))
  a = await run({ audio: 'dub', subLang: 'en' })
  assert.ok(a.includes('--alang=eng,en') && a.includes('--sid=no'), 'dub: English audio, no subtitles')
})

const keysMod = () => import('../renderer/js/keys.js')

test('keys: defaults match the old hard-coded player keys, saved choices win, junk is ignored', async () => {
  const { actionFor, bindings } = await keysMod()
  assert.equal(actionFor(' ', undefined), 'playPause'); assert.equal(actionFor('k', undefined), 'playPause')
  assert.equal(actionFor('arrowleft', undefined), 'back5'); assert.equal(actionFor('>', undefined), 'speedUp'); assert.equal(actionFor('z', undefined), null)
  assert.equal(actionFor('z', { skip: ['z'] }), 'skip'); assert.equal(actionFor('s', { skip: ['z'] }), null, 'a remapped action no longer answers to its old key')
  assert.deepEqual(bindings({ mute: 'nope', fit: ['1', 'escape', 'X'] }).mute, ['m'], 'malformed value falls back to the default')
  assert.deepEqual(bindings({ fit: ['1', 'escape', 'X'] }).fit, ['x'], 'digits and Esc are reserved; keys are stored lower-case')
  assert.deepEqual(bindings({ fit: [] }).fit, [], 'an action can be left without a key')
})

test('keys: assigning a taken key moves it, removing and resetting work', async () => {
  const { assign, unassign, bindings, defaultKeys } = await keysMod()
  const { map, stolen } = assign(undefined, 'fit', 'k')
  assert.equal(stolen, 'playPause'); assert.deepEqual(map.fit, ['r', 'k']); assert.deepEqual(map.playPause, [' '])
  assert.deepEqual(unassign(map, 'fit', 'r').fit, ['k'])
  assert.deepEqual(bindings(defaultKeys()), defaultKeys())
})

test('system volume: moves the sound server volume, clamps, unmutes and falls back across tools', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-vol-'))
  const state = path.join(dir, 'vol')
  fs.writeFileSync(state, '40 on')
  // fake wpctl: "get-volume" prints Volume: 0.40 [MUTED]; "set-volume X" writes X; "set-mute 0" unmutes
  fs.writeFileSync(path.join(dir, 'wpctl'), `#!/bin/sh
read v m < "${state}"
case "$1" in
  get-volume) if [ "$m" = off ]; then echo "Volume: 0.$v [MUTED]"; else echo "Volume: 0.$v"; fi ;;
  set-volume) n=$(echo "$3" | sed 's/^0\\.//;s/^1\\.00$/100/'); echo "$n $m" > "${state}" ;;
  set-mute) echo "$v on" > "${state}" ;;
esac
`, { mode: 0o755 })
  const oldPath = process.env.PATH
  process.env.PATH = dir + path.delimiter + '/usr/bin' + path.delimiter + '/bin'
  try {
    delete require.cache[require.resolve('../main/sysvolume')]
    const sv = require('../main/sysvolume')
    let r = await sv.change(5)
    assert.equal(r.percent, 45); assert.equal(r.backend, 'wpctl')
    r = await sv.change(-10)
    assert.equal(r.percent, 35)
    fs.writeFileSync(state, '40 off')
    await sv.change(5)
    assert.equal(fs.readFileSync(state, 'utf8').trim(), '45 on', 'raising the volume also unmutes')
    process.env.PATH = '/nonexistent'
    r = await sv.change(5)
    assert.ok(r.error && /wpctl/.test(r.error), 'no mixer tool: a clear error so the player can fall back')
  } finally { process.env.PATH = oldPath }
})
