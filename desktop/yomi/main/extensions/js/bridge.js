'use strict'
// Host-side helpers that Mangayomi exposes to JavaScript sources as global functions
// (cryptoHandler, encryptAESCryptoJS, decryptAESCryptoJS, decryptAESGCM, unpackJs, unpackJsAndCombine, parseDates).
// Behaviour follows Mangayomi: on failure the crypto helpers hand back their input instead of throwing.
const crypto = require('crypto')

// ---- AES -----------------------------------------------------------------------------------------------------------
function cryptoHandler(text, iv, secret, encrypt) {
  try {
    const key = Buffer.from(String(secret), 'utf8')
    const algo = { 16: 'aes-128-cbc', 24: 'aes-192-cbc', 32: 'aes-256-cbc' }[key.length]
    if (!algo) return text
    const ivBuf = Buffer.from(String(iv), 'utf8')
    if (encrypt) {
      const c = crypto.createCipheriv(algo, key, ivBuf)
      return Buffer.concat([c.update(Buffer.from(String(text), 'utf8')), c.final()]).toString('base64')
    }
    const d = crypto.createDecipheriv(algo, key, ivBuf)
    return Buffer.concat([d.update(Buffer.from(String(text), 'base64')), d.final()]).toString('utf8')
  } catch {
    return text
  }
}

// OpenSSL "Salted__" format used by CryptoJS: key+iv = EVP_BytesToKey(MD5, passphrase, salt).
function deriveKeyIv(pass, salt) {
  let out = Buffer.alloc(0)
  let prev = Buffer.alloc(0)
  while (out.length < 48) {
    prev = crypto.createHash('md5').update(Buffer.concat([prev, Buffer.from(pass, 'utf8'), salt])).digest()
    out = Buffer.concat([out, prev])
  }
  return { key: out.subarray(0, 32), iv: out.subarray(32, 48) }
}
function encryptAESCryptoJS(plain, pass) {
  const salt = crypto.randomBytes(8)
  const { key, iv } = deriveKeyIv(String(pass).trim(), salt)
  const c = crypto.createCipheriv('aes-256-cbc', key, iv)
  const enc = Buffer.concat([c.update(Buffer.from(String(plain).trim(), 'utf8')), c.final()])
  return Buffer.concat([Buffer.from('Salted__'), salt, enc]).toString('base64')
}
function decryptAESCryptoJS(encrypted, pass) {
  const raw = Buffer.from(String(encrypted).trim(), 'base64')
  const { key, iv } = deriveKeyIv(String(pass).trim(), raw.subarray(8, 16))
  const d = crypto.createDecipheriv('aes-256-cbc', key, iv)
  return Buffer.concat([d.update(raw.subarray(16)), d.final()]).toString('utf8')
}
function decryptAESGCM(encrypted, keyHex, ivHex, tagHex = '') {
  try {
    const key = Buffer.from(keyHex, 'hex')
    const iv = Buffer.from(ivHex, 'hex')
    let data = Buffer.from(String(encrypted), 'base64')
    let tag
    if (tagHex) tag = Buffer.from(tagHex, 'hex')
    else { tag = data.subarray(data.length - 16); data = data.subarray(0, data.length - 16) }
    const d = crypto.createDecipheriv({ 16: 'aes-128-gcm', 24: 'aes-192-gcm', 32: 'aes-256-gcm' }[key.length], key, iv)
    d.setAuthTag(tag)
    return Buffer.concat([d.update(data), d.final()]).toString('utf8')
  } catch {
    return encrypted
  }
}

// ---- Dean Edwards p,a,c,k,e,d unpacker -----------------------------------------------------------------------------
function unpackOne(src) {
  const m = /\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)/.exec(src)
  if (!m) return ''
  let payload = m[1].replace(/\\(.)/g, '$1')
  const radix = Number(m[2])
  let count = Number(m[3])
  const words = m[4].replace(/\\(.)/g, '$1').split('|')
  const enc = (n) => (n < radix ? '' : enc(Math.floor(n / radix))) + ((n %= radix) > 35 ? String.fromCharCode(n + 29) : n.toString(36))
  const dict = {}
  while (count--) dict[enc(count)] = words[count] || enc(count)
  payload = payload.replace(/\b\w+\b/g, (w) => dict[w] ?? w)
  return payload
}
const unpackJs = (code) => unpackOne(String(code))
const unpackJsAndCombine = (code) => {
  const parts = String(code).match(/eval\(function\(p,a,c,k,e,[dr]\)[\s\S]*?\.split\('\|'\)[^)]*\)\)/g) || []
  const out = parts.map(unpackOne).filter(Boolean)
  return out.length ? out.join('\n') : unpackOne(String(code))
}

