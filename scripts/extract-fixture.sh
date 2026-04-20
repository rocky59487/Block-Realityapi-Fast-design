#!/usr/bin/env bash
# v0.4 M3e — interactive dev-loop helper for `/br pfsf dumpAll`.
#
# Intent: start a Forge runServer, wait for the world to load, drive
# `/br pfsf dumpAll` through stdin, then copy the produced
# `<world>/pfsf-fixtures/*.json` back into the repo's
# `Block Reality/api/src/test/resources/pfsf-fixtures/` tree so the next
# `./gradlew :api:test` run picks them up.
#
# The bulk of the 20 canonical fixtures land through
# `scripts/generate_canonical_fixtures.py` (deterministic, regenerable,
# git-tracked). This shell script is for the "I want to capture an
# actual in-game structure I just built" workflow — useful for recording
# regression cases from player reports or for crafting art-directed
# fixtures that the procedural generator can't express cheaply.
#
# Usage:
#   scripts/extract-fixture.sh                       # interactive
#   scripts/extract-fixture.sh --world dev-world     # non-interactive
#   scripts/extract-fixture.sh --no-copy             # dump only
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_DIR="$REPO_ROOT/Block Reality"
FIX_DEST="$REPO_ROOT/Block Reality/api/src/test/resources/pfsf-fixtures"

WORLD_NAME="dev-world"
COPY_BACK=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --world)    WORLD_NAME="$2"; shift 2 ;;
        --no-copy)  COPY_BACK=0; shift ;;
        -h|--help)
            head -n 20 "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown flag: $1" >&2
            exit 2
            ;;
    esac
done

if [[ ! -f "$GRADLE_DIR/gradlew" ]]; then
    echo "extract-fixture.sh: cannot find $GRADLE_DIR/gradlew" >&2
    exit 1
fi

SERVER_RUN_DIR="$GRADLE_DIR/api/run"
WORLD_DIR="$SERVER_RUN_DIR/$WORLD_NAME"

cat <<EOF
[extract-fixture] starting dev server from $GRADLE_DIR
                  world   = $WORLD_NAME  (at $WORLD_DIR)
                  copy    = $([[ $COPY_BACK -eq 1 ]] && echo "yes → $FIX_DEST" || echo "no")

After the server prints "Done (...s)! For help, type "help"", run:
    /br pfsf dumpAll
Then type  stop  and press <Enter>. Fixtures will be copied back and
the script exits.
EOF

( cd "$GRADLE_DIR" && ./gradlew :api:runServer --console=plain )
EXIT_CODE=$?

if [[ $EXIT_CODE -ne 0 ]]; then
    echo "[extract-fixture] runServer exited with $EXIT_CODE — skipping copy-back" >&2
    exit $EXIT_CODE
fi

SRC_DIR="$WORLD_DIR/pfsf-fixtures"
if [[ ! -d "$SRC_DIR" ]]; then
    echo "[extract-fixture] no $SRC_DIR produced — did you run /br pfsf dumpAll?" >&2
    exit 1
fi

if [[ $COPY_BACK -eq 1 ]]; then
    mkdir -p "$FIX_DEST"
    cp -v "$SRC_DIR"/*.json "$FIX_DEST"/
    echo "[extract-fixture] done — re-run ./gradlew :api:test to pick them up"
else
    echo "[extract-fixture] fixtures left in $SRC_DIR (not copied, --no-copy)"
fi
