#!/usr/bin/env bash
#
# Stop the services started by scripts/start-all.sh, in reverse start order
# (eureka last, so the others can deregister first).
#
# Usage: scripts/stop-all.sh [--infra] [--only "user message"]
#   --infra      also `docker compose down` afterwards
#   --only LIST  stop only these services
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT/logs/pids"

ORDER="call websocket-gateway notification message user auth api-gateway eureka"
WITH_INFRA=0
GRACE="${GRACE:-20}"

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'
else
  C_RESET=; C_RED=; C_GREEN=; C_YELLOW=; C_BOLD=
fi
step() { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '  %sok%s   %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '  %swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '  %sfail%s %s\n' "$C_RED" "$C_RESET" "$*"; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --infra)   WITH_INFRA=1 ;;
    --only)    shift; [ $# -gt 0 ] || die "--only needs a service list"; ORDER="$1" ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)         die "unknown option: $1" ;;
  esac
  shift
done

step "Stopping services"
for svc in $ORDER; do
  pidfile="$PID_DIR/$svc.pid"
  [ -f "$pidfile" ] || continue
  pid="$(cat "$pidfile" 2>/dev/null)"
  if [ -z "$pid" ] || ! kill -0 "$pid" >/dev/null 2>&1; then
    rm -f "$pidfile"
    continue
  fi

  kill -TERM "$pid" >/dev/null 2>&1
  waited=0
  while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt "$GRACE" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" >/dev/null 2>&1; then
    kill -KILL "$pid" >/dev/null 2>&1
    warn "$svc (pid $pid) ignored SIGTERM — killed"
  else
    ok "$svc stopped (pid $pid)"
  fi
  rm -f "$pidfile"
done

if [ "$WITH_INFRA" -eq 1 ]; then
  step "Stopping infrastructure (docker compose down)"
  ( cd "$ROOT" && docker compose down ) && ok "compose down"
fi
