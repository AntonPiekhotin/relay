#!/usr/bin/env bash
#
# Roll a new image tag onto the running stack, one named service at a time.
#
# Runs ON the production host. .github/workflows/deploy.yml checks the repo out at the deployed
# commit and then invokes this over ssh; it is equally usable by hand.
#
# Usage:
#   deploy/remote-apply.sh --tag TAG [options] SERVICE...
#
#   --tag TAG        image tag to move to. Written into deploy/.env as RELAY_IMAGE_TAG, so the
#                    file stays the single source of truth for what is deployed.
#   --infra          also reconcile nginx and the ELK config containers (bind-mounted config,
#                    so a file edit needs an explicit reload/restart — compose cannot see it)
#   --full           recreate EVERY container instead of the named ones. See the warning below.
#   --timeout SECS   how long to wait for each service to report healthy (default 300)
#   --no-rollback    leave a failed deploy in place instead of restoring the previous tag
#
# WHY ONLY THE NAMED SERVICES, AND WHY --full IS A DELIBERATE FLAG
#
# Compose decides whether to recreate a container by hashing its resolved config, and `image:`
# is part of that hash. Bumping RELAY_IMAGE_TAG therefore changes the hash of all eight services
# even where the image digest is byte-identical, so a bare `up -d` restarts everything —
# including websocket-gateway, which drops every live client (deploy/README.md, ARCHITECTURE.md
# §6). Naming the services keeps a one-service deploy to one restart.
#
# The cost of that choice: containers that were not named keep the config hash of an older tag,
# so the next `--full` (or any hand-run `up -d`) restarts them once, with identical bits. That is
# a cosmetic restart, not a version skew — the retag step in the workflow guarantees every
# service has an image at every deployed tag.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
COMPOSE_FILE="$ROOT/deploy/docker-compose.prod.yml"

TAG=""
INFRA=0
FULL=0
TIMEOUT=300
ROLLBACK=1
SERVICES=()

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'
else
  C_RESET=; C_RED=; C_GREEN=; C_YELLOW=; C_BOLD=
fi
step() { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '  %sok%s   %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '  %swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '  %sfail%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)         shift; TAG="${1:-}" ;;
    --timeout)     shift; TIMEOUT="${1:-300}" ;;
    --infra)       INFRA=1 ;;
    --full)        FULL=1 ;;
    --no-rollback) ROLLBACK=0 ;;
    -h|--help)     sed -n '2,30p' "$0"; exit 0 ;;
    -*)            die "unknown option: $1 (try --help)" ;;
    *)             SERVICES+=("$1") ;;
  esac
  shift
done

[ -n "$TAG" ]           || die "--tag is required"
[ -f "$ENV_FILE" ]      || die "$ENV_FILE not found — copy deploy/.env.example and fill it in"
[ -f "$COMPOSE_FILE" ]  || die "$COMPOSE_FILE not found"
command -v docker >/dev/null || die "docker not on PATH"

if [ "$FULL" -eq 0 ] && [ "${#SERVICES[@]}" -eq 0 ] && [ "$INFRA" -eq 0 ]; then
  die "nothing to do — name at least one service, or pass --infra / --full"
fi

# --full recreates everything, so everything is what we wait on.
if [ "$FULL" -eq 1 ] && [ "${#SERVICES[@]}" -eq 0 ]; then
  SERVICES=(eureka api-gateway auth user message notification websocket-gateway call)
fi

dc() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

# ── the deployed tag lives in .env, and that is what a rollback reads ───────────────────────
read_tag()  { sed -n 's/^RELAY_IMAGE_TAG=//p' "$ENV_FILE" | head -1; }
write_tag() {
  # Not `sed -i`: it takes an argument on BSD and none on GNU, and this file is also edited by
  # hand on a laptop. Rewrite through a temp file in the same directory, then rename.
  local tmp
  tmp="$(mktemp "$ENV_FILE.XXXXXX")" || die "cannot write next to $ENV_FILE"
  chmod 600 "$tmp"
  if grep -q '^RELAY_IMAGE_TAG=' "$ENV_FILE"; then
    sed "s|^RELAY_IMAGE_TAG=.*|RELAY_IMAGE_TAG=$1|" "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    printf 'RELAY_IMAGE_TAG=%s\n' "$1" >> "$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
}

PREV_TAG="$(read_tag)"
step "tag ${PREV_TAG:-<unset>} -> $TAG"

# ── wait for the health that compose already defines, rather than a sleep ───────────────────
wait_healthy() {
  local deadline=$(( SECONDS + TIMEOUT )) svc name state pending
  while :; do
    pending=""
    for svc in "$@"; do
      name="relay-$svc"
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || echo missing)"
      case "$state" in
        healthy|running) ;;
        *) pending="$pending $svc($state)" ;;
      esac
    done
    [ -z "$pending" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { warn "still not healthy after ${TIMEOUT}s:$pending"; return 1; }
    sleep 5
  done
}

apply() {                                     # apply <tag> <service>...
  local tag="$1"; shift
  write_tag "$tag"
  if [ "$FULL" -eq 1 ]; then
    step "pull (all)"; dc pull || die "pull failed"
    step "up -d (all containers — every service restarts)"
    dc up -d --remove-orphans || return 1
  else
    step "pull$(printf ' %s' "$@")"; dc pull "$@" || die "pull failed"
    step "up -d --no-deps$(printf ' %s' "$@")"
    dc up -d --no-deps "$@" || return 1
  fi
}

# ── application services ────────────────────────────────────────────────────────────────────
if [ "$FULL" -eq 1 ] || [ "${#SERVICES[@]}" -gt 0 ]; then
  if ! apply "$TAG" ${SERVICES[@]+"${SERVICES[@]}"} || ! wait_healthy ${SERVICES[@]+"${SERVICES[@]}"}; then
    if [ "$ROLLBACK" -eq 1 ] && [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$TAG" ]; then
      warn "rolling back to $PREV_TAG"
      apply "$PREV_TAG" ${SERVICES[@]+"${SERVICES[@]}"} && wait_healthy ${SERVICES[@]+"${SERVICES[@]}"} \
        && warn "rolled back to $PREV_TAG" \
        || die "rollback to $PREV_TAG ALSO failed — the stack needs a human"
    fi
    dc ps
    die "deploy of$(printf ' %s' ${SERVICES[@]+"${SERVICES[@]}"}) failed at tag $TAG"
  fi
  ok "healthy at $TAG:$(printf ' %s' ${SERVICES[@]+"${SERVICES[@]}"})"
fi

# ── config-only containers ──────────────────────────────────────────────────────────────────
# nginx.conf and the ELK pipelines are bind-mounted, so their content is invisible to compose's
# config hash: `up -d` is a no-op after an edit and the reload has to be explicit.
if [ "$INFRA" -eq 1 ] && [ "$FULL" -eq 0 ]; then
  step "nginx config"
  if dc exec -T nginx nginx -t; then
    dc exec -T nginx nginx -s reload && ok "nginx reloaded (no dropped connections)"
  else
    die "nginx -t rejected the new config; nothing reloaded, the old config is still serving"
  fi

  step "ELK config"
  dc restart logstash filebeat && ok "logstash + filebeat restarted"
fi

step "state"
dc ps --format 'table {{.Service}}\t{{.Status}}'
printf '\n%sDeployed %s%s\n' "$C_BOLD" "$TAG" "$C_RESET"