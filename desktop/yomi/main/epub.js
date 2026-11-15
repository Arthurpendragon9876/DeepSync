'use strict'
// EPUB support without dependencies: regex-level XML parsing is enough for OPF/NCX/nav.
const path = require('path').posix
const { openCached } = require('./zip')

const decode = (s) =>
  String(s || '')
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/\s+/g, ' ')
    .trim()

const attr = (tag, name) => {
  const m = new RegExp('\\b' + name + '\\s*=\\s*("([^"]*)"|\'([^\']*)\')', 'i').exec(tag)
  return m ? (m[2] ?? m[3]) : undefined
}

async function openEpub(file) {
  const zip = await openCached(file)
  const container = await zip.readText('META-INF/container.xml')
  const opfPath = attr(container.match(/<rootfile\b[^>]*>/i)?.[0] || '', 'full-path')
  if (!opfPath) throw new Error('EPUB has no rootfile')
  const opf = await zip.readText(opfPath)
  const base = path.dirname(opfPath)
  const resolve = (href) => path.normalize(path.join(base, decodeURIComponent(href.split('#')[0])))

  const meta = (name) => decode(new RegExp('<dc:' + name + '\\b[^>]*>([\\s\\S]*?)</dc:' + name + '>', 'i').exec(opf)?.[1])
  const manifest = new Map()
  for (const m of opf.matchAll(/<item\b[^>]*>/gi)) {
    const id = attr(m[0], 'id')
    if (id) manifest.set(id, { href: attr(m[0], 'href'), type: attr(m[0], 'media-type'), props: attr(m[0], 'properties') || '' })
  }
  const spine = [...opf.matchAll(/<itemref\b[^>]*>/gi)]
    .map((m) => manifest.get(attr(m[0], 'idref')))
    .filter((it) => it && it.href && /html/.test(it.type || ''))
    .map((it) => resolve(it.href))

  // Table of contents: EPUB3 nav first, NCX fallback.
  const labels = new Map()
  const nav = [...manifest.values()].find((i) => i.props.split(' ').includes('nav'))
  const ncx = [...manifest.values()].find((i) => i.type === 'application/x-dtbncx+xml')
  try {
    if (nav) {
      const navPath = resolve(nav.href)
      const html = await zip.readText(navPath)
      for (const m of html.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/gi)) {
        const href = attr(m[1], 'href')
        if (href) labels.set(path.normalize(path.join(path.dirname(navPath), decodeURIComponent(href.split('#')[0]))), decode(m[2]))
      }
    } else if (ncx) {
      const ncxPath = resolve(ncx.href)
      const xml = await zip.readText(ncxPath)
      for (const m of xml.matchAll(/<navPoint\b[\s\S]*?<text>([\s\S]*?)<\/text>[\s\S]*?<content\b([^>]*)>/gi)) {
        const href = attr(m[2], 'src')
        if (href) labels.set(path.normalize(path.join(path.dirname(ncxPath), decodeURIComponent(href.split('#')[0]))), decode(m[1]))
      }
    }
  } catch {}

  const chapters = spine.map((href, i) => ({ index: i, href, title: labels.get(href) || `Section ${i + 1}` }))
  const coverId = attr(opf.match(/<meta\b[^>]*name\s*=\s*["']cover["'][^>]*>/i)?.[0] || '', 'content')
  const coverItem = [...manifest.entries()].find(([id, it]) => it.props.split(' ').includes('cover-image') || id === coverId)?.[1]
  return {
    title: meta('title') || path.basename(file).replace(/\.epub$/i, ''),
    author: meta('creator'),
    language: meta('language'),
    cover: coverItem ? resolve(coverItem.href) : null,
    chapters,
  }
}

// Chapter body with scripts/handlers removed and resources routed through zipUrl().
async function chapterHtml(file, href, zipUrl) {
  const zip = await openCached(file)
  let html = await zip.readText(href)
  html = html.match(/<body\b[^>]*>([\s\S]*)<\/body>/i)?.[1] ?? html
  html = html
    .replace(/<script\b[\s\S]*?<\/script>/gi, '')
    .replace(/<style\b[\s\S]*?<\/style>/gi, '')
    .replace(/<link\b[^>]*>/gi, '')
    .replace(/<(iframe|object|embed|form)\b[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<\/?(iframe|object|embed|form|base|meta)\b[^>]*>/gi, '')
    .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*')/gi, '')
    .replace(/\s(href|src)\s*=\s*(["'])\s*javascript:[^"']*\2/gi, '')
    .replace(/\sstyle\s*=\s*("[^"]*"|'[^']*')/gi, '')
  const dir = path.dirname(href)
  html = html.replace(/\b(src|xlink:href)\s*=\s*(["'])([^"']+)\2/gi, (all, a, q, v) => {
    if (/^(https?:|data:)/i.test(v)) return `${a}=${q}${v}${q}`
    const entry = path.normalize(path.join(dir, decodeURIComponent(v.split('#')[0])))
    return `${a}=${q}${zipUrl(file, entry)}${q}`
  })
  return html
}

module.exports = { openEpub, chapterHtml, decode }
