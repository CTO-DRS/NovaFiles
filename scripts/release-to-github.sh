#!/usr/bin/env bash
# =============================================================================
# NovaFiles — one-command GitHub release publisher
# =============================================================================
# Pushes main + tag to GitHub and creates a GitHub Release with artifacts.
# Token from env only — never stored, never in URLs.
#
# Usage:
#   export GITHUB_TOKEN=ghp_xxx            # PAT with Contents: read/write
#   bash scripts/release-to-github.sh
#
# Options (env):
#   REPO=CTO-DRS/NovaFiles   TAG=v0.41.0
#   DRY_RUN=1                show actions without executing
#   FORCE_PUSH=1             overwrite remote main if histories diverge
#   FORCE_RELEASE=1          overwrite existing release notes/assets
# =============================================================================
set -euo pipefail

REPO="${REPO:-CTO-DRS/NovaFiles}"
TAG="${TAG:-v0.41.0}"
LOCAL_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ART_DIR="${ART_DIR:-/home/z/my-project/download}"
NOTES_FILE="${NOTES_FILE:-$ART_DIR/release-notes-$TAG.md}"
API="https://api.github.com"
UPLOAD="https://uploads.github.com"

ASSETS=(
  "$ART_DIR/NovaFiles-$TAG-release.apk"
  "$ART_DIR/NovaFiles-$TAG-debug.apk"
  "$ART_DIR/NovaFiles-$TAG-mapping.zip"
  "$ART_DIR/NovaFiles-$TAG-src.zip"
)

log()  { printf '\033[1;36m[release]\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; }
fail() { err "$*"; exit 1; }

[[ -n "${GITHUB_TOKEN:-}" ]] || fail "GITHUB_TOKEN is not set — export it first"
[[ -d "$LOCAL_REPO/.git" ]] || fail "no local repo at $LOCAL_REPO"
[[ -f "$NOTES_FILE" ]] || fail "notes file missing: $NOTES_FILE"
for a in "${ASSETS[@]}"; do [[ -f "$a" ]] || fail "asset missing: $a"; done
git -C "$LOCAL_REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
  || fail "tag $TAG not found locally"

ASKPASS="$(mktemp)"
trap 'rm -f "$ASKPASS"' EXIT
printf '#!/usr/bin/env bash\necho "$GITHUB_TOKEN"\n' > "$ASKPASS"
chmod 600 "$ASKPASS"
export GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 GITHUB_TOKEN

git -C "$LOCAL_REPO" remote remove origin 2>/dev/null || true
git -C "$LOCAL_REPO" remote add origin "https://github.com/$REPO.git"

run() { if [[ "${DRY_RUN:-0}" == "1" ]]; then log "DRY-RUN: $*"; else "$@"; fi; }

log "pushing main to $REPO ..."
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  if git -C "$LOCAL_REPO" push origin main 2>/tmp/nova-push.err; then
    log "main pushed"
  elif rg -q "non-fast-forward|rejected" /tmp/nova-push.err; then
    [[ "${FORCE_PUSH:-0}" == "1" ]] || fail "non-fast-forward — use FORCE_PUSH=1 deliberately"
    git -C "$LOCAL_REPO" push --force origin main
  else
    cat /tmp/nova-push.err >&2; fail "push failed — check token scope"
  fi
  git -C "$LOCAL_REPO" push origin "refs/tags/$TAG"
  log "tag $TAG pushed"
else
  log "DRY-RUN: push origin main + refs/tags/$TAG"
fi

[[ "${DRY_RUN:-0}" == "1" ]] && { log "DRY-RUN end — nothing executed"; exit 0; }

auth=(-H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json")
EXIST_ID=$(curl -sS "${auth[@]}" "$API/repos/$REPO/releases/tags/$TAG" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('id', ''))" || true)
BODY=$(python3 -c "import json,sys; print(json.dumps(open(sys.argv[1],encoding='utf-8').read()))" "$NOTES_FILE")

if [[ -n "$EXIST_ID" && "${FORCE_RELEASE:-0}" != "1" ]]; then
  log "release exists (id=$EXIST_ID) — skipping creation (FORCE_RELEASE=1 to overwrite)"
  REL_ID="$EXIST_ID"
elif [[ -n "$EXIST_ID" ]]; then
  curl -sS "${auth[@]}" -X PATCH "$API/repos/$REPO/releases/$EXIST_ID" -d "{\"body\": $BODY}" >/dev/null
  log "existing release notes updated"; REL_ID="$EXIST_ID"
else
  CREATE=$(curl -sS "${auth[@]}" -X POST "$API/repos/$REPO/releases" \
    -d "{\"tag_name\": \"$TAG\", \"name\": \"NovaFiles $TAG\", \"body\": $BODY}")
  REL_ID=$(echo "$CREATE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))")
  [[ -n "$REL_ID" ]] || { echo "$CREATE" | head -c 400 >&2; fail "release creation failed"; }
  log "release created (id=$REL_ID)"
fi

declare -A CT=( [".apk"]="application/vnd.android.package-archive" [".zip"]="application/zip" )
for a in "${ASSETS[@]}"; do
  name="$(basename "$a")"; ct="${CT[${name: -4}]}"
  if [[ "${FORCE_RELEASE:-0}" == "1" ]]; then
    OLD=$(curl -sS "${auth[@]}" "$API/repos/$REPO/releases/$REL_ID/assets" | python3 -c "
import sys, json
for x in json.load(sys.stdin):
    if x['name'] == '$name': print(x['id']); break" || true)
    [[ -n "${OLD:-}" ]] && curl -sS "${auth[@]}" -X DELETE "$API/repos/$REPO/releases/assets/$OLD" >/dev/null
  fi
  log "uploading $name ($(du -h "$a" | cut -f1)) ..."
  UP=$(curl -sS "${auth[@]}" -X POST --data-binary "@$a" -H "Content-Type: $ct" \
    "$UPLOAD/repos/$REPO/releases/$REL_ID/assets?name=$name")
  echo "$UP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
sys.exit(0 if d.get('state') == 'uploaded' else 1)" || { echo "$UP" | head -c 300 >&2; err "upload failed: $name"; }
done

log "DONE — https://github.com/$REPO/releases/tag/$TAG"
