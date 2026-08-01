#!/usr/bin/env bash
# Build the browser game and copy it into a target directory, with the engine import
# stamped by content hash.
#
# The stamp is the point. game.js imports ./tapdodge.js, so a rebuilt engine lands at a URL
# the browser already has cached — index.html and game.js expire on their own schedule, and
# in between a visitor runs a new page against an old engine. That happened during this
# page's first deploy and looked exactly like a bug that was not there.
#
# Usage: tools/deploy_site.sh /path/to/site/dir
set -euo pipefail

DEST="${1:?usage: tools/deploy_site.sh <destination dir>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# check, not just build: the browser artifact should never be published without the
# differential test having agreed that it plays the same game as the JVM.
./gradlew --quiet :web:check

JS="web/build/generated/teavm/js/tapdodge.js"
HASH="$(shasum -a 256 "$JS" | cut -c1-12)"

mkdir -p "$DEST"
cp "$JS" "$DEST/tapdodge.js"
cp web/site/index.html "$DEST/index.html"
sed "s#from \"\./tapdodge\.js\"#from \"./tapdodge.js?v=$HASH\"#" web/site/game.js > "$DEST/game.js"

if ! grep -q "tapdodge.js?v=$HASH" "$DEST/game.js"; then
  echo "error: the import in $DEST/game.js was not stamped — refusing a deploy that can serve" >&2
  echo "       a cached engine against a fresh page. Check the import line in web/site/game.js." >&2
  exit 1
fi

echo "deployed to $DEST (engine $HASH)"
