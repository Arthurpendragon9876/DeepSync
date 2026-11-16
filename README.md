# DeepSync

One project, one app per platform: anime, manga and light novels with tracker sync.

| Folder | App | Platform | Status |
| --- | --- | --- | --- |
| [`android/`](android) | Yugen | Android | released from this repo |
| [`desktop/shinkai/`](desktop/shinkai) | Shinkai | Linux | **beta** |
| [`desktop/yomi/`](desktop/yomi) | Yomi | Windows | ready |

Backups differ between the apps for now: see [docs/BACKUP.md](docs/BACKUP.md).

## Building

- **Android:** [`android/README.md`](android/README.md), e.g. `cd android && ./gradlew :app:assemblePreview`
- **Shinkai:** `cd desktop/shinkai && npm ci && npm start` (tests: `npm test`, packages: `npm run dist`)
- **Yomi:** `cd desktop/yomi && npm ci && npm start` (tests: `npm test`; the Windows installer is built on Windows with `npm run dist:win`)

## GitHub Actions: one workflow per build

Each build has its own workflow and its own tag prefix, so releasing one never triggers another.

| Workflow | When it runs | Output |
| --- | --- | --- |
| `android-preview.yml` | manually; pushes touching `android/**` on branch `desktop` (temporary) | preview APK artifact |
| `android-release.yml` | manually (you enter the version) | GitHub release `android-v<version>` with the APK |
| `shinkai-linux.yml` | manually; pushes touching `desktop/shinkai/**` on branch `desktop` (temporary); tag `shinkai-v*` | Linux packages as an artifact; **pre-release** on tag |
| `yomi-windows.yml` | manually; pushes touching `desktop/yomi/**` on branch `desktop` (temporary); tag `yomi-v*` | Windows installer artifact; **release** on tag |
| `backup-bridge-test.yml` | pull requests touching the backup converter | test run |

Releasing Yomi: bump `version` in `desktop/yomi/package.json`, commit, then `git tag yomi-v<version> && git push origin yomi-v<version>`. Same tag-must-match rule as below.

Releasing Shinkai beta: bump `version` in `desktop/shinkai/package.json`, commit, then `git tag shinkai-v0.4.10 && git push origin shinkai-v0.4.10`. The tag must match `package.json` or the build stops.

Licensed under GPL-3.0, see [LICENSE](LICENSE).
