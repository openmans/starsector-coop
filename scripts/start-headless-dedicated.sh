#!/usr/bin/env bash
# Headless (no window, no X server, no GPU) dedicated server for Starsector Coop on Linux.
#
# Runs the real engine with jars/coop-headless-lwjgl.jar first on the classpath. The Null-LWJGL
# classes create no window and load no native OpenGL/OpenAL library, so no Xvfb and no X11 is
# needed. Saves, mods and the network stack are the same code the graphical client runs.
#
# Usage: scripts/start-headless-dedicated.sh [--port N] [--max-guests N] [--tps N] [--root DIR] [--dry-run]
set -euo pipefail

PORT=7777
MAX_GUESTS=4
TPS=30
ROOT="${STARSECTOR_HOME:-}"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --max-guests) MAX_GUESTS="$2"; shift 2 ;;
    --tps) TPS="$2"; shift 2 ;;
    --root) ROOT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "[ERROR] unknown argument: $1" >&2; exit 2 ;;
  esac
done

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEADLESS_JAR="$MOD_DIR/jars/coop-headless-lwjgl.jar"
FORKS_JAR="$MOD_DIR/jars/coop-forks.jar"

if [ ! -f "$HEADLESS_JAR" ]; then
  echo "[ERROR] jars/coop-headless-lwjgl.jar is missing - build the mod (gradlew build) first" >&2
  exit 1
fi

if [ -z "$ROOT" ]; then
  for candidate in "$MOD_DIR/../.." "$HOME/Starsector" "/opt/starsector" "/opt/Starsector"; do
    if [ -f "$candidate/starsector-core/starfarer_obf.jar" ]; then
      ROOT="$(cd "$candidate" && pwd)"
      break
    fi
  done
fi

if [ -z "$ROOT" ] || [ ! -f "$ROOT/starsector-core/starfarer_obf.jar" ]; then
  echo "[ERROR] Starsector install not found; pass --root /path/to/Starsector" >&2
  exit 1
fi

CORE="$ROOT/starsector-core"
if [ -x "$ROOT/jre/bin/java" ]; then
  JAVA="$ROOT/jre/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  echo "[ERROR] no java found (looked for \$ROOT/jre/bin/java and PATH)" >&2
  exit 1
fi

VMPARAMS="$ROOT/vmparams"
if [ ! -f "$VMPARAMS" ]; then
  echo "[ERROR] vmparams not found under $ROOT" >&2
  exit 1
fi

# First line of vmparams is the java command line. Strip the leading java token and the trailing
# main class, then rewrite the classpath: Windows-style separators are normalised so one file works
# for both packaging flavours of the game.
read -r -d '' VM_LINE < "$VMPARAMS" || true
VM_LINE="${VM_LINE%%$'\n'*}"
VM_LINE="$(printf '%s' "$VM_LINE" | sed -E 's/^[[:space:]]*"?[^[:space:]]*javaw?(\.exe)?"?[[:space:]]+//')"

VM_CP="$(printf '%s' "$VM_LINE" | sed -nE 's/.*(-classpath|-cp)[[:space:]]+"?([^"[:space:]]+)"?.*/\2/p')"
if [ -z "$VM_CP" ]; then
  echo "[ERROR] vmparams has no -classpath entry" >&2
  exit 1
fi

VM_CP_NORM="$(printf '%s' "$VM_CP" | tr ';' ':' | tr '\\' '/')"
REST="$(printf '%s' "$VM_LINE" | sed -E 's/[[:space:]]*(-classpath|-cp)[[:space:]]+"?[^"[:space:]]+"?[[:space:]]*/ /')"
REST="$(printf '%s' "$REST" | sed -E 's/[[:space:]]*com\.fs\.starfarer\.StarfarerLauncher[[:space:]]*$//')"

REL_HEADLESS="$(python3 - "$CORE" "$HEADLESS_JAR" <<'PY' 2>/dev/null || true
import os, sys
print(os.path.relpath(sys.argv[2], sys.argv[1]))
PY
)"
if [ -z "$REL_HEADLESS" ]; then
  # No python3: the mod is normally <root>/mods/<name>, which is two levels up from the core.
  REL_HEADLESS="../mods/$(basename "$MOD_DIR")/jars/coop-headless-lwjgl.jar"
fi
REL_HEADLESS="${REL_HEADLESS//\\//}"

NEW_CP="$REL_HEADLESS:$VM_CP_NORM"
if ! printf '%s' "$VM_CP_NORM" | grep -q 'coop-forks\.jar'; then
  NEW_CP="$REL_HEADLESS:../mods/$(basename "$MOD_DIR")/jars/coop-forks.jar:$VM_CP_NORM"
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "DRY_RUN java=$JAVA"
  echo "DRY_RUN cwd=$CORE"
  echo "DRY_RUN classpath=$NEW_CP"
  echo "DRY_RUN main=coop.headless.CoopHeadlessServerMain"
  exit 0
fi

echo "Starting Starsector Coop HEADLESS dedicated server"
echo "  root      : $ROOT"
echo "  port      : $PORT"
echo "  maxGuests : $MAX_GUESTS"
echo "  tps       : $TPS"
echo "  classpath : $NEW_CP"
echo

cd "$CORE"
# shellcheck disable=SC2086
exec "$JAVA" -classpath "$NEW_CP" $REST \
  -Dcoop.dedicatedHost=true \
  -Dcoop.hostPort="$PORT" \
  -Dcoop.maxGuests="$MAX_GUESTS" \
  -Dcoop.liveWorldInCombat=true \
  -Dcoop.allowMidSessionJoin=true \
  -Dcoop.headless=true \
  -Dcoop.headlessTickRate="$TPS" \
  coop.headless.CoopHeadlessServerMain
