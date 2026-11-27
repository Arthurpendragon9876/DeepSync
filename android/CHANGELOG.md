# Changelog

All notable changes to **Yugen** (the Android app of the **DeepSync** project) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

> The release workflow publishes the `## [x.y.z]` section for the version being released (or `changelogs/vX.Y.Z.md` when that file exists) as the GitHub release notes. Add a new section here before every release.

## [Unreleased]

### Added

- Backup & restore: **Restore from Kototoro/Usagi**, **Export for Kototoro** and **Export for Usagi**. Titles are re-keyed to the other app's ids and sources, tracker ids are renumbered, and anything the other app cannot open is counted and skipped.
- Desktop (Yomi and Shinkai): *Import Android backup* also reads Kototoro and Usagi backups.

## [0.1.2] - 2026-11-22

Stable release. It follows three betas (0.1.2-beta.1 to beta.3) and is meant for everyday use, but it is still young software: please report anything odd. If Android refuses to install it over an earlier version, uninstall once and restore from your backup or cloud sync.

This section lists what is new since 0.1.2-beta.3. Everything from the betas below is included.

### Added
- **Player style**: Settings > Player > Player style lets you pick the control layout from preview tiles: **Yugen** (the default), **Kototoro** or **Simple modern**. It applies the next time a video opens.
- **Redesigned player pop-ups**: settings, speed, subtitles, audio, servers and episodes open as a side panel in landscape and as a bottom sheet in portrait. A new **Episodes** list in the player's More menu shows a preview image for each episode.
- **Extension recommendations for anime**: the extension catalog now also suggests the Aniyomi extensions your library needs but you have not installed, as it already did for manga.
- **Keep favourite categories in every mode** (Settings > Appearance): favourites lists show the titles of the current mode (Manga, Anime or Novel), and a category that is empty in a mode is hidden there unless this switch is on.
- **Search in the tracker libraries**: the Sync library and the tracker libraries have a search field.

### Changed
- **Library filters moved behind a filter icon** next to the search bar, like Explore's. The Reading / Planning / Completed / ... chips and the tracker chips no longer take space above the covers. The sheet keeps the counts, and a small dot on the icon shows that something other than Reading is selected.
- Tracker library entries show their count in the same pill as reading progress, instead of a separate round badge.
- **Explore's header matches Home's**: the same height, and the Manga | Anime | Novel chip has no background on either tab.

### Fixed
- **A new episode (or chapter) no longer moves your progress forward by itself.** When the source changed an episode's internal id, the app worked out where you were from the saved percentage measured against the *new*, longer list, so 6 of 6 became 7 of 7 the moment episode 7 appeared, with nothing in the feed and no notification. It now uses the count that was saved with your position.
- **Anime updates now show in the Feed.** Entries were cut to the 20 most recent *before* the Manga / Anime / Novel filter was applied, so a run of manga updates pushed anime out of view, and switching mode did not refresh the list.
- **No empty strip above Home and Explore.** The top bar collapses cleanly while either tab is shown, so the page below it is no longer pushed down by the bar's old height.
- **The local library keeps up with your files.** The local index now notices when the files on disk changed and rebuilds itself.

## [0.1.2-beta.3] - 2026-11-21

Third beta of **Yugen**, the Android app of the **DeepSync** project. It follows 0.1.2-beta.2 and is meant for testing, so expect rough edges and please report them. It installs over beta 2, beta 1 and 0.1.0 (same package id). Everything below is new since beta 2; the changes are ported from DropSauce 1.1.3 and 1.1.4.

