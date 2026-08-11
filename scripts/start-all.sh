#!/usr/bin/env bash
#
# Start every Relay service locally, eureka first.
#
# Each service is its own Gradle build, so this builds a bootJar per service and
# runs it as a plain `java -jar` child process — one PID per service, killable by
# scripts/stop-all.sh. eureka must be up before anything else starts, or the rest
# register late and `lb://` lookups fail on the first request.
#
# Anything already up — including instances started from the IDE — is left alone
# rather than started a second time.
#
# Usage:
#   scripts/start-all.sh [--skip-build] [--sequential] [--infra] [--force]
#                        [--only "user message"]
#
#   --skip-build   reuse the jars already in <service>/build/libs
#   --sequential   start the post-eureka services one at a time, waiting for each
#   --infra        `docker compose up -d` first and wait for kafka + the databases
#   --force        start even a service that looks like it is already running
#   --only LIST    space-separated service names (eureka is always started first)
#
# Env: START_TIMEOUT (default 120s per service), JAVA_OPTS (passed to every service),
#      RELAY_JAVA_HOME (JDK to run with; must be >= the toolchain version)
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/logs"
PID_DIR="$LOG_DIR/pids"

# Order matters: eureka, then the discovery-dependent services.
EUREKA="eureka"
SERVICES="api-gateway auth user message notification websocket-gateway call"

EUREKA_HEALTH_URL="http://localhost:8761/eureka/apps"
START_TIMEOUT="${START_TIMEOUT:-120}"
JAVA_OPTS="${JAVA_OPTS:-}"

# Services with a pinned port; the rest use server.port: 0 and are only findable
# through the eureka registry.
FIXED_PORTS="eureka:8761 api-gateway:8080 websocket-gateway:8083"

SKIP_BUILD=0
SEQUENTIAL=0
WITH_INFRA=0
FORCE=0

# --- pretty output ---------------------------------------------------------
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'
else
  C_RESET=; C_DIM=; C_RED=; C_GREEN=; C_YELLOW=; C_BOLD=
fi
info()  { printf '%s\n' "$*"; }
step()  { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()    { printf '  %sok%s   %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '  %swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
fail()  { printf '  %sfail%s %s\n' "$C_RED" "$C_RESET" "$*"; }
die()   { fail "$*"; exit 1; }

usage() { sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --sequential) SEQUENTIAL=1 ;;
    --infra)      WITH_INFRA=1 ;;
    --force)      FORCE=1 ;;
    --only)       shift; [ $# -gt 0 ] || die "--only needs a service list"; SERVICES="$1" ;;
    -h|--help)    usage ;;
    *)            die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

# eureka is started by its own step; never double-start it from the list.
SERVICES="$(printf '%s\n' $SERVICES | grep -v "^${EUREKA}$" | tr '\n' ' ')"

mkdir -p "$LOG_DIR" "$PID_DIR"

# --- helpers --------------------------------------------------------------
port_open() { # host port
  nc -z "$1" "$2" >/dev/null 2>&1
}

running_pid() { # service -> echoes pid if a recorded process is still alive
  local pidfile="$PID_DIR/$1.pid" pid
  [ -f "$pidfile" ] || return 1
  pid="$(cat "$pidfile" 2>/dev/null)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" >/dev/null 2>&1 || return 1
  printf '%s' "$pid"
}

# The services are built against a Java 25 toolchain, so `java` on PATH is often
# too old to run the jars (it fails with UnsupportedClassVersionError). Find a
# runtime new enough, preferring RELAY_JAVA_HOME if the caller set one.
MIN_JAVA=25
java_major() { # path-to-java -> echoes major version
  "$1" -version 2>&1 | head -1 | sed -E 's/^[^"]*"([0-9]+).*/\1/'
}

