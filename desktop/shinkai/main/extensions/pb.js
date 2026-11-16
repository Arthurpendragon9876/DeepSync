'use strict'
// Minimal protobuf reader for the "index.pb" extension repo format (Keiyoushi and compatible repos).
// Field numbers follow the layout used by AnymeX's PbDecoder. Only what the index needs is decoded.

function readVarint(buf, pos) {
  let result = 0n
  let shift = 0n
  while (pos < buf.length) {
    const b = buf[pos++]
    result |= BigInt(b & 0x7f) << shift
    if (!(b & 0x80)) return [result, pos]
    shift += 7n
    if (shift > 70n) break
  }
  throw new Error('Bad varint')
}

function parseMessage(buf) {
  const map = new Map()
  let pos = 0
  const push = (k, v) => (map.has(k) ? map.get(k).push(v) : map.set(k, [v]))
  try {
    while (pos < buf.length) {
      let key
      ;[key, pos] = readVarint(buf, pos)
      const wire = Number(key & 7n)
      const field = Number(key >> 3n)
      if (wire === 0) {
        let v
        ;[v, pos] = readVarint(buf, pos)
        push(field, v)
      } else if (wire === 1) pos += 8
      else if (wire === 5) pos += 4
      else if (wire === 2) {
        let len
        ;[len, pos] = readVarint(buf, pos)
        const n = Number(len)
        if (pos + n > buf.length) break
        push(field, buf.subarray(pos, pos + n))
        pos += n
      } else break
    }
  } catch {}
  return map
}

const str = (list) => (list?.length && Buffer.isBuffer(list[0]) ? list[0].toString('utf8') : '')
const int = (list) => (list?.length && typeof list[0] === 'bigint' ? list[0] : 0n)

function parseExtensions(list) {
  return list.map((bytes) => {
    const m = parseMessage(bytes)
    const res = m.get(3)?.[0] ? parseMessage(m.get(3)[0]) : new Map()
    const sources = (m.get(8) || []).map((sb) => {
      const s = parseMessage(sb)
      return { id: int(s.get(1)).toString(), name: str(s.get(2)), lang: str(s.get(3)), baseUrl: str(s.get(4)) }
    })
    const apkUrl = str(res.get(1))
    return {
      name: str(m.get(1)),
      pkg: str(m.get(2)),
      apk: apkUrl.includes('/') ? apkUrl.split('/').pop() : apkUrl,
      apkUrl,
      iconUrl: str(res.get(2)),
      lang: sources[0]?.lang || 'en',
      code: Number(int(m.get(5))),
      version: str(m.get(6)),
      nsfw: Number(int(m.get(7))) >= 2 ? 1 : 0,
      sources,
    }
  })
}

function decodeIndex(buf) {
  const root = parseMessage(buf)
  if (root.get(101)?.[0]) return parseExtensions(parseMessage(root.get(101)[0]).get(1) || [])
  const list = root.get(1)
  if (list?.length && Buffer.isBuffer(list[0]) && parseMessage(list[0]).has(2)) return parseExtensions(list)
  return []
}

module.exports = { decodeIndex, parseMessage }
