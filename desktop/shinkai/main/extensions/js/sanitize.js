'use strict'
// Chapter text from online novel sources is untrusted HTML. Parse it, keep only a small set of reading-oriented tags
// and attributes, and route images through the app's image proxy (so Referer-protected hosts work).
const cheerio = require('cheerio')

const KEEP = new Set(['p', 'br', 'hr', 'div', 'span', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'em', 'strong', 'b', 'i', 'u', 's', 'strike', 'del', 'ins', 'sub', 'sup', 'small',
  'blockquote', 'ul', 'ol', 'li', 'img', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'pre', 'code', 'ruby', 'rt', 'rp', 'figure', 'figcaption', 'center', 'dl', 'dt', 'dd'])
const DROP = new Set(['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'select', 'textarea', 'link', 'meta', 'base', 'svg', 'math', 'noscript', 'template', 'audio', 'video', 'canvas', 'applet', 'frame', 'frameset', 'head', 'title'])

function resolveUrl(u, base) {
  const v = String(u || '').trim()
  if (!v) return ''
  try { return new URL(v, base || undefined).href } catch { return '' }
}

function cleanNovelHtml(html, { baseUrl = '', proxyUrl = (u) => u, headers = {} } = {}) {
  const $ = cheerio.load(`<body>${String(html ?? '')}</body>`)
  const all = $('body *').toArray().reverse() // children before parents so unwrapping is safe
  for (const el of all) {
    const name = el.name
    if (DROP.has(name)) { $(el).remove(); continue }
    if (!KEEP.has(name)) { $(el).replaceWith($(el).contents()); continue }
    const attrs = el.attribs || {}
    const lazy = name === 'img' ? attrs['data-src'] || attrs['data-lazy-src'] || attrs['data-original'] : ''
    for (const k of Object.keys(attrs)) {
      const keep = (name === 'img' && (k === 'src' || k === 'alt')) || ((name === 'td' || name === 'th') && (k === 'colspan' || k === 'rowspan'))
      if (!keep) $(el).removeAttr(k)
    }
    if (name === 'img') {
      // lazy-loading sites keep the real address in a data-* attribute; prefer it, then drop it with the rest
      const src = resolveUrl(lazy || $(el).attr('src'), baseUrl)
      if (!/^https?:\/\//i.test(src)) { $(el).remove(); continue }
      $(el).attr('src', proxyUrl(src, headers)).attr('loading', 'lazy')
    }
  }
  return $('body').html() || ''
}

module.exports = { cleanNovelHtml }
