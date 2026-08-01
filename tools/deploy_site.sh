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

# Every asset carries the stamp; only index.html does not, because it is the entry point and
# nothing can version it from outside. Stamping tapdodge.js alone is not enough: game.js is
# what imports it, so a cached game.js pulls in the unversioned URL and both stay stale
# together. That is exactly what happened on the first attempt at this fix.
sed "s#from \"\./tapdodge\.js\"#from \"./tapdodge.js?v=$HASH\"#" web/site/game.js > "$DEST/game.js"
sed "s#src=\"\./game\.js\"#src=\"./game.js?v=$HASH\"#" web/site/index.html > "$DEST/index.html"

for check in "$DEST/game.js:tapdodge.js?v=$HASH" "$DEST/index.html:game.js?v=$HASH"; do
  file="${check%%:*}"
  want="${check#*:}"
  if ! grep -q "$want" "$file"; then
    echo "error: $file was not stamped with '$want' — refusing a deploy that can serve a" >&2
    echo "       cached asset against a fresh page. Check the reference in web/site/." >&2
    exit 1
  fi
done

echo "deployed to $DEST (engine $HASH)"
