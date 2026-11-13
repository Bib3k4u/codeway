#!/usr/bin/env bash
# Pull-based deploys: GitHub Actions publishes each backend build as the release "backend-latest" (jar + sha256 +
# the commit). This runs every 2 minutes (codeway-update.timer), and when the published build differs from the
# installed one it verifies the checksum, rebuilds the sandbox image if its tag changed, installs the jar, restarts,
# waits for health, and rolls back to the previous jar if the new one doesn't come up. No inbound access is needed.
set -euo pipefail
REPO="${CODEWAY_REPO:-Bib3k4u/codeway}"
TAG=backend-latest
# overridable for testing; the defaults are the real server
DIR="${CODEWAY_DIR:-/opt/codeway}"
API="${GITHUB_API:-https://api.github.com}"
RAW="${GITHUB_RAW:-https://raw.githubusercontent.com}"
HEALTH="${HEALTH_URL:-http://localhost:8080/api/health}"
RESTART="${RESTART_CMD:-systemctl restart codeway}"
OWNER="${INSTALL_OWNER--o codeway -g codeway}"
mkdir -p "$DIR/releases"
STATE="$DIR/releases/installed.sha256"
log() { echo "[codeway-update] $*"; }

json=$(curl -fsL --max-time 20 -H "Accept: application/vnd.github+json" "$API/repos/$REPO/releases/tags/$TAG") || { log "no release yet"; exit 0; }
PARSED=$(RELEASE_JSON="$json" python3 -c '
import json, os
r = json.loads(os.environ["RELEASE_JSON"])
assets = {a["name"]: a["browser_download_url"] for a in r.get("assets", [])}
body = (r.get("body") or "").split()
print(assets.get("codeway-backend.jar", "-"), assets.get("codeway-backend.jar.sha256", "-"), body[0] if body else "-")
')
read -r JAR_URL SHA_URL COMMIT <<< "$PARSED"
[ "$JAR_URL" != "-" ] && [ "$SHA_URL" != "-" ] || { log "release has no jar yet"; exit 0; }
WANT=$(curl -fsSL --max-time 20 "$SHA_URL" | awk '{print $1}')
[ -n "$WANT" ] || exit 0
[ -f "$STATE" ] && [ "$(cat "$STATE")" = "$WANT" ] && exit 0

log "new build $WANT (commit $COMMIT)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
curl -fsSL --max-time 300 -o "$TMP/codeway-backend.jar" "$JAR_URL"
GOT=$(sha256sum "$TMP/codeway-backend.jar" | awk '{print $1}')
[ "$GOT" = "$WANT" ] || { log "checksum mismatch ($GOT), not installing"; exit 1; }

# The sandbox image is versioned by deploy/ec2/sandbox/tag; rebuild only when that commit has a new tag.
if [ "$COMMIT" != "-" ]; then
  mkdir -p "$TMP/sandbox"
  ok=1
  for f in Dockerfile tag ensure.sh; do curl -fsSL --max-time 20 -o "$TMP/sandbox/$f" "$RAW/$REPO/$COMMIT/deploy/ec2/sandbox/$f" || ok=0; done
  [ "$ok" = 1 ] && bash "$TMP/sandbox/ensure.sh" || log "sandbox files not fetched; keeping the current image"
fi

[ -f "$DIR/codeway-backend.jar" ] && cp "$DIR/codeway-backend.jar" "$DIR/releases/previous.jar"
install $OWNER -m 644 "$TMP/codeway-backend.jar" "$DIR/codeway-backend.jar"
$RESTART
for _ in $(seq 1 "${HEALTH_TRIES:-90}"); do curl -fs "$HEALTH" >/dev/null && { echo "$WANT" > "$STATE"; log "deployed $COMMIT, healthy"; exit 0; }; sleep 2; done

log "new build did not become healthy; rolling back"
journalctl -u codeway -n 40 --no-pager 2>/dev/null || true
if [ -f "$DIR/releases/previous.jar" ]; then
  install $OWNER -m 644 "$DIR/releases/previous.jar" "$DIR/codeway-backend.jar"
  $RESTART
fi
echo "$WANT" > "$STATE"   # don't retry the same broken build every 2 minutes; the next push replaces it
exit 1
