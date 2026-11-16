'use strict'
// Synchronous HTML DOM used by Mangayomi-style JavaScript sources (Document / Element).
//
// Mangayomi parses HTML with Dart's `html` package and queries it with Jsoup-flavoured selectors. Behaviour that
// extensions rely on, and that this file keeps:
//   - every getter returns a string (never null): a missing element answers "" / [] instead of throwing,
//   - selectFirst() always returns an Element object, even when nothing matched (it is just an empty one),
//   - text is the raw text content (not whitespace-normalised like Jsoup),
//   - the extra selector pseudos :containsOwn, :matches, :eq(n), :lt(n), :gt(n) ... exist.
const cheerio = require('cheerio')
const { selectAll, selectOne } = require('css-select')
const { textContent, getOuterHTML, getInnerHTML, findAll, findOne } = require('domutils')
const { isTag, isText } = require('domhandler')

const SER = { encodeEntities: 'utf8' }
const ownText = (el) => el.children.filter(isText).map((t) => t.data).join('')
const wholeText = (el) => textContent(el)
const tagSiblings = (el) => (el.parent ? el.parent.children : []).filter(isTag)
const nth = (el, v) => { const i = tagSiblings(el).indexOf(el); return i < 0 ? NaN : i }
const safeRe = (src, flags) => { try { return new RegExp(src, flags) } catch { return null } }
const lower = (s) => String(s ?? '').toLowerCase()

// Jsoup / Dart-html pseudo selectors that css-select does not know. `contains` is overridden to be case-insensitive,
// exactly like Mangayomi's implementation.
const PSEUDOS = {
  contains: (el, v) => lower(textContent(el)).includes(lower(v)),
  containsOwn: (el, v) => lower(ownText(el)).includes(lower(v)),
  containsData: (el, v) => (el.name === 'script' || el.name === 'style') && lower(textContent(el)).includes(lower(v)),
  containsWholeText: (el, v) => wholeText(el).includes(String(v ?? '')),
  containsWholeOwnText: (el, v) => ownText(el).includes(String(v ?? '')),
  matches: (el, v) => !!safeRe(v, 'i')?.test(textContent(el)),
  matchesOwn: (el, v) => !!safeRe(v, 'i')?.test(ownText(el)),
  matchesWholeText: (el, v) => !!safeRe(v)?.test(wholeText(el)),
  matchesWholeOwnText: (el, v) => !!safeRe(v)?.test(ownText(el)),
  eq: (el, v) => nth(el) === parseInt(v, 10),
  lt: (el, v) => nth(el) < parseInt(v, 10),
  gt: (el, v) => nth(el) > parseInt(v, 10),
}
// css-what lower-cases pseudo names, so register lower-case aliases too
for (const k of Object.keys(PSEUDOS)) PSEUDOS[k.toLowerCase()] = PSEUDOS[k]
const OPTS = { pseudos: PSEUDOS }

const run = (fn) => { try { return fn() } catch { return null } }

// "href=\"...\"" style matchers Mangayomi runs over outerHtml (getHref / getSrc / getImg / getDataSrc).
const attrFromOuter = (el, name) => { const m = new RegExp(name + '="([^"]+)"').exec(getOuterHTML(el, SER)); return m ? m[1] : '' }