### Added
- **Novel translation now works.** Until now the Translation switches in Advanced novel settings did nothing. Chapters are translated through Google Translate (only the text, so images and layout stay as they are), shown translated in the reader, and cached so a chapter is translated once. **Auto-translate downloads** (#21) translates chapters as they are saved to the downloaded book, so they read translated offline. A chapter that is already in the target language is left alone. If a chapter cannot be translated you see the original text and a short message with the reason.
- **Translation in the novel reader's quick settings**: Options > Translate chapters, with the language to translate to and from. Changing it reloads the chapters you are reading and keeps your place.
- **Extensions per row**: choose 3, 4 or 5 extensions per row in Explore's grid, under Settings > Extensions.
- **Customize voices** in the reader's read-aloud panel: link any installed voice to each of the voice dots, play a sample of the book's own text before choosing, and jump to the engine's voice download screen. Voice links are remembered per language.
- **Tab slide transition**: switching bottom tabs slides the content towards the tab you picked (tabs that change the app bar still swap instantly).

### Changed
- The **Manga | Anime | Novel chip** at the top of Home is centred on the screen and has no background, so it blends into the page.
- Reader presets you create yourself are listed **first**, newest at the top, above the built-in ones.
- **Navigation bar** rebuilt on DropSauce's new one: press and drag along the bar and the selector pill follows your finger, magnifies, and stretches against the screen edge. The mode button (Anime / Manga / Novel fan) is unchanged.
- **Popups** use the new outlined card design with a corner button and a pinned footer, and grow in from their centre.
- Theme, list layout and default reader mode are chosen from visual previews in settings.
- Switching between light and dark theme fades instead of flashing black.
- Corner radii and spacing are now consistent across the anime player, the details screen and the stream picker.

### Fixed
- **Novel chapters always open.** If translated text cannot be displayed, the chapter falls back to the original, and an EPUB image without a source no longer crashes the reader.
- **GitHub sync keeps you signed in.** A saved login is no longer treated as lost when Android's secure storage is briefly unavailable (right after boot, under memory pressure), and a single "unauthorized" answer from GitHub no longer signs you out: the app first asks GitHub whether the login is really revoked.

## [0.1.2-beta.2] - 2026-11-19

Second beta. It follows 0.1.2-beta.1 and is meant for testing: expect rough edges and please report them. It installs over 0.1.2-beta.1 and 0.1.0 (same package id).

### Added
- **More cloud sync providers**: sync your library, history, bookmarks, tracking, stats and settings through **GitHub** (a private `deepsync-sync` repository, signed in with a device code), **Dropbox** or **WebDAV** (Nextcloud and other self-hosted servers). One provider is active at a time. Pick it during onboarding or under Settings > Sync. OneDrive support is in the code but not offered yet.
- **Onboarding**: restore and import choices are grouped into cloud and local sections, with a Skip for now option.
- **Skip chapters in the reader**: Settings > Reader > Chapter navigation has two switches. One makes Next / Previous pass over a chapter number you already have from another scanlator or source. The other passes over in-between chapters (1.1, 1.5 ...), so reading goes 1, 2, 3.
- **Sub / Dub in the player**: the Audio menu now lists the Sub or Dub twin of the playing stream, and switching keeps your position. Picking a stream or an audio track by hand is a temporary choice for that viewing and no longer changes your saved Sub / Dub preference.
- **Watching statistics switch**: recording anime watch time has its own switch next to the Watching statistics entry in settings, separate from the reading statistics switch.
- **Pull to refresh** on the Explore metadata tab.

### Changed
- **Google Drive sync is turned off** in this build. Its sign-in was still unstable, so it is no longer offered and the app makes no requests to Google for sync. Use GitHub, Dropbox or WebDAV instead.
- Reading statistics no longer count anime sessions; those only appear in the watching statistics.
- Explore metadata: choosing only a sort, such as Trending, now shows a result list, and paging through MyAnimeList and MangaBaka results was fixed.

## [0.1.2-beta.1] - 2026-11-16

Beta release. It follows the 0.1.0 stable and is meant for testing: expect rough edges and please report them. It installs over 0.1.0 (same package id).

### Added
- **Anikku backups**: Restore now accepts backups from Anikku too (and Tachiyomi / Mihon as before). The anime library, categories, progress, history and tracking come across.
- **Subtitle customization** in Settings > Player: font, text size, text colour, edge style, background box and position, with a live preview (#16).
- **Anime extensions settings**: anime extensions get their own settings page, like manga extensions (#12). Anime extension icons now show in the source lists, and the extension's own filters (genre, year, status and more) work when browsing anime.
- **Open in browser**: a dedicated button in the reader and the details menu, an **Always open in browser** setting, and holding the WebView button opens the page in your browser. WebView cannot upload images or GIFs for comments, the browser can (#15).
- **Watching statistics**: the stats page now covers anime with watching rate, total watch time, episodes watched, sessions and top anime, plus **Genres you like** bars built from your library, history and stats.
- **Search preferences**: one sheet, opened from the search bar menu, to choose between **Discover** (searches AniList, MyAnimeList and MangaBaka) and **Sources** (searches your installed extensions). The search field's hint always says what a search will hit.
- **Explore metadata tab**: browse tracker catalogues page by page with genre and tag filters, with picks based on the genres you like and trailers where the tracker has one.
- **Advanced novel settings**, opened from the novel reader settings: request throttling and update/import concurrency for novel downloads, and the chapter translation options. Novel sources no longer share the manga slowdown.
- **Suggestions**: separate **Do not suggest** switches for manga, novels and anime.
- **Explore filters**: tags next to genres (BL / Yaoi, GL / Yuri, Harem, Reverse Harem, Isekai and more).

### Changed
- Excluded genres in Suggestions now complete every genre and tag, and excluding one name also excludes its synonyms (Yaoi also drops BL and Boys' Love).
- Adult titles from sources that mix adult and regular content are now caught by their tags (Hentai, Adult, Erotica and similar) when the source gives no rating, so NSFW mode and Suggestions hide them.
- "See your reading and watching stats" is plain text with an arrow, no background.
- The last onboarding screen now points to the Discord community, the source code and the website.
- The YUGEN wordmark artwork was redrawn.

### Removed
- Holding the reader title no longer pops up "Open WebView"; the WebView button already does that (#14).
- The Discover | Sources strip under the search field (replaced by Search preferences).

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
