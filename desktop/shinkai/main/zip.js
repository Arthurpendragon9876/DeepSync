'use strict'
// Minimal random-access ZIP reader (stored + deflate). Reads only what it needs,
// so a 500 MB .cbz is never loaded fully into memory.
const fs = require('fs')
const zlib = require('zlib')

class Zip {
  constructor(file) {
    this.file = file
    this.entries = new Map()
    this.fd = null
  }

  static async open(file) {
    const z = new Zip(file)
    z.fd = await fs.promises.open(file, 'r')
    try {
      await z._index()
    } catch (e) {
      await z.close()
      throw e
    }
    return z
  }

  async _readAt(pos, len) {
    const buf = Buffer.alloc(len)
    const { bytesRead } = await this.fd.read(buf, 0, len, pos)
    return bytesRead === len ? buf : buf.subarray(0, bytesRead)
  }

  async _index() {
    const { size } = await this.fd.stat()
    const tailLen = Math.min(size, 66000)
    const tail = await this._readAt(size - tailLen, tailLen)
    let eocd = -1
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) {
        eocd = i
        break
      }
    }
    if (eocd < 0) throw new Error('Not a zip archive: ' + this.file)
    const count = tail.readUInt16LE(eocd + 10)
    const cdSize = tail.readUInt32LE(eocd + 12)
    const cdOff = tail.readUInt32LE(eocd + 16)
    if (count === 0xffff || cdOff === 0xffffffff) throw new Error('ZIP64 archives are not supported')
    const cd = await this._readAt(cdOff, cdSize)
    let p = 0
    while (p + 46 <= cd.length && cd.readUInt32LE(p) === 0x02014b50) {
      const flags = cd.readUInt16LE(p + 8)
      const method = cd.readUInt16LE(p + 10)
      const compSize = cd.readUInt32LE(p + 20)
      const size = cd.readUInt32LE(p + 24)
      const nameLen = cd.readUInt16LE(p + 28)
      const extraLen = cd.readUInt16LE(p + 30)
      const commentLen = cd.readUInt16LE(p + 32)
      const offset = cd.readUInt32LE(p + 42)
      const raw = cd.subarray(p + 46, p + 46 + nameLen)
      const name = (flags & 0x800 ? raw.toString('utf8') : raw.toString('utf8')).replace(/\\/g, '/')
      if (!name.endsWith('/')) this.entries.set(name, { method, compSize, size, offset })
      p += 46 + nameLen + extraLen + commentLen
    }
  }

  list() {
    return [...this.entries.keys()]
  }
  has(name) {
    return this.entries.has(name)
  }

  async read(name) {
    const e = this.entries.get(name)
    if (!e) throw new Error('No such entry: ' + name)
    const head = await this._readAt(e.offset, 30)
    if (head.readUInt32LE(0) !== 0x04034b50) throw new Error('Corrupt zip entry: ' + name)
    const start = e.offset + 30 + head.readUInt16LE(26) + head.readUInt16LE(28)
    const data = await this._readAt(start, e.compSize)
    if (e.method === 0) return data
    if (e.method === 8) return zlib.inflateRawSync(data)
    throw new Error('Unsupported compression method ' + e.method)
  }

  async readText(name) {
    return (await this.read(name)).toString('utf8').replace(/^\uFEFF/, '')
  }

  async close() {
    await this.fd?.close().catch(() => {})
    this.fd = null
  }
}

// Small LRU of open archives so each page request doesn't re-parse the directory.
const pool = new Map()
async function openCached(file) {
  const hit = pool.get(file)
  if (hit) {
    pool.delete(file)
    pool.set(file, hit)
    return hit
  }
  const z = await Zip.open(file)
  pool.set(file, z)
  while (pool.size > 6) {
    const [k, old] = pool.entries().next().value
    pool.delete(k)
    old.close()
  }
  return z
}

const IMAGE_RE = /\.(jpe?g|png|webp|gif|avif|bmp)$/i
// Natural sort: page2 before page10.
const natural = (a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })

module.exports = { Zip, openCached, IMAGE_RE, natural }
