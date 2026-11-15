// Small localStorage-backed UI preferences (no main-process round trip).
const get = (k, d) => { try { return localStorage.getItem(k) ?? d } catch { return d } }
const set = (k, v) => { try { localStorage.setItem(k, v) } catch {} }
export const animePageStyle = () => get('ds.animePage', 'youtube') // 'youtube' | 'classic'
export const setAnimePageStyle = (v) => set('ds.animePage', v)
export const chapterOrder = () => get('ds.chapterOrder', 'desc')
export const setChapterOrder = (v) => set('ds.chapterOrder', v)
const nk = (mode, id) => `ds.notes.${mode}.${id}`
export const getNotes = (mode, id) => { try { return JSON.parse(get(nk(mode, id), '[]')) } catch { return [] } }
export const saveNotes = (mode, id, list) => set(nk(mode, id), JSON.stringify(list))

// Scanlation groups (MangaFire "official" / "unofficial" and so on). The preset decides which copy of a duplicated chapter is
// kept; per-series choices (groups switched off, duplicates shown) are remembered so they survive leaving the page.
export const groupPreset = () => (['official', 'unofficial'].includes(get('ds.groupPreset', 'most')) ? get('ds.groupPreset', 'most') : 'most') // 'most' (group with the most chapters) | 'official' | 'unofficial'
export const skipDuplicates = () => get('ds.skipDupes', '1') !== '0'
export const setSkipDuplicates = (v) => set('ds.skipDupes', v ? '1' : '0')
export const setGroupPreset = (v) => set('ds.groupPreset', v)
export const groupPrefs = (k) => { try { return JSON.parse(get('ds.groups.' + k, '{}')) || {} } catch { return {} } }
export const hasGroupPrefs = (k) => get('ds.groups.' + k, null) !== null
export const setGroupPrefs = (k, v) => { try { v ? localStorage.setItem('ds.groups.' + k, JSON.stringify(v)) : localStorage.removeItem('ds.groups.' + k) } catch {} }
export const clearAllGroupPrefs = () => { try { Object.keys(localStorage).filter((k) => k.startsWith('ds.groups.')).forEach((k) => localStorage.removeItem(k)) } catch {} }