resolve_java() {
  local candidates="" c major mac_home
  [ -n "${RELAY_JAVA_HOME:-}" ] && candidates="$candidates$RELAY_JAVA_HOME/bin/java"$'\n'
  if [ -x /usr/libexec/java_home ]; then
    mac_home="$(/usr/libexec/java_home -v "$MIN_JAVA" 2>/dev/null)"
    [ -n "$mac_home" ] && candidates="$candidates$mac_home/bin/java"$'\n'
  fi
  [ -n "${JAVA_HOME:-}" ] && candidates="$candidates$JAVA_HOME/bin/java"$'\n'
  candidates="$candidates$(command -v java 2>/dev/null)"

  while IFS= read -r c; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    major="$(java_major "$c")"
    case "$major" in
      ''|*[!0-9]*) continue ;;
    esac
    if [ "$major" -ge "$MIN_JAVA" ]; then
      JAVA_BIN="$c"
      return 0
    fi
  done <<< "$candidates"

  die "no Java >= $MIN_JAVA found (tried RELAY_JAVA_HOME, java_home -v $MIN_JAVA, JAVA_HOME, PATH).
       Install a JDK $MIN_JAVA or point RELAY_JAVA_HOME at one."
}

boot_jar() { # service -> echoes the executable jar (not the -plain one)
  ls "$ROOT/$1"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1
}

fixed_port() { # service -> echoes its pinned port, or fails
  local pair
  for pair in $FIXED_PORTS; do
    if [ "${pair%%:*}" = "$1" ]; then printf '%s' "${pair##*:}"; return 0; fi
  done
  return 1
}

# Started elsewhere (IDE, another shell)? Pinned-port services show up as a bound
# port; random-port ones only as a eureka registration. A registration can linger
# ~90s after an unclean shutdown — that is what --force is for.
already_up() { # service -> 0 and echoes the reason if something already provides it
  local port app
  if port="$(fixed_port "$1")"; then
    port_open localhost "$port" && { printf 'port %s is already serving' "$port"; return 0; }
    return 1
  fi
  app="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  if curl -fsS "$EUREKA_HEALTH_URL" 2>/dev/null | grep -qi "<name>$app</name>"; then
    printf 'already registered in eureka'
    return 0
  fi
  return 1
}

tail_log() { # service — show the tail of a failed service's log
  printf '%s' "$C_DIM"
  tail -n 15 "$LOG_DIR/$1.log" 2>/dev/null | sed 's/^/       /'
  printf '%s' "$C_RESET"
}

# Spring logs the bound port; random-port services (server.port: 0) only reveal
# it here, so surface it in the summary.
service_port() { # service
  grep -oE '(Tomcat|Netty) started on port[s]?:? [0-9]+' "$LOG_DIR/$1.log" 2>/dev/null \
    | tail -1 | grep -oE '[0-9]+$'
}

# --- optional: docker infrastructure -------------------------------------
start_infra() {
  step "Bringing up infrastructure (docker compose)"
  ( cd "$ROOT" && docker compose up -d ) || die "docker compose up failed"

  # host:port pairs the services connect to on boot
  local waits="kafka:9092 keycloak:8081 user-db:5434 notification-db:5435 call-db:5436 message-db:5437"
  local pair name port waited
  for pair in $waits; do
    name="${pair%%:*}"; port="${pair##*:}"
    waited=0
    until port_open localhost "$port"; do
      waited=$((waited + 1))
      if [ "$waited" -gt 60 ]; then warn "$name (:$port) not answering — continuing anyway"; break; fi
      sleep 1
    done
    port_open localhost "$port" && ok "$name listening on :$port"
  done
}

# --- build ----------------------------------------------------------------
build_all() {
  # `common` is consumed from the local maven repo, not as a project dependency —
  # without this republish, every service compiles against a stale copy.
  step "Publishing common to mavenLocal"
  ( cd "$ROOT/common" && ./gradlew publishToMavenLocal -q --console=plain ) \
    || die "common publishToMavenLocal failed"
  ok "common published"

  local svc
  for svc in $EUREKA $SERVICES; do
    step "Building $svc"
    ( cd "$ROOT/$svc" && ./gradlew bootJar -q --console=plain ) || die "$svc bootJar failed"
    ok "$(basename "$(boot_jar "$svc")")"
  done
}

# --- start / wait ---------------------------------------------------------
spawn() { # service -> writes pidfile, echoes pid on stdout (diagnostics go to stderr)
  local svc="$1" jar pid
  jar="$(boot_jar "$svc")"
  [ -n "$jar" ] || { fail "$svc: no bootJar in $svc/build/libs (drop --skip-build)" >&2; return 1; }

  : > "$LOG_DIR/$svc.log"
  # Run from the service directory: some config resolves relative paths against
  # it (notification's FCM credentials, for one).
  ( cd "$ROOT/$svc" && exec nohup "$JAVA_BIN" $JAVA_OPTS -jar "$jar" ) \
    >> "$LOG_DIR/$svc.log" 2>&1 &
  pid=$!
  echo "$pid" > "$PID_DIR/$svc.pid"
  printf '%s' "$pid"
}

