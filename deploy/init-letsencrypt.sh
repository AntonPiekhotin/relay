#!/usr/bin/env bash
#
# One-time TLS bootstrap. Run this ONCE, before the first `docker compose up -d`.
#
# It exists because of a genuine circular dependency: certbot's webroot challenge is served by
# nginx, and nginx will not start without a certificate file to load. So this puts a throwaway
# self-signed certificate in place for each hostname, starts nginx, swaps in real certificates
# from Let's Encrypt, and reloads. After this, the certbot container renews on its own every 12h.
#
# Usage:  deploy/init-letsencrypt.sh [--staging]
#
#   --staging   use Let's Encrypt's staging CA. Do this first: the production CA allows five
#               failed authorisations per hostname per hour, and a typo'd DNS record burns them.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="docker compose -f $HERE/docker-compose.prod.yml --env-file $HERE/.env"

[ -f "$HERE/.env" ] || { echo "deploy/.env missing — copy .env.example and fill it in" >&2; exit 1; }
# shellcheck disable=SC1091
set -a; . "$HERE/.env"; set +a

STAGING_ARG=""
[ "${1:-}" = "--staging" ] && STAGING_ARG="--staging"

HOSTS=$(printf '%s\n' "$API_HOST" "$AUTH_HOST" "$SFU_HOST" "$TURN_HOST" | awk 'NF && !seen[$0]++')
: "${LETSENCRYPT_EMAIL:?set LETSENCRYPT_EMAIL in deploy/.env}"

echo "==> hostnames: $HOSTS"
echo "    every one of these must already resolve to this host, or the challenge fails"

echo "==> placing throwaway self-signed certificates"
for h in $HOSTS; do
  $COMPOSE run --rm --entrypoint sh certbot -c "
    mkdir -p /etc/letsencrypt/live/$h &&
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
      -keyout /etc/letsencrypt/live/$h/privkey.pem \
      -out    /etc/letsencrypt/live/$h/fullchain.pem \
      -subj '/CN=$h' 2>/dev/null"
done

echo "==> starting nginx so it can serve /.well-known/acme-challenge"
# --no-deps: nginx declares depends_on for the gateways and Keycloak, and without this the whole
# stack boots just to answer a challenge.
$COMPOSE up -d --no-deps nginx
sleep 5

echo "==> requesting real certificates"
for h in $HOSTS; do
  # The throwaway has to be deleted first, or certbot sees a valid-looking cert and declines.
  $COMPOSE run --rm --entrypoint sh certbot -c "rm -rf /etc/letsencrypt/live/$h /etc/letsencrypt/archive/$h /etc/letsencrypt/renewal/$h.conf"
  # coturn reads its certificate from this same volume; TURN_HOST gets one for `turns:` on 5349.
  $COMPOSE run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot \
    $STAGING_ARG \
    --email "$LETSENCRYPT_EMAIL" --agree-tos --no-eff-email \
    --non-interactive \
    -d "$h" || { echo "certificate for $h failed — check the A record and port 80" >&2; exit 1; }
done

echo "==> reloading nginx"
$COMPOSE exec nginx nginx -s reload

echo
echo "Done. Now: $COMPOSE up -d"
echo
echo "Note: coturn loads its certificate at start and does not reload it. Add a host cron for"
echo "renewal day:  0 4 * * 1  cd $HERE && docker compose -f docker-compose.prod.yml --env-file .env restart coturn"
