# Cloud sync

One active provider at a time: **GitHub, Dropbox, OneDrive or WebDAV** (Google Drive is switched off for now, see below). They all sit behind
`sync/backend/SyncBackend`; the merge engine (`SyncRepository`, `ReplicaMerger`) never knows which one
it talks to. Switching = sign out (forgets cursors and join state) -> sign in elsewhere -> the first
sync uploads everything. Local `.bk.zip` backups and tracker accounts are independent and can be used
alongside any provider.

## What is synced
Each device writes only its own `replica_<device>` file and merges everyone else's (newest version per
record wins, deletions are tombstones), so devices cannot overwrite each other. Custom covers (images
the user set; never ordinary covers, those are re-fetched from sources) are content-addressed
`cover_<hash>` files; turn them off under *What to sync* if you don't want them uploaded.

## Build-time setup (public identifiers only, never ship a secret)
| Provider | Setting | Notes |
|---|---|---|
| Google Drive (**off**) | none in code; `GoogleDriveBackend.ENABLED = false` | Register package name + signing SHA-1 of **each** build (debug, release, Play App Signing) in Google Cloud Console; add test users while the consent screen is in Testing. |
| GitHub | `-PGITHUB_CLIENT_ID` / env | OAuth App with **Device Flow** enabled. Scope `repo` (the only one that reaches private repos). Creates private `<user>/deepsync-sync` by itself. |
| Dropbox | `-PDROPBOX_APP_KEY` / env | App with **App folder** access; permissions `files.content.read/write`, `account_info.read`; redirect URI `db-<app key>://oauth`. |
| OneDrive | `-PONEDRIVE_CLIENT_ID` / env | Microsoft Entra app, **personal accounts**, public client (no secret). Redirect URI `msal<client id>://auth` (platform: Mobile and desktop). Delegated permissions `Files.ReadWrite.AppFolder`, `User.Read`, `offline_access`. Work/school accounts are not supported (they have no app folder). |
| WebDAV | none | User enters address, user name, password (Nextcloud: an app password). Folder `DeepSync/` is created. Plain http only on local-network hosts. |

A provider whose identifier is empty is simply not offered.

**Google Drive is disabled** since 0.1.2-beta.2: sign-in was unstable and the project prefers not to keep user data with Google. The code is still in the tree; set `GoogleDriveBackend.ENABLED = true` to bring it back (and register each build's package name + signing SHA-1 first, as in the table).

## Repo/folder layout
- GitHub: `replicas/replica_<device>.json` (canonical JSON, one record per line, git-delta friendly), `covers/cover_<hash>`.
- Drive: hidden `appDataFolder`; Dropbox and OneDrive: the app folder; WebDAV: `<address>/DeepSync/`. Files are `replica_<device>.json.gz`.

## Security
- Tokens and the WebDAV password: AES-GCM with a non-exportable Android Keystore key, stored in `noBackupFilesDir` (`SecretStore`).
- Dropbox and OneDrive use PKCE and a random `state`; the pending verifier is single-use. OneDrive uploads over 4 MB use an upload session in 3.2 MB chunks.
- GitHub's `repo` scope is broad by nature; DeepSync only touches `deepsync-sync`, and refuses to use it if it is public.

## Failure handling
- 401 / revoked grant -> sync pauses, the settings screen shows **Sign in again**, a notification is posted.
- GitHub repo deleted/renamed -> found or recreated automatically, sync retried once.
- Dropbox and OneDrive access tokens are refreshed transparently (also once on a surprise 401); OneDrive rotates the refresh token on every refresh and the new one is always stored. A refresh token unused for 90 days expires: the user signs in again.
- Rate limits (GitHub 403/429, Dropbox 429) are honoured up to a minute, else surfaced.

## Adding a provider
1. Add an entry to `SyncProvider`.
2. Implement `SyncBackend` (+ a `SyncSession` of list/download/create/update/delete) and register it in `SyncBackendsModule`.
3. Add its sign-in and one `when` branch in `SyncSettingsViewModel.signInWith` and the two string helpers in `SyncSettingsFragment`.

## Onboarding
The restore slide has two sections, as full-width rows: **Cloud storage sync** (one row per provider configured
in the build; WebDAV carries a "Self-hosted" tag) and **Local archive restore** (Kotatsu backup, and
Tachiyomi/Mihon/Anikku backups with the explore icon). A "Skip for now" button moves on. See `SyncSlide` in
`OnboardingScreen.kt`.

## Settings
`Settings` starts with a **Cloud sync** profile card (provider and account, "Not signed in", or "sign in again")
that opens the sync screen (`CloudSyncProfileCard` in `RootSettingsFragment.kt`).
