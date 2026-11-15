# Changelog

All notable changes to **Yugen** (the Android app of the **DeepSync** project) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

> The release workflow publishes the `## [x.y.z]` section for the version being released (or `changelogs/vX.Y.Z.md` when that file exists) as the GitHub release notes. Add a new section here before every release.

## [0.1.0] - 2026-11-13

First stable release. It follows the 0.0.1 beta and is cut from the internal v5.12 build.

### Platform names
DeepSync is the project name. Each platform gets its own app name:

| App | Platform | Status |
| --- | --- | --- |
| **Yugen** | Android | Released (this version) |
| **Shinkai** | Linux | Coming soon |
| **Yomi** | Windows | Coming soon |

The Android package id stays `com.kotatsu.deepsync`.

### Added: Yugen identity
- App renamed to **Yugen**, with a new launcher icon (torii gate, cat and book). The old DropSauce mark is gone, including the dark-mode variant. The monochrome layer supports Android themed icons.
- Cold-start splash: the Yugen mark and wordmark pop in, then fade into the app. On Android 12+ the system splash is a matching plain dark screen, so the hand-over is seamless.
- The YUGEN wordmark and logo follow your theme accent colour.
- About screen explains that Yugen is the Android app of the DeepSync project.

### Added: Home
- New Home header: YUGEN wordmark, Settings, Updates bell (with a dot when the feed has news) and Search. It replaces the old three-dots menu.
- Tapping the wordmark opens **Yugen preferences**: Incognito mode, Library filters, Metadata source and Home layout.
- Quick row is a pair: **Suggestions** and **Surprise me**, each with cover art behind it.
- **Home layout** now lives in Settings, with a live banner preview (Panels, Card, Spotlight), auto-rotate, and a switch for every carousel: Banner, New chapters, Sync library, Continue on AniList / MyAnimeList / MangaBaka, Continue reading, Recently released, Trending and Popular.
- **Custom carousel**: show Favourites categories you pick as Home carousels.
- With the banner off, Trending starts from the first title.

### Added: smarter tracker matching and metadata
- Tracker matching now checks every name a title has (romaji, English, native, synonyms), its kind (manga, manhwa or manhua) and whether it is Boys' or Girls' Love. Same-named titles are no longer mixed up (for example a manhwa and a BL manga that share a name).
- **Metadata source** picker: choose covers and info from the tracker (AniList, MyAnimeList or MangaBaka) or from the source. Covers stay in sync with the tracker a title is tracked on.
- **Tracker details page**: tapping a tracker title opens its page immediately (synopsis, alternative titles, genres, tags, characters and voice actors, relations, staff, recommendations, seasons and more). Once a source with the chapters or episodes is found, the real details page fills in.
- **Tracker status button** on the details page shows the status on the tracker (or a plus when untracked) and opens the tracking sheet.
- Tracking sheet has a **progress editor** (minus, plus or typed number) for chapters read or episodes watched.
- The track prompt searches all signed-in trackers at once and shows one card per title, with a landscape layout.
- Auto-track options: **Ask**, **Auto-track exact matches** and **Auto-track closest match** (turning one on turns the others off; novels are skipped for closest match).

### Added: Sync Library and tracker synchronization
- **Sync Library**: one merged list of what every signed-in tracker holds, with a tracker filter and an "Out of sync" marker.
- **Synchronize trackers**: brings every tracker up to the furthest progress any of them has. Progress is never lowered, so running it twice or from two devices is harmless.
- **Auto-synchronize trackers**: every 12 hours, and whenever a title is saved to several trackers.
- Home hides per-tracker rows while Sync library is active.

### Added: anime watch page and Player settings
- Tapping "N episodes" opens a **YouTube-style watch page**: 16:9 video, title, details card, Notes & Annotations, Sub/Dub and sort, and episode cards with watched ticks. Fullscreen landscape is one tap (or turn the phone) and does not restart playback.
- **Notes** are stored on the device per anime; a timestamped note jumps to that moment when tapped.
- **Auto-pick stream**: remembered server, then the server the previous episode used, then your preferred quality. A dead server falls through to the next one.
- Next episode resolves inside the player instead of going through the chooser.
- New **Settings > Player** page (also searchable): Direct landscape, Auto-pick stream, Preferred quality, Audio preset (Sub/Dub), Play next automatically, Skip button length and Always open in VLC.
- All player controls are round, and they fade and scale on springs.

### Added: reader
- **Open in browser** button in the manga toolbar and the novel bar.
- Redesigned **reader quick settings** with Reading, More and Extra tabs, an Advanced reader options page and an All reader settings entry.
- New **Long press for page actions** toggle.