class Elem {
  constructor(node) { this.node = node || null }
  get text() { return this.node ? textContent(this.node) : '' }
  get outerHtml() { return this.node ? getOuterHTML(this.node, SER) : '' }
  get innerHtml() { return this.node ? getInnerHTML(this.node, SER) : '' }
  get className() { return this.node?.attribs?.class || '' }
  get localName() { return this.node?.name || '' }
  get namespaceUri() { return this.node ? 'http://www.w3.org/1999/xhtml' : '' }
  get getSrc() { return this.node ? attrFromOuter(this.node, 'src') : '' }
  get getImg() { return this.node ? attrFromOuter(this.node, 'img') : '' }
  get getHref() { return this.node ? attrFromOuter(this.node, 'href') : '' }
  get getDataSrc() { return this.node ? attrFromOuter(this.node, 'data-src') : '' }
  attr(name) { return this.node?.attribs?.[name] ?? '' }
  hasAttr(name) { return !!this.node?.attribs && Object.prototype.hasOwnProperty.call(this.node.attribs, name) }
  select(sel) { return this.node ? (run(() => selectAll(sel, this.node, OPTS)) || []).map((n) => new Elem(n)) : [] }
  selectFirst(sel) { return new Elem(this.node ? run(() => selectOne(sel, this.node, OPTS)) : null) }
  get children() { return this.node?.children ? this.node.children.filter(isTag).map((n) => new Elem(n)) : [] }
  getElementsByTagName(name) {
    if (!this.node) return []
    const want = lower(name)
    return findAll((n) => isTag(n) && (want === '*' || n.name === want), this.node.children || []).map((n) => new Elem(n))
  }
  getElementsByClassName(name) {
    if (!this.node) return []
    return findAll((n) => isTag(n) && (n.attribs.class || '').split(/\s+/).includes(name), this.node.children || []).map((n) => new Elem(n))
  }
  get nextElementSibling() { return new Elem(this.node ? tagSiblings(this.node)[tagSiblings(this.node).indexOf(this.node) + 1] : null) }
  get previousElementSibling() { return new Elem(this.node ? tagSiblings(this.node)[tagSiblings(this.node).indexOf(this.node) - 1] : null) }
  xpath(expr) { return this.node ? xpathAll(this.node, expr) : [] }
  xpathFirst(expr) { return this.node ? xpathAll(this.node, expr)[0] ?? '' : '' }
}

class Doc {
  constructor(html) {
    this.$ = cheerio.load(String(html ?? ''))
    this.root = this.$.root()[0]
    this.html = Array.from(this.root.children).find((n) => isTag(n) && n.name === 'html') || null
  }
  child(name) { return this.html ? new Elem(findOne((n) => isTag(n) && n.name === name, this.html.children, false)) : new Elem(null) }
  get body() { return this.child('body') }
  get head() { return this.child('head') }
  get documentElement() { return new Elem(this.html) }
  get parent() { return new Elem(null) }
  get text() { return this.html ? textContent(this.html) : '' }
  get outerHtml() { return this.html ? getOuterHTML(this.html, SER) : '' }
  get children() { return this.html ? [new Elem(this.html)] : [] }
  select(sel) { return this.html ? (run(() => selectAll(sel, this.html, OPTS)) || []).map((n) => new Elem(n)) : [] }
  selectFirst(sel) { return new Elem(this.html ? run(() => selectOne(sel, this.html, OPTS)) : null) }
  getElementsByTagName(name) { return new Elem(this.html).getElementsByTagName(name) }
  getElementsByClassName(name) { return new Elem(this.html).getElementsByClassName(name) }
  getElementById(id) { return new Elem(this.html ? findOne((n) => isTag(n) && n.attribs.id === id, this.html.children, true) : null) }
  attr(name) { return this.html?.attribs?.[name] ?? '' }
  hasAttr(name) { return !!this.html?.attribs && Object.prototype.hasOwnProperty.call(this.html.attribs, name) }
  xpath(expr) { return this.html ? xpathAll(this.html, expr) : [] }
  xpathFirst(expr) { return this.html ? xpathAll(this.html, expr)[0] ?? '' : '' }
}

// ---- XPath ---------------------------------------------------------------------------------------------------------
// The parsed (already HTML5-normalised) tree is re-serialised as well-formed XML and evaluated with the `xpath`
// package. Results: attribute / text nodes give their value, elements give their text.
let xp, XMLDOM
function xpathAll(node, expr) {
  try {
    xp ||= require('xpath')
    XMLDOM ||= require('@xmldom/xmldom')
    const xml = getOuterHTML(node, { xmlMode: true, encodeEntities: 'utf8' })
    const doc = new XMLDOM.DOMParser({ errorHandler: () => {} }).parseFromString(xml, 'text/xml')
    const res = xp.select(String(expr), doc)
    const list = Array.isArray(res) ? res : [res]
    return list.map((n) => (n && typeof n === 'object' ? String(n.value ?? n.data ?? n.textContent ?? '') : String(n))).map((s) => s.trim())
  } catch {
    return []
  }
}

module.exports = { Doc, Elem, PSEUDOS }