wait_for_startup() { # service pid -> 0 up, 1 timeout, 2 died
  local svc="$1" pid="$2" waited=0
  while [ "$waited" -lt "$START_TIMEOUT" ]; do
    # Kotlin main classes log as "Started MessageApplicationKt in 8.4 seconds"
    if grep -qE ': Started [A-Za-z0-9_$]+ in [0-9.]+ seconds' "$LOG_DIR/$svc.log" 2>/dev/null; then
      return 0
    fi
    kill -0 "$pid" >/dev/null 2>&1 || return 2
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

wait_for_eureka() { # pid -> 0 up, 1 timeout, 2 died
  local pid="$1" waited=0
  while [ "$waited" -lt "$START_TIMEOUT" ]; do
    if curl -fsS -o /dev/null "$EUREKA_HEALTH_URL" 2>/dev/null; then return 0; fi
    kill -0 "$pid" >/dev/null 2>&1 || return 2
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

report() { # service status-code pid
  case "$2" in
    0) local port; port="$(service_port "$1")"
       ok "$1 up (pid $3${port:+, port $port})" ;;
    1) fail "$1 did not report startup within ${START_TIMEOUT}s — still running as pid $3"; tail_log "$1" ;;
    2) fail "$1 exited during startup"; tail_log "$1"; rm -f "$PID_DIR/$1.pid" ;;
    *) fail "$1 could not be started" ;;
  esac
}

# --- main -----------------------------------------------------------------
FAILED=""
JAVA_BIN=""

resolve_java
step "Java runtime: $JAVA_BIN ($(java_major "$JAVA_BIN"))"

[ "$WITH_INFRA" -eq 1 ] && start_infra

if [ "$SKIP_BUILD" -eq 1 ]; then
  step "Skipping build (--skip-build)"
else
  build_all
fi

step "Starting $EUREKA (must be up before the rest)"
if pid="$(running_pid "$EUREKA")"; then
  ok "$EUREKA already running (pid $pid)"
elif curl -fsS -o /dev/null "$EUREKA_HEALTH_URL" 2>/dev/null; then
  ok "$EUREKA already answering on :8761 (started outside this script)"
else
  pid="$(spawn "$EUREKA")" || die "could not start $EUREKA"
  wait_for_eureka "$pid"; rc=$?
  report "$EUREKA" "$rc" "$pid"
  [ "$rc" -eq 0 ] || die "$EUREKA is not up — refusing to start the rest (see $LOG_DIR/$EUREKA.log)"
fi

step "Starting services"
PIDS=""   # "svc:pid" pairs, in start order
for svc in $SERVICES; do
  if pid="$(running_pid "$svc")"; then
    ok "$svc already running (pid $pid)"
    continue
  fi
  if [ "$FORCE" -eq 0 ] && reason="$(already_up "$svc")"; then
    ok "$svc left alone — $reason (--force to start another instance)"
    continue
  fi
  if pid="$(spawn "$svc")"; then
    info "  ...   $svc starting (pid $pid)"
    if [ "$SEQUENTIAL" -eq 1 ]; then
      wait_for_startup "$svc" "$pid"; rc=$?
      report "$svc" "$rc" "$pid"
      [ "$rc" -eq 0 ] || FAILED="$FAILED $svc"
    else
      PIDS="$PIDS $svc:$pid"
    fi
  else
    FAILED="$FAILED $svc"
  fi
done

if [ -n "$PIDS" ]; then
  step "Waiting for startup"
  for pair in $PIDS; do
    svc="${pair%%:*}"; pid="${pair##*:}"
    wait_for_startup "$svc" "$pid"; rc=$?
    report "$svc" "$rc" "$pid"
    [ "$rc" -eq 0 ] || FAILED="$FAILED $svc"
  done
fi

step "Summary"
info "  logs: $LOG_DIR/<service>.log     stop: scripts/stop-all.sh"
info "  eureka http://localhost:8761 · api-gateway http://localhost:8080 · websocket-gateway ws://localhost:8083"
if [ -n "${FAILED# }" ]; then
  fail "failed:${FAILED}"
  exit 1
fi
ok "all services up"
