#!/usr/bin/env bash
#
# Roll one service onto a new image tag. Runs ON the server; the per-service workflow calls it
# over ssh. Also fine by hand:  deploy/apply.sh message a1b2c3d
#
# Each service has its own *_TAG in deploy/.env, so this touches one line, pulls one image and
# restarts one container. Nothing else in the stack notices.

set -euo pipefail

SERVICE="${1:?usage: apply.sh <service> <tag>}"
TAG="${2:?usage: apply.sh <service> <tag>}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
VAR="$(echo "$SERVICE" | tr 'a-z-' 'A-Z_')_TAG"

dc() { docker compose -f "$ROOT/deploy/docker-compose.prod.yml" --env-file "$ENV_FILE" "$@"; }

# Serialise against other services deploying at the same moment — a change under common/ fires
# all eight workflows at once and they would otherwise race this read-modify-write of .env.
exec 9>"$ROOT/deploy/.apply.lock"
flock 9

PREV="$(sed -n "s/^$VAR=//p" "$ENV_FILE" | head -1)"

set_tag() {
  local tmp; tmp="$(mktemp "$ENV_FILE.XXXXXX")"; chmod 600 "$tmp"
  if grep -q "^$VAR=" "$ENV_FILE"; then
    sed "s|^$VAR=.*|$VAR=$1|" "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"; printf '%s=%s\n' "$VAR" "$1" >> "$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
}

roll() {
  set_tag "$1"
  dc pull "$SERVICE"
  # --no-deps so a restart of one service never drags its dependencies with it.
  dc up -d --no-deps "$SERVICE"
}

healthy() {
  local deadline=$(( SECONDS + ${TIMEOUT:-300} )) state
  while :; do
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "relay-$SERVICE" 2>/dev/null || echo missing)"
    case "$state" in healthy|running) return 0 ;; esac
    [ "$SECONDS" -ge "$deadline" ] && { echo "  $SERVICE stuck at '$state'" >&2; return 1; }
    sleep 5
  done
}

echo "==> $SERVICE: ${PREV:-<unset>} -> $TAG"
if roll "$TAG" && healthy; then
  echo "==> $SERVICE healthy at $TAG"
else
  if [ -n "$PREV" ] && [ "$PREV" != "$TAG" ]; then
    echo "==> rolling $SERVICE back to $PREV" >&2
    roll "$PREV" && healthy && echo "==> rolled back to $PREV" >&2
  fi
  exit 1
fi
