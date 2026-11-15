#!/bin/sh
# Rebuilds main/extensions/ctorfix.jar from main/extensions/java/CtorFix.java.
# Needs a JDK (javac) and the AnymeX bridge JAR, which already bundles ASM:  tools/build-ctorfix.sh /path/anymex_desktop_runtime.jar
set -e
BRIDGE=${1:?usage: build-ctorfix.sh /path/anymex_desktop_runtime.jar}
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
javac -source 17 -target 17 -Xlint:-options -cp "$BRIDGE" -d "$OUT" "$HERE/main/extensions/java/CtorFix.java"
rm -f "$HERE/main/extensions/ctorfix.jar"
(cd "$OUT" && zip -qr "$HERE/main/extensions/ctorfix.jar" .)
echo "built $HERE/main/extensions/ctorfix.jar"
