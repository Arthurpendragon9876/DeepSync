// Favourites (with categories) and reading / watching history.
import { api, h, icon, menu, toast, titleOf, coverOf, S } from './core.js'

export const favKey = (mode, id) => `${mode}:${id}`
export const favItem = (media, mode) => ({ key: favKey(mode, media.id), mode, id: media.id, title: titleOf(media), cover: coverOf(media) })

// Heart menu: tick the categories a title belongs to. Ticking none removes it from Favourites.
export async function favMenu(anchor, media, mode, onChange) {
  const d = await api('fav:list')
  const item = favItem(media, mode)
  let cats = new Set(d.items.find((x) => x.key === item.key)?.cats || [])
  const items = () => d.categories.map((c) => ({ label: c.name, icon: cats.has(c.id) ? 'check' : 'plus', keep: true, run: async () => {
    cats.has(c.id) ? cats.delete(c.id) : cats.add(c.id)
    await api('fav:set', { item, cats: [...cats] }); onChange?.(cats.size > 0)
    toast(cats.size ? 'Saved to Favourites' : 'Removed from Favourites')
  } }))
  if (d.categories.length === 1) { // one category: the heart just toggles it
    cats = cats.size ? new Set() : new Set([d.categories[0].id])
    await api('fav:set', { item, cats: [...cats] }); onChange?.(cats.size > 0)
    return toast(cats.size ? 'Added to Favourites' : 'Removed from Favourites')
  }
  menu(anchor, items())
}
export const isFav = async (media, mode) => (await api('fav:list').catch(() => ({ items: [] }))).items.some((x) => x.key === favKey(mode, media.id))

// One history row per title (the latest chapter / episode), written whenever something is read or watched. Incognito skips it.
export function recordHistory({ media, mode, number, label }) {
  if (!media || S.settings.incognito) return
  api('history:add', { entry: { key: favKey(mode, media.id), mode, id: media.id, title: titleOf(media), cover: coverOf(media), number: number ?? null, label: label || '' } }).catch(() => {})
}
