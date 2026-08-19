#!/usr/bin/env bash
#
# One-time setup for the local ELK stack. Idempotent — safe to re-run.
#
#   1. ILM policy      — the built-in `logs` policy rolls over but never deletes
#   2. Index template  — priority 200, so it beats the built-in logs-*-* template
#   3. Kibana data view — WITHOUT THIS, DISCOVER SHOWS NOTHING even though the index has data
#
# Usage: elk/setup.sh        (after `docker compose --profile logging up -d`)
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ES="${ES:-http://localhost:9200}"
KIBANA="${KIBANA:-http://localhost:5601}"
PATTERN="logs-relay.app-*"

say()  { printf '  %s\n' "$*"; }
step() { printf '==> %s\n' "$*"; }

step "Waiting for Elasticsearch at $ES"
waited=0
until curl -fsS "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; do
  waited=$((waited + 5))
  [ "$waited" -gt 180 ] && { say "Elasticsearch did not become ready — is the 'logging' profile up?"; exit 1; }
  sleep 5
done
say "ready"

step "ILM policy relay-dev-logs (3-day retention)"
curl -fsS -XPUT "$ES/_ilm/policy/relay-dev-logs" -H 'Content-Type: application/json' \
  --data-binary "@$ROOT/elk/es/ilm-relay-dev.json" >/dev/null && say "applied"

step "Index template logs-relay.app"
curl -fsS -XPUT "$ES/_index_template/logs-relay.app" -H 'Content-Type: application/json' \
  --data-binary "@$ROOT/elk/es/index-template-relay.json" >/dev/null && say "applied"

step "Waiting for Kibana at $KIBANA"
waited=0
until curl -fsS "$KIBANA/api/status" 2>/dev/null | grep -q '"level":"available"'; do
  waited=$((waited + 5))
  [ "$waited" -gt 240 ] && { say "Kibana did not become available"; exit 1; }
  sleep 5
done
say "ready"

step "Kibana data view $PATTERN"
# Without a data view, Discover has nothing to query and the UI looks empty even with a full index.
existing="$(curl -fsS "$KIBANA/api/data_views" -H 'kbn-xsrf: true' 2>/dev/null \
  | tr ',' '\n' | grep -c "$PATTERN" || true)"
if [ "${existing:-0}" -gt 0 ]; then
  say "already exists — leaving it alone"
else
  curl -fsS -X POST "$KIBANA/api/data_views/data_view" \
    -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
    -d "{\"data_view\":{\"title\":\"$PATTERN\",\"name\":\"Relay logs\",\"timeFieldName\":\"@timestamp\"}}" \
    >/dev/null && say "created"
fi

printf '\n'
step "Done"
say "Kibana:   $KIBANA/app/discover"
say "Docs:     $(curl -fsS "$ES/$PATTERN/_count" 2>/dev/null | sed -E 's/.*"count":([0-9]+).*/\1/') in $PATTERN"
say "Set the time range wide (e.g. Last 24 hours) — idle services log very little,"
say "and only INFO and above is shipped (logging.threshold.file)."
