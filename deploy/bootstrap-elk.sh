#!/usr/bin/env bash
#
# One-time Elasticsearch security bootstrap, then the usual index setup.
#
# ELASTIC_PASSWORD seeds only the `elastic` superuser at cluster bootstrap. Kibana logs in as
# `kibana_system`, whose password can only be set through the security API once the cluster is
# up — which is why this is a script and not an environment variable. Kibana crash-loops until
# it has been run; that is expected on a first deploy, not a fault.
#
# Idempotent. Safe to re-run, and re-running is how you rotate KIBANA_SYSTEM_PASSWORD.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
COMPOSE="docker compose -f $HERE/docker-compose.prod.yml --env-file $HERE/.env"

# shellcheck disable=SC1091
set -a; . "$HERE/.env"; set +a
: "${ELASTIC_PASSWORD:?set ELASTIC_PASSWORD in deploy/.env}"
: "${KIBANA_SYSTEM_PASSWORD:?set KIBANA_SYSTEM_PASSWORD in deploy/.env}"

es() { $COMPOSE exec -T elasticsearch curl -fsS -u "elastic:$ELASTIC_PASSWORD" "$@"; }

echo "==> waiting for Elasticsearch"
for _ in $(seq 1 60); do
  es "http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1 && break
  sleep 5
done
es "http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null \
  || { echo "Elasticsearch never became ready" >&2; exit 1; }
echo "    ready"

echo "==> setting the kibana_system password"
es -XPOST "http://localhost:9200/_security/user/kibana_system/_password" \
   -H 'Content-Type: application/json' \
   -d "{\"password\":\"$KIBANA_SYSTEM_PASSWORD\"}" >/dev/null
echo "    set"

echo "==> restarting Kibana so it picks the credentials up"
$COMPOSE up -d kibana

echo "==> index template, ILM policy and Kibana data view"
# Neither 9200 nor 5601 is published in production, so every call runs curl from inside the
# Elasticsearch container — including the Kibana ones, which reach it by service name on the
# bridge. Kibana's own image is not guaranteed to ship curl.
RELAY_ENV=prod \
ES="http://localhost:9200" \
KIBANA="http://kibana:5601" \
ES_CURL_AUTH="elastic:$ELASTIC_PASSWORD" \
KIBANA_CURL_AUTH="elastic:$ELASTIC_PASSWORD" \
ES_EXEC="$COMPOSE exec -T elasticsearch" \
KIBANA_EXEC="$COMPOSE exec -T elasticsearch" \
  "$ROOT/elk/setup.sh"

echo
echo "Done. Kibana is not published — reach it over an ssh tunnel:"
echo "  ssh -L 5601:localhost:5601 <box>   then open http://localhost:5601/app/discover"
echo "  (log in as elastic)"