// ---- dates ---------------------------------------------------------------------------------------------------------
const UNITS = [
  [/\b(sec|second|seconds|s|segundo|segundos|seconde|secondes|detik|saniye|saat)\b/i, 1000],
  [/\b(min|mins|minute|minutes|minuto|minutos|minuten|menit|dakika)\b/i, 60000],
  [/\b(hour|hours|hr|hrs|hora|horas|heure|heures|stunde|stunden|jam|saat)\b/i, 3600000],
  [/\b(day|days|dia|dias|jour|jours|tag|tage|hari|gün)\b/i, 86400000],
  [/\b(week|weeks|semana|semanas|semaine|semaines|woche|wochen|minggu|hafta)\b/i, 7 * 86400000],
  [/\b(month|months|mes|meses|mois|monat|monate|bulan|ay)\b/i, 30 * 86400000],
  [/\b(year|years|año|años|ano|anos|an|ans|jahr|jahre|tahun|yıl)\b/i, 365 * 86400000],
]
function relative(str, now) {
  const s = str.toLowerCase().trim()
  if (/^(today|hoy|hoje|aujourd'hui|heute|hari ini|bugün)$/.test(s)) return now
  if (/^(yesterday|ayer|ontem|hier|gestern|kemarin|dün)$/.test(s)) return now - 86400000
  if (/^(just now|now|ahora|agora|maintenant)$/.test(s)) return now
  const m = /(\d+)\s*([^\d\s]+(?:\s[^\d\s]+)?)/.exec(s)
  if (!m || !/\bago\b|\bhace\b|\bhá\b|il y a|\bvor\b|lalu|önce/.test(s)) return null
  for (const [re, ms] of UNITS) if (re.test(m[2])) return now - Number(m[1]) * ms
  return null
}

// Dart intl pattern (yyyy-MM-dd, MMM d, yyyy, HH:mm ...) -> parser
function parseWithPattern(str, pattern, locale) {
  const monthNames = (style) => Array.from({ length: 12 }, (_, i) => { try { return new Intl.DateTimeFormat(locale || 'en', { month: style, timeZone: 'UTC' }).format(new Date(Date.UTC(2020, i, 1))) } catch { return '' } })
  const longM = monthNames('long').map((x) => x.toLowerCase())
  const shortM = monthNames('short').map((x) => x.toLowerCase().replace(/\.$/, ''))
  const fields = []
  let re = ''
  const tokens = pattern.match(/'[^']*'|y+|M+|d+|H+|h+|m+|s+|a|S+|[^yMdHhmsaS']+/g) || []
  for (const t of tokens) {
    if (t[0] === "'") re += t.slice(1, -1).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    else if (/^y+$/.test(t)) { fields.push('y'); re += t.length === 2 ? '(\\d{2})' : '(\\d{4})' }
    else if (/^M+$/.test(t)) { fields.push(t.length >= 3 ? (t.length === 3 ? 'Ms' : 'Ml') : 'M'); re += t.length >= 3 ? '([^\\s\\d,./-]+\\.?)' : '(\\d{1,2})' }
    else if (/^d+$/.test(t)) { fields.push('d'); re += '(\\d{1,2})(?:st|nd|rd|th)?' }
    else if (/^[Hh]+$/.test(t)) { fields.push(t[0]); re += '(\\d{1,2})' }
    else if (/^m+$/.test(t)) { fields.push('m'); re += '(\\d{1,2})' }
    else if (/^s+$/.test(t)) { fields.push('s'); re += '(\\d{1,2})' }
    else if (t === 'a') { fields.push('a'); re += '([AaPp][Mm])' }
    else if (/^S+$/.test(t)) { fields.push('S'); re += '(\\d+)' }
    else re += t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+')
  }
  const m = new RegExp('^\\s*' + re, 'i').exec(str)
  if (!m) return null
  const v = { y: new Date().getFullYear(), M: 1, d: 1, H: 0, m: 0, s: 0 }
  let pm = null
  fields.forEach((f, i) => {
    const x = m[i + 1]
    if (f === 'y') v.y = x.length === 2 ? 2000 + Number(x) : Number(x)
    else if (f === 'M') v.M = Number(x)
    else if (f === 'Ms' || f === 'Ml') {
      const k = x.toLowerCase().replace(/\.$/, '')
      let idx = (f === 'Ml' ? longM : shortM).indexOf(k)
      if (idx < 0) idx = longM.findIndex((n) => n.startsWith(k.slice(0, 3)))
      if (idx < 0) throw new Error('month')
      v.M = idx + 1
    } else if (f === 'd') v.d = Number(x)
    else if (f === 'H') v.H = Number(x)
    else if (f === 'h') v.H = Number(x) % 12
    else if (f === 'm') v.m = Number(x)
    else if (f === 's') v.s = Number(x)
    else if (f === 'a') pm = /p/i.test(x)
  })
  if (pm) v.H += 12
  const t = new Date(v.y, v.M - 1, v.d, v.H, v.m, v.s).getTime()
  return Number.isFinite(t) ? t : null
}

function parseDates(list, format, locale) {
  const now = Date.now()
  const vals = (list || []).filter((x) => x != null && String(x).trim()).map((x) => String(x).trim())
  return vals.map((s) => {
    try {
      const rel = relative(s, now)
      if (rel != null) return String(rel)
      if (format) { const t = parseWithPattern(s, format, locale); if (t != null) return String(t) }
      const t = Date.parse(s)
      return String(Number.isFinite(t) ? t : now)
    } catch {
      return String(now)
    }
  })
}

module.exports = { cryptoHandler, encryptAESCryptoJS, decryptAESCryptoJS, decryptAESGCM, unpackJs, unpackJsAndCombine, parseDates }
