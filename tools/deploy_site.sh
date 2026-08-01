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

# Each asset is stamped with ITS OWN hash. Stamping everything with the engine's hash meant a
# change to game.js alone kept the same URL, so browsers kept serving the cached old file --
# which is exactly how a fixed page shipped and still behaved like the broken one.
hash_of() { shasum -a 256 "$1" | cut -c1-12; }
ENGINE_HASH="$(hash_of "$JS")"

mkdir -p "$DEST"
cp "$JS" "$DEST/tapdodge.js"

# Sound files are the game's, not the engine's, so they live wherever the site is deployed
# rather than in this repo. Warn loudly if they are missing: a silent game looks broken, and
# the page tells the reader the music starts on their first tap.
for a in bgm_loop.ogg score.wav death.wav; do
  if [ ! -f "$DEST/$a" ]; then
    echo "warning: $DEST/$a is missing — the page will load without it" >&2
  fi
done

# Every asset carries the stamp; only index.html does not, because it is the entry point and
# nothing can version it from outside. Stamping tapdodge.js alone is not enough: game.js is
# what imports it, so a cached game.js pulls in the unversioned URL and both stay stale
# together. That is exactly what happened on the first attempt at this fix.
sed "s#from \"\./tapdodge\.js\"#from \"./tapdodge.js?v=$ENGINE_HASH\"#" web/site/game.js > "$DEST/game.js"
GAME_HASH="$(hash_of "$DEST/game.js")"
cp web/site/index.html "$DEST/index.html"

# The manifest the page reads with cache:"no-store". This is the only file that must never be
# cached, and it is small enough that fetching it every load costs nothing.
printf '{"game":"%s","engine":"%s"}\n' "$GAME_HASH" "$ENGINE_HASH" > "$DEST/version.json"

for check in "$DEST/game.js:tapdodge.js?v=$ENGINE_HASH" "$DEST/version.json:$GAME_HASH"; do
  file="${check%%:*}"
  want="${check#*:}"
  if ! grep -q "$want" "$file"; then
    echo "error: $file was not stamped with '$want' — refusing a deploy that can serve a" >&2
    echo "       cached asset against a fresh page. Check the reference in web/site/." >&2
    exit 1
  fi
done

echo "deployed to $DEST (engine $ENGINE_HASH, page $GAME_HASH)"
