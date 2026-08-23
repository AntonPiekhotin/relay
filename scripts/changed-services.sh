#!/usr/bin/env bash
#
# Decide which services a range of commits actually touched.
#
# Usage:
#   scripts/changed-services.sh <base-ref> [head-ref]      # default head-ref: HEAD
#
# Prints one JSON object on stdout, nothing else — the deploy workflow reads it with fromJSON:
#
#   {"all":false,"build":["message"],"retag":["auth","call",...],"infra":false,"compose":false}
#
#   build    services whose image must be rebuilt from source
#   retag    services whose image at <base-ref> can simply be re-pointed at the new tag
#   infra    nginx / ELK / Keycloak config changed — those containers need reconciling
#   compose  deploy/docker-compose.prod.yml changed, which can affect anything; the workflow
#            surfaces this rather than acting on it, because a full `up -d` restarts
#            websocket-gateway and drops every live client (deploy/README.md)
#
# Why `build` and `retag` are disjoint but together always cover all eight services: the server
# runs a single RELAY_IMAGE_TAG, so every tag must be a *complete* set of images even when only
# one service was rebuilt. The workflow copies the rest forward with `buildx imagetools create`,
# which moves a manifest without pulling a layer.
#
# A change under common/ or to deploy/Dockerfile rebuilds everything: every service embeds the
# published `common` jar (CLAUDE.md invariant 1 — it is an artifact, not a project dependency),
# and one Dockerfile builds all eight.

set -euo pipefail

SERVICES="eureka api-gateway auth user message notification websocket-gateway call"

BASE="${1:-}"
HEAD="${2:-HEAD}"

# ── JSON helpers ────────────────────────────────────────────────────────────────────────────
json_array() {           # json_array a b c  ->  ["a","b","c"]
  local out="" s
  for s in "$@"; do out="$out,\"$s\""; done
  printf '[%s]' "${out#,}"
}

emit() {                 # emit <all> <infra> <compose> <build-list> -- <retag-list>
  local all="$1" infra="$2" compose="$3"; shift 3
  local build=() retag=() seen=0 s
  for s in "$@"; do
    if [ "$s" = "--" ]; then seen=1; continue; fi
    if [ "$seen" -eq 0 ]; then build+=("$s"); else retag+=("$s"); fi
  done
  printf '{"all":%s,"build":%s,"retag":%s,"infra":%s,"compose":%s}\n' \
    "$all" \
    "$(json_array ${build[@]+"${build[@]}"})" \
    "$(json_array ${retag[@]+"${retag[@]}"})" \
    "$infra" "$compose"
}

everything() { emit true false true $SERVICES -- ; }

# ── no usable base: rebuild the world ───────────────────────────────────────────────────────
# First deploy, a hand-set RELAY_IMAGE_TAG like `latest`, or a base commit this clone does not
# have (force-push, shallow fetch). Rebuilding is the only safe answer — a retag would copy an
# image forward that nothing verified.
if [ -z "$BASE" ] || ! git rev-parse --verify --quiet "${BASE}^{commit}" >/dev/null 2>&1; then
  everything
  exit 0
fi

FILES="$(git diff --name-only "$BASE" "$HEAD")"

# ── everything-rebuilds triggers ────────────────────────────────────────────────────────────
if printf '%s\n' "$FILES" | grep -qE '^(common/|deploy/Dockerfile$|\.dockerignore$)'; then
  everything
  exit 0
fi

BUILD=()
RETAG=()
for svc in $SERVICES; do
  if printf '%s\n' "$FILES" | grep -qE "^${svc}/"; then BUILD+=("$svc"); else RETAG+=("$svc"); fi
done

INFRA=false
if printf '%s\n' "$FILES" | grep -qE '^(deploy/nginx/|elk/|keycloak/|deploy/bootstrap-elk\.sh$)'; then
  INFRA=true
fi

COMPOSE=false
if printf '%s\n' "$FILES" | grep -qE '^deploy/docker-compose\.prod\.yml$'; then
  COMPOSE=true
fi

emit false "$INFRA" "$COMPOSE" ${BUILD[@]+"${BUILD[@]}"} -- ${RETAG[@]+"${RETAG[@]}"}