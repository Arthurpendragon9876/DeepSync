# Backups across Android and desktop

The two apps use **different backup formats** for now. This page says what exists today and what can be shared.

| App | Format | Contents |
| --- | --- | --- |
| Android (Yugen) | `*.bk.zip`: one JSON entry per section (`index`, `history`, `categories`, `favourites`, `bookmarks`, `settings`, `scrobbling`, ...) | Kotatsu-style database. A title is identified by **source + URL**. |
| Shinkai (Linux) | one `shinkai-backup.json` | settings, progress, library links, favourites, history, stats. A title is identified by its **AniList id**. Tracker tokens are never exported. |

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

## Shinkai -> Android (not possible yet)

An Android favourite needs a **source and a URL** (for example a MangaDex or extension entry). Shinkai only has AniList ids, so there is nothing valid to write into the Android database.
This changes when the Windows build (Yomi) ships Aniyomi/Mihon extension support: titles will then carry a source and URL, and a two-way format becomes realistic.
Until then, use AniList as the shared account.

## Later: one shared format

When both sides can express source + URL, add a `deepsync-backup` v1 file (JSON, with `format: "deepsync-backup"`, `version`, and per-title `anilistId`, `source`, `url`, `categories`, `history`) that all apps import and export,
and keep the converters above for old files. Not started; this is only the plan.