### Added: library and navigation
- **Mark as** picker: Completed, Not read or Not watched. Mark as Completed now also sets Completed and full progress on linked trackers, works for anime and novels, no longer asks "Track this?" per title, and one failing title no longer aborts the batch.
- Global **NSFW mode** (18+ sources and titles everywhere), with per-mode Feed and Suggestions. Home rows honour genre filters and NSFW mode.
- Incognito mode works for anime playback.
- **Mode button** gestures: tap to continue, pull to open a fan and switch mode, hold for the last title's details, with haptics at each step. New setting: **Use mode switch as Continue button**.

### Changed: touch feedback
- The ripple is replaced everywhere by a **pulse**: the tapped icon or label dips and springs back with a small overshoot while backgrounds stay still. It applies to Compose and View screens alike.
- Known limit: Views inside separate dialog windows (alert dialogs, bottom-sheet dialogs) lose the ripple but do not pulse. Compose content inside them still does.

### Fixed
- Gap above the Home header when the activity's top bar was hidden.
- The old search bar flashing when opening Settings or the bell from Home.

### Removed
- Home three-dots menu, the Home layout bottom sheet, the banner picker dialog and the custom-carousel picker dialog (replaced by the screens above).
- DropSauce launcher artwork.

## Beta build history (v0.0.1 to v0.1.0)

The internal builds between the 0.0.1 beta and 0.1.0. Everything below is already included in the 0.1.0 notes above; this is the order it arrived in. Builds marked *unbuilt* were written without Gradle available at the time.

### v5.12 (unbuilt): Yugen name and icon, accent branding, pulse presses, rounder player
- App label is now **Yugen** (debug build shows "Yugen Debug"). Package id and the DeepSync project name inside the code are unchanged.
- New launcher icon traced to a vector (`ic_yugen_logo`), with a monochrome layer for themed icons. Single-colour, so it can be tinted with the theme accent anywhere in the app.
- YUGEN wordmark and dropdown arrow tinted with the accent colour. Player slider, HQ badge, quality chip and skip pill use theme colours.
- `PulseIndication` (Compose) and `PulseTouchCallbacks` (Views) replace the ripple everywhere; ripples are switched off in the theme.
- Player controls are round, and fade and scale on springs.

### v5.11 (unbuilt): YouTube-style watch page and Player settings
- Watch page in a waiting state (blurred cover, episode list) or playing, one activity with a portrait watch layout and the fullscreen landscape player, no restart when switching.
- Notes per anime, auto-pick stream with fallback, next episode resolved inside the player.
- New Settings > Player page, including a UI for "Always open in VLC".

### v5.10 (unbuilt): Home layout in Settings, info-tab patch merged
- Fixed the gap above the Home header and the search bar flash when opening Settings or the bell.
- Quick row is Suggestions plus Surprise me again.
- Home layout moved to Settings with a live banner preview; the bottom sheet and picker dialogs were removed.
- Tracker status button, tracking sheet progress editor, and anime episodes opening from an "N episodes" pill in a bottom sheet (merged from the info-tab patch).

### v5.9 (unbuilt): Home redesign from the Yugen concept
- New Home header with YUGEN wordmark, Settings, bell and Search; Yugen preferences sheet; Home layout sheet with a switch per carousel and a Custom carousel.
- New setting `homeHiddenCarousels`, everything on by default.

### v5.8: V6 features and mark-as fixes
- Reader open-in-browser button, Mark as picker, global NSFW mode, per-mode Feed and Suggestions, incognito anime playback, anime wording for tracker statuses.
- Mark as Completed also updates linked trackers, works for anime and novels, and one failing title no longer aborts the batch.
- Tracker-sync patch: Home hides per-tracker rows when Sync library is active, tracker filter in Sync library.

### Before v5.8
Tracker metadata matching, covers and the metadata picker, the tracker details page and info cards, Sync Library and tracker synchronization, the track prompt with auto-track modes, the reader quick settings redesign, the mode button gestures and the splash overlay were all built in the v5.x builds before v5.8. They have no per-build notes and are listed under 0.1.0 above.

## [0.0.1] - Beta

First public beta (as DeepSync).
- Rebuilt novel reader: scroll styles, presets, custom backgrounds and text-to-speech.
- Redesigned Home with banner styles and a smarter tracker prompt.
- Fork of [DropSauce](https://github.com/HuzaifaKhalid1311/DropSauce), installing as `com.kotatsu.deepsync` next to it.
