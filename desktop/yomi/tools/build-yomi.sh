#!/usr/bin/env bash
# Build the Yomi Windows installer on a Linux PC.
#
#   bash tools/build-yomi.sh            installer  ->  dist/Yomi-Setup-<version>-x64.exe
#   bash tools/build-yomi.sh --zip      also a portable zip  ->  dist/Yomi-Portable-<version>-x64.zip
#   bash tools/build-yomi.sh --check    only check this PC (nothing is built or downloaded)
#   bash tools/build-yomi.sh --help     all options
#
# What it needs: Node 20+, npm, internet on the first run (Electron, NSIS and the Java runtime are downloaded once and cached).
# What it does NOT need: a Windows machine, a VM, or 32-bit wine. NSIS (the installer maker) has a native Linux build.
# wine is optional: it is only used to stamp Yomi's icon and version into Yomi.exe (Explorer, the Start menu shortcut). Without wine
# the installer still builds and works, the window and taskbar use the Yomi icon, but the .exe file itself keeps Electron's icon.
set -euo pipefail
cd "$(dirname "$0")/.."

BRAND=auto ZIP=0 CHECK=0 DRY=0 CLEAN=0
usage() {
  cat <<'EOF'
Usage: bash tools/build-yomi.sh [options]
  --zip        also build the portable zip (unzip anywhere, run Yomi.exe)
  --brand      stamp the Yomi icon + version into Yomi.exe (needs wine64); fail if wine is missing
  --no-brand   skip that step (no wine needed)
  --clean      delete dist/ first
  --check      check this PC and exit (nothing is built or downloaded)
  --dry-run    print every command instead of running it
  -h, --help   this text
Environment: YOMI_UPDATE_URL=<folder with latest.yml> turns on auto-updates in the built app. RCEDIT=/path/to/rcedit-x64.exe to use your own copy.
EOF
}
for a in "$@"; do
  case "$a" in
    --zip) ZIP=1 ;; --brand) BRAND=yes ;; --no-brand) BRAND=no ;; --clean) CLEAN=1 ;;
    --check) CHECK=1 ;; --dry-run) DRY=1 ;; -h | --help) usage; exit 0 ;;
    *) echo "Unknown option: $a" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -t 1 ]; then C1=$'\033[1;36m' C2=$'\033[1;33m' C3=$'\033[1;31m' C4=$'\033[1;32m' C0=$'\033[0m'; else C1='' C2='' C3='' C4='' C0=''; fi
say() { printf '%s==>%s %s\n' "$C1" "$C0" "$*"; }
ok() { printf '  %sok%s   %s\n' "$C4" "$C0" "$*"; }
warn() { printf '  %s!!%s   %s\n' "$C2" "$C0" "$*" >&2; WARNED=1; }
bad() { printf '  %sxx%s   %s\n' "$C3" "$C0" "$*" >&2; FAILED=1; }
WARNED=0 FAILED=0
run() { if [ "$DRY" = 1 ]; then printf '   $ %s\n' "$*"; else "$@"; fi }

# ---------------------------------------------------------------------------------------------------------------- checks
say "Checking this PC"
have() { command -v "$1" >/dev/null 2>&1; }

if have node; then
  NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]')
  if [ "$NODE_MAJOR" -ge 20 ]; then ok "node $(node -v)"; else bad "node $(node -v) is too old, Node 20 or newer is needed (https://nodejs.org or your package manager)"; fi
else bad "node is not installed (Arch: sudo pacman -S nodejs npm | Debian/Ubuntu: sudo apt install nodejs npm)"; fi
have npm && ok "npm $(npm -v)" || bad "npm is not installed"
have unzip || have tar && ok "unzip/tar (unpacks the Java runtime)" || bad "neither unzip nor tar found (sudo apt install unzip)"

if [ -x node_modules/.bin/electron-builder ]; then ok "dependencies installed"
else
  if [ -f package-lock.json ]; then NEED_INSTALL="npm ci"; else NEED_INSTALL="npm install"; fi
  warn "dependencies are not installed yet: this script will run '$NEED_INSTALL' (a few hundred MB)"
fi

# First build downloads Electron + NSIS + Java (~300 MB). Later builds are offline.
CACHE_OK=0
[ -d "$HOME/.cache/electron-builder/nsis" ] && [ -d "$HOME/.cache/electron" ] && [ -f build/win/java/bin/java.exe ] && CACHE_OK=1
if [ "$CACHE_OK" = 1 ]; then ok "downloads cached: this build can run offline"
elif have curl && curl -fsS --max-time 8 -o /dev/null https://github.com 2>/dev/null; then ok "internet reachable (first build downloads about 300 MB, once)"
else
  if [ "$CHECK" = 1 ] || [ "$DRY" = 1 ]; then warn "no internet: the first build needs it for Electron, NSIS and the Java runtime (cached afterwards)"
  else bad "no internet, and the first build needs it to download Electron, NSIS and the Java runtime (about 300 MB, cached afterwards)"; fi
