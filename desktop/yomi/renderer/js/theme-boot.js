// Runs before first paint (plain script in <head>, CSP forbids inline scripts) so the window never flashes the wrong mode.
// theme.js keeps these localStorage keys in sync with the saved settings.
;(function () {
  var d = document.documentElement, v = function (k, f) { try { return localStorage.getItem(k) || f } catch (e) { return f } }
  var mode = v('ds.colorMode', 'system')
  if (mode === 'system') mode = matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  d.dataset.theme = v('ds.theme', 'sky')
  d.dataset.mode = mode
  if (v('ds.amoled', '0') === '1') d.dataset.amoled = '1'
  d.style.colorScheme = mode
  // splash: off when disabled in settings, already played in this window session (reloads), or the OS asks for reduced motion
  var off = v('ds.splash', '1') === '0' || matchMedia('(prefers-reduced-motion: reduce)').matches
  try { if (sessionStorage.getItem('ds.splashDone')) off = true } catch (e) {}
  if (off) d.dataset.splash = 'off'
})()
