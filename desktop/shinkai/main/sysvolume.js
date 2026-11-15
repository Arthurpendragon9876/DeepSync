'use strict'
// System (sound-server) volume for the player's volume keys and mouse wheel. A <video> element's own volume only scales
// the stream *below* whatever the system volume is, so it can never be louder than the system level and never moves it.
// Tries PipeWire (wpctl), then PulseAudio (pactl), then ALSA (amixer). No Electron imports, so it can be tested with fake binaries.
const { execFile } = require('child_process')

const run = (cmd, args) => new Promise((resolve) => execFile(cmd, args, { timeout: 2500 }, (err, out) => resolve(err ? { err } : { out: String(out) })))
const clamp = (n) => Math.max(0, Math.min(100, Math.round(n)))

const BACKENDS = [
  {
    name: 'wpctl',
    async get() {
      const r = await run('wpctl', ['get-volume', '@DEFAULT_AUDIO_SINK@'])
      const m = r.out && /Volume:\s*([\d.]+)/.exec(r.out)
      return m ? { percent: clamp(parseFloat(m[1]) * 100), muted: /MUTED/.test(r.out) } : null
    },
    async set(p, unmute) {
      const r = await run('wpctl', ['set-volume', '@DEFAULT_AUDIO_SINK@', (p / 100).toFixed(2)])
      if (!r.err && unmute) await run('wpctl', ['set-mute', '@DEFAULT_AUDIO_SINK@', '0'])
      return !r.err
    },
  },
  {
    name: 'pactl',
    async get() {
      const [v, m] = await Promise.all([run('pactl', ['get-sink-volume', '@DEFAULT_SINK@']), run('pactl', ['get-sink-mute', '@DEFAULT_SINK@'])])
      const x = v.out && /(\d+)%/.exec(v.out)
      return x ? { percent: clamp(+x[1]), muted: /yes/i.test(m.out || '') } : null
    },
    async set(p, unmute) {
      const r = await run('pactl', ['set-sink-volume', '@DEFAULT_SINK@', p + '%'])
      if (!r.err && unmute) await run('pactl', ['set-sink-mute', '@DEFAULT_SINK@', '0'])
      return !r.err
    },
  },
  {
    name: 'amixer',
    async get() {
      const r = await run('amixer', ['sget', 'Master'])
      const x = r.out && /\[(\d+)%\]/.exec(r.out)
      return x ? { percent: clamp(+x[1]), muted: /\[off\]/.test(r.out) } : null
    },
    async set(p, unmute) {
      const r = await run('amixer', ['sset', 'Master', p + '%', ...(unmute ? ['unmute'] : [])])
      return !r.err
    },
  },
]

let working = null // the backend that last worked
// Moves the system volume by `delta` percentage points (clamped 0-100). Raising it also unmutes.
async function change(delta) {
  const d = Math.max(-50, Math.min(50, Math.round(Number(delta) || 0)))
  for (const b of working ? [working, ...BACKENDS.filter((x) => x !== working)] : BACKENDS) {
    const cur = await b.get()
    if (!cur) continue
    const percent = clamp(cur.percent + d)
    if (!(await b.set(percent, d > 0 && cur.muted))) continue
    working = b
    return { percent, backend: b.name }
  }
  working = null
  return { error: 'Could not reach the system mixer. Install wpctl (PipeWire), pactl (PulseAudio) or amixer (ALSA).' }
}

module.exports = { change }