fi

WINE=""
for w in wine64 wine; do have "$w" && WINE="$w" && break; done
if [ "$BRAND" = no ]; then ok "branding skipped (--no-brand)"
elif [ -n "$WINE" ]; then ok "$WINE found: Yomi.exe will get the Yomi icon and version info"
elif [ "$BRAND" = yes ]; then bad "--brand needs wine64 (Arch: sudo pacman -S wine | Debian/Ubuntu: sudo apt install wine64)"
else
  BRAND=no
  warn "wine not found: building without stamping the icon into Yomi.exe. The installer works; install wine64 and re-run to brand the .exe too."
fi

if [ "$CHECK" = 1 ]; then
  echo
  if [ "$FAILED" = 1 ]; then echo "${C3}Not ready:${C0} fix the xx items above."; exit 1; fi
  if [ "$WARNED" = 1 ]; then echo "${C4}Ready to build${C0} (read the !! notes above first). Run: bash tools/build-yomi.sh"; else echo "${C4}Ready to build.${C0} Run: bash tools/build-yomi.sh"; fi
  exit 0
fi
[ "$FAILED" = 1 ] && { echo "Fix the xx items above and run again." >&2; exit 1; }

# ----------------------------------------------------------------------------------------------------------------- build
VER=$(node -p "require('./package.json').version")
say "Building Yomi $VER"
export CI=true WINEDEBUG=-all

if [ "$CLEAN" = 1 ]; then say "Cleaning dist/"; run rm -rf dist; fi
if [ ! -x node_modules/.bin/electron-builder ]; then say "Installing dependencies"; run ${NEED_INSTALL:-npm install}; fi

say "1/4 Bundled runtime (Java 17 + extension bridge; skipped when already downloaded)"
run node tools/fetch-win-runtime.js

say "2/4 Packaging the app"
YOMI_NO_EXE_EDIT=1 run npx electron-builder --win --x64 --dir --config electron-builder.win.js --publish never
EXE=dist/win-unpacked/Yomi.exe
[ "$DRY" = 1 ] || [ -f "$EXE" ] || { echo "Expected $EXE but it was not produced." >&2; exit 1; }

say "3/4 Branding Yomi.exe"
if [ "$BRAND" = no ]; then
  echo "   skipped"
else
  # rcedit is what electron-builder itself uses. Look for it, else download the official release once.
  RC="${RCEDIT:-}"
  [ -n "$RC" ] || RC=$(ls "$HOME"/.cache/electron-builder/winCodeSign/*/rcedit-x64.exe 2>/dev/null | head -n 1 || true)
  [ -n "$RC" ] || RC="$HOME/.cache/yomi/rcedit-x64.exe"
  if [ ! -f "$RC" ]; then
    echo "   downloading rcedit (official electron/rcedit release) to $RC"
    run mkdir -p "$(dirname "$RC")"
    run curl -fL --retry 3 -o "$RC" https://github.com/electron/rcedit/releases/download/v1.1.1/rcedit-x64.exe
  fi
  # a private wine prefix, so this never touches your own one; no Mono/Gecko prompts
  export WINEPREFIX="${YOMI_WINEPREFIX:-$HOME/.cache/yomi/wine}" WINEARCH=win64 WINEDLLOVERRIDES="mscoree,mshtml="
  run "$WINE" "$RC" "$PWD/$EXE" --set-icon "$PWD/build/icon.ico" \
    --set-version-string FileDescription Yomi --set-version-string ProductName Yomi \
    --set-version-string InternalName Yomi --set-version-string OriginalFilename Yomi.exe \
    --set-version-string LegalCopyright "GPL-3.0-only" \
    --set-file-version "$VER" --set-product-version "$VER.0"
fi

say "4/4 Making the installer"
if [ "$ZIP" = 1 ]; then export YOMI_ZIP=1; fi
YOMI_NO_EXE_EDIT=1 run npx electron-builder --win --x64 --prepackaged dist/win-unpacked --config electron-builder.win.js --publish never

echo
say "Done"
if [ "$DRY" = 1 ]; then echo "   (dry run: nothing was built)"; exit 0; fi
for f in dist/Yomi-Setup-*.exe dist/Yomi-Portable-*.zip; do
  [ -f "$f" ] || continue
  printf '   %s  (%s)\n   sha256 %s\n' "$f" "$(du -h "$f" | cut -f1)" "$(sha256sum "$f" | cut -d' ' -f1)"
done
echo "   Copy the .exe to a Windows PC and run it. It is unsigned, so SmartScreen shows 'More info > Run anyway' until you sign it."
