#!/usr/bin/env node
'use strict'
// Usage: node tools/android-backup-to-shinkai.js <backup.bk.zip> [out.json]
// Writes a Shinkai backup JSON (import it in Settings > Data & backup > Import backup) and prints what could not be matched.
const fs = require('fs')
const path = require('path')
const { convertFile } = require('../main/android-backup')

;(async () => {
  const [input, out] = process.argv.slice(2)
  if (!input) { console.error('Usage: android-backup-to-shinkai <backup.bk.zip> [out.json]'); process.exit(2) }
  const { favourites, history, report } = await convertFile(input)
  const target = out || input.replace(/(\.bk)?\.zip$/i, '') + '.shinkai.json'
  fs.writeFileSync(target, JSON.stringify({ favourites, history, exportedAt: new Date().toISOString(), source: 'android' }, null, 2))
  console.log(`Wrote ${path.resolve(target)}`)
  console.log(`Favourites ${report.favourites}/${report.totals.favourites}, history ${report.history}/${report.totals.history}, categories ${report.categories}`)
  if (report.unmatched.length) {
    console.log(`\n${report.unmatched.length} title(s) have no AniList link in the Android app, so they were skipped:`)
    for (const u of report.unmatched.slice(0, 40)) console.log(`  - ${u.title} (${u.source}, ${u.where})`)
    if (report.unmatched.length > 40) console.log(`  ... and ${report.unmatched.length - 40} more`)
    console.log('Track them with AniList in the Android app, make a new backup and convert again.')
  }
})().catch((e) => { console.error('Error: ' + e.message); process.exit(1) })
