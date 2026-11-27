# Backups across Android and desktop

The two apps use **different backup formats** for now. This page says what exists today and what can be shared.

| App | Format | Contents |
| --- | --- | --- |
| Android (Yugen) | `*.bk.zip`: one JSON entry per section (`index`, `history`, `categories`, `favourites`, `bookmarks`, `settings`, `scrobbling`, ...) | Kotatsu-style database. A title is identified by **source + URL**. |
| Shinkai (Linux) and Yomi (Windows) | one `shinkai-backup.json` | settings, progress, library links, favourites, history, stats. A title is identified by its **AniList id**. Tracker tokens are never exported. |

## Android -> Shinkai (available)

Shinkai can read an Android backup: **Settings > Data & backup > Import Android backup**, or on the command line:

```bash
node desktop/shinkai/tools/android-backup-to-shinkai.js MyBackup.bk.zip   # writes MyBackup.shinkai.json
```

What comes across: favourites, favourite categories (as `android-<id>` categories) and history, **for titles that are tracked with AniList in the Android app**.
That link (the `scrobbling` section) is the only thing that connects an Android title to an AniList id. Titles without it are skipped and listed
(in the app: "N skipped"; in the CLI: the full list). Fix: track them with AniList in the Android app, make a new backup, import again.
Nothing is guessed from titles. Import merges into what you already have and never touches tracker tokens.

Progress itself needs no backup: both apps push chapter/episode progress to AniList, so signing into the same AniList account on both keeps progress in step.

## Kototoro and Usagi

Kototoro and Usagi are Kotatsu forks, so their `*.bk.zip` has the same layout as Yugen's. What differs is how a title is identified, so the app converts instead of copying.

**On Android** (Settings > Backup & restore > Other apps):

| Button | What it does |
| --- | --- |
| Restore from Kototoro/Usagi | Converts the file, then restores it with the normal restore service. Categories are matched by title, never overwritten. |
| Export for Kototoro | Favourites, categories, history, bookmarks and reading stats of titles from Mihon sources. Written as a merge-style backup, so Kototoro never treats it as a snapshot that replaces its library. |
| Export for Usagi | The same, plus tracking, for titles whose Mihon source maps to a Kotatsu parser (the table used by the Kotatsu migration, reversed). |

Limits, by design:

- Only titles that the other app can open are exported: `MIHON_` sources for Kototoro, mapped sources for Usagi. The rest are counted and reported, not written as dead entries.
- Settings, sources and extension data are never exchanged. Their keys belong to the other app.
- Tracker ids are renumbered (Kototoro inserts Bangumi and MangaUpdates, which shifts Simkl and MangaBaka). Trackers the other side lacks are dropped.
- Chapter pointers are recomputed from the chapter url where the backup carries it. Kototoro backups carry no chapter list, so imported history resumes from its saved percentage.
- Usagi ids follow Kotatsu's published uid algorithm. Only the source and url identify a title; a wrong id could at worst duplicate a title that already exists there.

**On desktop**, *Import Android backup* now also reads Kototoro and Usagi files (same AniList-link rule as above). Anime and novel entries from Kototoro land in their own mode. Desktop **export** to these apps is not possible: a desktop favourite has an AniList id but no source and url, and both apps need those to open a title.

## Shinkai/Yomi -> Android (not possible yet)

An Android favourite needs a **source and a URL** (for example a MangaDex or extension entry). Shinkai only has AniList ids, so there is nothing valid to write into the Android database. Yomi is built from the same code as Shinkai, but its Android-backup importer has not been added there yet.
Yomi (Windows) now has Aniyomi/Mihon extension support, so titles there can carry a source and URL, and a two-way format becomes realistic. It is not built yet.
Until then, use AniList as the shared account.

## Later: one shared format

When both sides can express source + URL, add a `deepsync-backup` v1 file (JSON, with `format: "deepsync-backup"`, `version`, and per-title `anilistId`, `source`, `url`, `categories`, `history`) that all apps import and export,
and keep the converters above for old files. Not started; this is only the plan.
