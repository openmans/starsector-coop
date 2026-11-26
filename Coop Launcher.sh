#!/usr/bin/env bash
# Starts the Starsector co-op launcher on Linux or macOS using the JRE bundled with
# Starsector (or Java 17+ on PATH as a fallback).
#
# Lives at <install>/mods/coop/Coop Launcher.sh, so the install root is two levels up.
set -euo pipefail

MOD_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$MOD_DIR/../.." && pwd)"

if [[ ! -d "$ROOT/starsector-core" ]]; then
    echo "Could not find starsector-core under \"$ROOT\"." >&2
    echo "The mod must sit in <Starsector>/mods/coop." >&2
    exit 1
fi

JAVA_BIN=""
for candidate in \
    "$ROOT/jre_linux/bin/java" \
    "$ROOT/jre/bin/java" \
    "$ROOT/Contents/Home/jre/bin/java" \
    "$ROOT/jre_mac/bin/java"
do
    if [[ -x "$candidate" ]]; then
        JAVA_BIN="$candidate"
        break
    fi
done

if [[ -z "$JAVA_BIN" ]]; then
    if command -v java >/dev/null 2>&1; then
        JAVA_BIN="$(command -v java)"
    else
        echo "Could not find a Java runtime (bundled JRE or java on PATH)." >&2
        exit 1
    fi
fi

CP="$MOD_DIR/jars/coop-launcher.jar:$MOD_DIR/jars/flatlaf.jar:$MOD_DIR/jars/coop.jar:$ROOT/starsector-core/json.jar:$ROOT/starsector-core/log4j-1.2.9.jar"
exec "$JAVA_BIN" -cp "$CP" coop.launcher.CoopLauncherApp "$@"
