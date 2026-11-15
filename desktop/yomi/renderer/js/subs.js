// Subtitle language matching for the built-in player. Pure functions (no DOM) so they can be unit-tested.
// Sources label tracks however they like ("English", "en", "eng - Full", "Español (Latinoamérica)", "English (Signs & Songs)").

export const SUB_LANGS = [
  ['en', 'English'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian'], ['ar', 'Arabic'],
  ['ru', 'Russian'], ['id', 'Indonesian'], ['tr', 'Turkish'], ['vi', 'Vietnamese'], ['th', 'Thai'], ['pl', 'Polish'], ['zh', 'Chinese'], ['ko', 'Korean'], ['ja', 'Japanese'],
]

const NAMES = {
  en: /\b(en|eng|english)\b/i,
  es: /\b(es|spa|spanish|espa[nñ]ol|castellano|latino|latam)\b/i,
  pt: /\b(pt|por|portuguese|portugu[eê]s|br)\b/i,
  fr: /\b(fr|fre|fra|french|fran[cç]ais)\b/i,
  de: /\b(de|ger|deu|german|deutsch)\b/i,
  it: /\b(it|ita|italian|italiano)\b/i,
  ar: /\b(ar|ara|arabic)\b|العربية/i,
  ru: /\b(ru|rus|russian)\b|русский/i,
  id: /\b(id|ind|indonesian|bahasa)\b/i,
  tr: /\b(tr|tur|turkish|t[uü]rk[cç]e)\b/i,
  vi: /\b(vi|vie|vietnamese|ti[eê]́?ng vi[eê]̣?t)\b/i,
  th: /\b(th|tha|thai)\b|ไทย/i,
  pl: /\b(pl|pol|polish|polski)\b/i,
  zh: /\b(zh|chi|zho|chinese|mandarin|cantonese)\b|中文|简体|繁體|繁体/i,
  ko: /\b(ko|kor|korean)\b|한국어/i,
  ja: /\b(ja|jpn|jap|japanese)\b|日本語/i,
}

/** "English (Signs & Songs)" -> "en"; unknown labels -> "". */
export const langOf = (text) => Object.keys(NAMES).find((c) => NAMES[c].test(String(text || ''))) || ''

const hasLang = (code, t) => (NAMES[code] || new RegExp(`\\b${String(code).replace(/[^\w]/g, '')}\\b`, 'i')).test(t)

/**
 * Index of the track to show for a language code, or -1 when none is in that language.
 * Prefers the full dialogue track over "Signs & Songs" / forced / commentary tracks, and plain subtitles over SDH.
 * @param {{label?: string, lang?: string}[]} tracks
 */
export function pickSubtitle(tracks, code) {
  if (!code || code === 'off') return -1
  let best = -1, bestScore = -Infinity
  tracks.forEach((t, i) => {
    const text = `${t.label || ''} ${t.lang || ''}`
    if (!hasLang(code, text)) return
    let s = 100
    if (/sign|song|forced|commentary|karaoke|lyrics/i.test(text)) s -= 40
    if (/\b(sdh|cc|hearing|caption)/i.test(text)) s -= 5
    if (/full|dialog|complete/i.test(text)) s += 10
    if (s > bestScore) { best = i; bestScore = s }
  })
  return best
}
