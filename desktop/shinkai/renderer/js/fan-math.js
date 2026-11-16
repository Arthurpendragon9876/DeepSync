// Pure geometry of the mode fan (no DOM), shared by fab.js and the tests. Ported from ModeFan.kt.
export const MODES = ['anime', 'manga', 'novel']
export const PULL_PX = 14, NEAR_PX = 54, DEAD_PX = 24
export const ANGLE = { anime: 75, manga: 45, novel: 15 } // degrees from "straight left" (0) to "straight up" (90)

// Which mode is under the pointer, [dx]/[dy] away from the button (positive = left / up); null near the button.
// [dead] is the radius around the origin where nothing is picked.
export function pickMode(dx, dy, radius, dead = DEAD_PX) {
  if (Math.hypot(dx, dy) < dead) return null
  const dist = (m) => Math.hypot(dx - radius * Math.cos((ANGLE[m] * Math.PI) / 180), dy - radius * Math.sin((ANGLE[m] * Math.PI) / 180))
  const nearest = MODES.reduce((a, b) => (dist(a) <= dist(b) ? a : b)) // magnetic: close to a node beats the angle zones
  if (dist(nearest) < NEAR_PX) return nearest
  const angle = (Math.atan2(dy, dx) * 180) / Math.PI
  return angle >= 60 ? 'anime' : angle >= 30 ? 'manga' : 'novel'
}
