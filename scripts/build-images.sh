#!/usr/bin/env bash
#
# Build a container image for every Relay service.
#
# There is no root Gradle build, and `common` is consumed from ~/.m2 rather than as a project
# dependency, so the ordering below is not optional: publish common, then build each service's
# bootJar against it, then wrap the jars in images. Skipping the publish is the classic way to
# ship eight images that all embed a stale `common`.
#
# Usage:
#   scripts/build-images.sh [--tag TAG] [--skip-build] [--push] [--registry REG] [--only "a b"]
#
#   --tag TAG       image tag (default: the short git sha, or `dev` outside a repo)
#   --skip-build    reuse the jars already in <service>/build/libs
#   --push          docker push each image after building
#   --registry REG  image name prefix, e.g. ghcr.io/you  (default: relay)
#   --only LIST     space-separated subset of services
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES="eureka api-gateway auth user message notification websocket-gateway call"
VERSION="0.0.1-SNAPSHOT"

REGISTRY="relay"
TAG=""
SKIP_BUILD=0
PUSH=0

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_BOLD=$'\033[1m'
else
  C_RESET=; C_RED=; C_GREEN=; C_BOLD=
fi
step() { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '  %sok%s   %s\n' "$C_GREEN" "$C_RESET" "$*"; }
die()  { printf '  %sfail%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)        shift; TAG="${1:-}" ;;
    --registry)   shift; REGISTRY="${1:-}" ;;
    --only)       shift; SERVICES="${1:-}" ;;
    --skip-build) SKIP_BUILD=1 ;;
    --push)       PUSH=1 ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *)            die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

if [ -z "$TAG" ]; then
  TAG="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"
fi

command -v docker >/dev/null || die "docker not on PATH"

if [ "$SKIP_BUILD" -eq 0 ]; then
  step "publishing common to ~/.m2 (every service resolves com.relay:common:1.0 from there)"
  (cd "$ROOT/common" && ./gradlew --quiet publishToMavenLocal) || die "common publish failed"
  ok "common published"

  for svc in $SERVICES; do
    step "bootJar $svc"
    (cd "$ROOT/$svc" && ./gradlew --quiet bootJar -x test) || die "$svc bootJar failed"
    ok "$svc"
  done
fi

for svc in $SERVICES; do
  jar="$ROOT/$svc/build/libs/$svc-$VERSION.jar"
  [ -f "$jar" ] || die "missing $jar — drop --skip-build"

  image="$REGISTRY/$svc:$TAG"
  step "image $image"
  docker build \
    --file "$ROOT/deploy/Dockerfile" \
    --build-arg "SERVICE=$svc" \
    --build-arg "VERSION=$VERSION" \
    --tag "$image" \
    --tag "$REGISTRY/$svc:latest" \
    "$ROOT" || die "$svc image build failed"
  ok "$image"

  if [ "$PUSH" -eq 1 ]; then
    docker push "$image" || die "push $image failed"
    docker push "$REGISTRY/$svc:latest" || die "push $REGISTRY/$svc:latest failed"
    ok "pushed $image"
  fi
done

printf '\n%sBuilt tag %s%s — set RELAY_IMAGE_TAG=%s in deploy/.env\n' "$C_BOLD" "$TAG" "$C_RESET" "$TAG"
