#!/usr/bin/env bash
#
# One-time setup for the ELK stack. Idempotent — safe to re-run.
#
#   1. ILM policy      — the built-in `logs` policy rolls over but never deletes
#   2. Index template  — priority 200, so it beats the built-in logs-*-* template
#   3. Kibana data view — WITHOUT THIS, DISCOVER SHOWS NOTHING even though the index has data
#
# Development (ports published on the host):
#   elk/setup.sh                      after `docker compose --profile logging up -d`
#
# Production (nothing published; called for you by deploy/bootstrap-elk.sh):
#   RELAY_ENV=prod ES_CURL_AUTH=elastic:… KIBANA_CURL_AUTH=elastic:… \
#   ES_EXEC='docker compose … exec -T elasticsearch' KIBANA_EXEC="$ES_EXEC" \
#   KIBANA=http://kibana:5601 elk/setup.sh
#
# The *_EXEC variables are the whole difference between the two: in prod neither 9200 nor 5601 is
# reachable from the host, so every call runs curl from inside the container instead. JSON bodies
# are piped on stdin rather than passed as @file for the same reason — the files are not in there.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RELAY_ENV="${RELAY_ENV:-dev}"
ES="${ES:-http://localhost:9200}"
KIBANA="${KIBANA:-http://localhost:5601}"
ES_EXEC="${ES_EXEC:-}"
KIBANA_EXEC="${KIBANA_EXEC:-}"
ES_CURL_AUTH="${ES_CURL_AUTH:-}"
KIBANA_CURL_AUTH="${KIBANA_CURL_AUTH:-}"

PATTERN="logs-relay.app-*"
ILM_NAME="relay-${RELAY_ENV}-logs"
ILM_FILE="$ROOT/elk/es/ilm-relay-${RELAY_ENV}.json"

say()  { printf '  %s\n' "$*"; }
step() { printf '==> %s\n' "$*"; }

[ -f "$ILM_FILE" ] || { say "no ILM policy for RELAY_ENV=$RELAY_ENV (expected $ILM_FILE)"; exit 1; }

# curl against Elasticsearch, either directly or inside the container. Body, if any, on stdin —
# `docker compose exec -T` forwards it, and the function does not touch stdin itself.
es() {
  [ -n "$ES_CURL_AUTH" ] && set -- -u "$ES_CURL_AUTH" "$@"
  if [ -n "$ES_EXEC" ]; then
    $ES_EXEC curl -fsS "$@"
  else
    curl -fsS "$@"
  fi
}

# Same, for Kibana. Its API requires authentication once Elasticsearch security is on, and the
# calls are issued from the Elasticsearch container in production — Kibana's own image is not
# guaranteed to carry curl, and both sit on the same bridge anyway.
kb() {
  [ -n "$KIBANA_CURL_AUTH" ] && set -- -u "$KIBANA_CURL_AUTH" "$@"
  if [ -n "$KIBANA_EXEC" ]; then
    $KIBANA_EXEC curl -fsS "$@"
  else
    curl -fsS "$@"
  fi
}

step "Waiting for Elasticsearch at $ES"
waited=0
until es "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; do
  waited=$((waited + 5))
  [ "$waited" -gt 180 ] && { say "Elasticsearch did not become ready"; exit 1; }
  sleep 5
done
say "ready"

step "ILM policy $ILM_NAME"
es -XPUT "$ES/_ilm/policy/$ILM_NAME" -H 'Content-Type: application/json' --data-binary @- \
  < "$ILM_FILE" >/dev/null && say "applied"

step "Index template logs-relay.app (lifecycle -> $ILM_NAME)"
# The template file names the dev policy; point it at this environment's policy on the way past
# rather than keeping two near-identical templates in the repo.
sed "s/\"relay-dev-logs\"/\"$ILM_NAME\"/" "$ROOT/elk/es/index-template-relay.json" \
  | es -XPUT "$ES/_index_template/logs-relay.app" -H 'Content-Type: application/json' \
       --data-binary @- >/dev/null && say "applied"

step "Waiting for Kibana at $KIBANA"
waited=0
until kb "$KIBANA/api/status" 2>/dev/null | grep -q '"level":"available"'; do
  waited=$((waited + 5))
  [ "$waited" -gt 240 ] && { say "Kibana did not become available"; exit 1; }
  sleep 5
done
say "ready"

step "Kibana data view $PATTERN"
# Without a data view, Discover has nothing to query and the UI looks empty even with a full index.
existing="$(kb "$KIBANA/api/data_views" -H 'kbn-xsrf: true' 2>/dev/null \
  | tr ',' '\n' | grep -c "$PATTERN" || true)"
if [ "${existing:-0}" -gt 0 ]; then
  say "already exists — leaving it alone"
else
  kb -X POST "$KIBANA/api/data_views/data_view" \
    -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
    -d "{\"data_view\":{\"title\":\"$PATTERN\",\"name\":\"Relay logs\",\"timeFieldName\":\"@timestamp\"}}" \
    >/dev/null && say "created"
fi

printf '\n'
step "Done"
say "Environment: $RELAY_ENV (retention: $(grep -o '"min_age": *"[^"]*"' "$ILM_FILE" | head -1 | sed 's/.*"\([^"]*\)"$/\1/'))"
say "Docs:        $(es "$ES/$PATTERN/_count" 2>/dev/null | sed -E 's/.*"count":([0-9]+).*/\1/') in $PATTERN"
say "Set the time range wide (e.g. Last 24 hours) — idle services log very little,"
say "and only INFO and above is shipped (logging.threshold.file)."
