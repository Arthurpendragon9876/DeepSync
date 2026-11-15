// Startup screen controller. The markup lives in index.html (so it paints on the very first frame); this only decides when to hide it.
let el = document.getElementById('splash')
const t0 = performance.now()
let timer, gone = false
if (el && document.documentElement.dataset.splash === 'off') { el.remove(); el = null }

const finish = (quick) => {
  if (!el || gone) return
  gone = true
  clearTimeout(timer)
  try { sessionStorage.setItem('ds.splashDone', '1') } catch {}
  el.classList.add('done') // the spinning O closes into a ring
  setTimeout(() => el.classList.add('out'), quick === true ? 0 : 380)
  setTimeout(() => el.remove(), quick === true ? 450 : 850)
}
if (el) { addEventListener('keydown', () => finish(true), { once: true }); el.addEventListener('pointerdown', () => finish(true), { once: true }) }

// Called when the app is ready; keeps the animation on screen long enough for the letters to appear.
export function dismissSplash(minMs = 2300) {
  if (!el || gone) return
  clearTimeout(timer)
  timer = setTimeout(finish, Math.max(0, minMs - (performance.now() - t0)))
}

// Remember the setting where theme-boot.js can read it before first paint.
export function setStartupAnimation(on) {
  try { localStorage.setItem('ds.splash', on === false ? '0' : '1') } catch {}
}
