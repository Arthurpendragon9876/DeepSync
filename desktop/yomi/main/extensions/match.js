'use strict'
// Title matching, ported from DeepSync Android (scrobbling/common/domain/TitleMatcher.kt and metadata/MetadataMatcher.kt):
// "Shingeki no Kyojin", "Attack on Titan" and the native title are the same series, so every name on one side is compared
// with every name on the other after stripping case, accents, punctuation and bracketed notes.

const MIN_NAME_SCORE = 0.85

function normalize(title) {
  let t = String(title || '').normalize('NFKD').replace(/\p{M}+/gu, '')
  t = t.replace(/[(\[{][^)\]}]*[)\]}]/g, ' ').toLowerCase()
  t = t.replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
  return t.replace(/^the /, '').trim()
}

function levenshtein(a, b) {
  if (a === b) return 0
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i)
  let cur = new Array(b.length + 1)
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
    ;[prev, cur] = [cur, prev]
  }
  return prev[b.length]
}

/** 0..1. 1 means identical once normalised (spaces ignored). */
function score(a, b) {
  const x = normalize(a), y = normalize(b)
  if (!x || !y) return 0
  if (x === y || x.replace(/ /g, '') === y.replace(/ /g, '')) return 1
  const tx = new Set(x.split(' ')), ty = new Set(y.split(' '))
  let overlap = 0
  for (const t of tx) if (ty.has(t)) overlap++
  const jaccard = overlap / new Set([...tx, ...ty]).size
  // One title being the other plus a subtitle ("Frieren" vs "Frieren Beyond Journey's End")
  const contained = x.length >= 5 && y.length >= 5 && (x.includes(y) || y.includes(x)) ? (Math.min(x.length, y.length) / Math.max(x.length, y.length)) * 0.5 + 0.4 : 0
  const edit = 1 - levenshtein(x, y) / Math.max(x.length, y.length)
  return Math.min(0.99, Math.max(0, jaccard * 0.9, contained, edit * 0.95))
}

function bestScore(left, right) {
  let best = 0
  for (const a of left) for (const b of right) {
    const s = score(a, b)
    if (s > best) best = s
    if (best >= 1) return 1
  }
  return best
}

/** Every name AniList knows for a title, best first, without duplicates. */
function namesOf(media) {
  const t = media?.title || {}
  const out = []
  for (const n of [t.english, t.romaji, t.userPreferred, t.native, ...(media?.synonyms || [])]) if (n && !out.some((o) => normalize(o) === normalize(n))) out.push(n)
  return out
}

/** Ranks a source's search results against the AniList title; only results at or above MIN_NAME_SCORE are kept. */
function rank(media, results) {
  const names = namesOf(media)
  if (!names.length) return []
  return results
    .map((item) => ({ item, score: bestScore(names, [item.title]) }))
    .filter((r) => r.score >= MIN_NAME_SCORE)
    .sort((a, b) => b.score - a.score)
}

module.exports = { MIN_NAME_SCORE, normalize, score, bestScore, namesOf, rank }
