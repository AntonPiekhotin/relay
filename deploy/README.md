# Deploying Relay

Single host, Docker Compose, nginx terminating TLS. Everything here lives in `deploy/`; the
repo-root `docker-compose.yml` stays what it is — a laptop's infrastructure, with the services
running as host JVMs under `scripts/start-all.sh`.

**Why a separate compose file rather than an override.** Compose *merges* `ports:` lists across
files and gives you no way to remove an entry. The dev file publishes Postgres, Kafka and Redis
to the host. Hardening by layering is therefore impossible — only by replacing.

---

## What runs where

| | Published | Reachable how |
|---|---|---|
| nginx | 80, 443 | the only HTTP entry point |
| coturn | 3478 tcp+udp, 5349 tcp+udp, 49160-49200/udp | host networking, direct |
| livekit | 7881 tcp, 7882 udp | direct — media cannot be proxied |
| everything else | nothing | the `relay-network` bridge only |

LiveKit's signaling port 7880 is *not* published: it goes through nginx so clients get `wss://`.
Kibana, Elasticsearch, Kafka, Redis and all five Postgres instances have no host ports at all.

## Requirements

- **16 GB RAM / 4 vCPU.** Eight JVMs ≈ 3 GB, Kafka 1.5 GB, Elasticsearch 1.5 GB, Kibana 1 GB,
  Keycloak 1 GB, five Postgres ≈ 1 GB, the rest ≈ 1 GB. 8 GB only works with the four logging
  containers removed.
- **Docker Engine on Linux.** `coturn` uses `network_mode: host`, which Docker Desktop does not
  implement the same way.
- **Five DNS A records**, all pointing at the box: `api.`, `auth.`, `sfu.`, `logs.`, `turn.`
- **Firewall**: allow 80/tcp, 443/tcp, 3478/tcp+udp, 5349/tcp+udp, 49160-49200/udp, 7881/tcp,
  7882/udp. Deny everything else inbound.

## First deploy

```bash
# 1. Build and push images (from a machine with the JDK — the server needs neither Java nor Gradle)
scripts/build-images.sh --registry ghcr.io/<you> --push
#    → prints a tag; put it in RELAY_IMAGE_TAG below

# 2. On the server
cp deploy/.env.example deploy/.env && chmod 600 deploy/.env
$EDITOR deploy/.env                       # every blank must be filled; see "Secrets" below
mkdir -p secrets && cp <your>-fcm.json secrets/fcm.json

# 3. TLS. --staging first: the production CA allows five failed authorisations per hostname per
#    hour, and one wrong A record burns them all.
deploy/init-letsencrypt.sh --staging
deploy/init-letsencrypt.sh

# 4. Bring it up
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d

# 5. Elasticsearch security bootstrap. Kibana crash-loops until this runs — that is expected,
#    not a fault: kibana_system's password can only be set through the security API.
deploy/bootstrap-elk.sh
```

Then rotate the Keycloak client secret — step 1 of "Secrets" below — and run the smoke test.

## Redeploying

```bash
scripts/build-images.sh --registry ghcr.io/<you> --push --tag v12
sed -i 's/^RELAY_IMAGE_TAG=.*/RELAY_IMAGE_TAG=v12/' deploy/.env
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
```

Rolling back is the same three lines with the previous tag, which is the entire reason
`RELAY_IMAGE_TAG=latest` is a bad idea in `.env` even though it works.

**Restarting `websocket-gateway` disconnects every live client.** There is one node and there can
only be one (ARCHITECTURE.md §6 — presence is decided from an in-memory registry, so a second node
would declare users connected elsewhere offline). Clients reconnect and catch up over REST, so it
is survivable, not invisible. Deploy it when traffic is low.

## Secrets

Everything is in `deploy/.env`, which is gitignored and must be `chmod 600`. Nothing is baked into
an image; FCM's service-account JSON and the APNs `.p8` are bind-mounted read-only at
`/run/secrets`.

**Rotate the Keycloak client secret before you accept a single real user.** The value in
`auth/src/main/resources/application.yaml` is committed to this repository, and before the logging
levels were capped it was also printed verbatim into log files by `org.apache.http.headers`, along
with every bearer and refresh token that passed through. Treat it as public:

```
Keycloak admin console → clients → relay-client → credentials → regenerate
→ copy into KEYCLOAK_CLIENT_SECRET in deploy/.env → up -d auth
```

The admin console is deliberately not exposed — nginx returns 404 for `/admin/` on the auth host.
Reach it over a tunnel: `ssh -L 8081:localhost:8080 <box>` after
`docker compose … exec keycloak …`, or temporarily publish the port.

## Keycloak realm changes

`--import-realm` **skips a realm that already exists**. It seeds an empty database on the very
first boot and does nothing on every boot after that. It is not a deployment mechanism: a change
to `keycloak/import/relay-realm.json` will not appear on the server. Either apply the change in
the admin console and re-export, or drop the realm and re-import deliberately.

The committed realm also has `"sslRequired": "none"`. Set it to `external` once you are behind
nginx — otherwise Keycloak will happily serve the token endpoint over plain HTTP if anything ever
reaches it directly.

## Backups

Volume snapshots of a running Postgres are not backups. Logical dumps, off the box:

```cron
0 3 * * *  cd /opt/relay && for db in user message notification call; do \
  docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T $db-db \
    pg_dump -U relay_$db $db | gzip > /backup/$db-$(date +\%F).sql.gz; done
```

Kafka is not backed up on purpose: the database is the source of truth (ARCHITECTURE.md
principle 1) and the topics are a transport. Losing the broker's disk loses in-flight events, not
messages.

## Logs

`https://logs.<domain>`, restricted to `ADMIN_ALLOW_CIDR` at nginx and behind Elasticsearch's own
authentication (log in as `elastic`). Retention is 30 days (`elk/es/ilm-relay-prod.json`); dev is
3. Only INFO and above is shipped — `logging.threshold.file` — so a *successful* request produces
no records at all. That is the usual reason Discover looks empty, along with too narrow a time
range and a missing data view.

The console stream stays at INFO here too (`LOGGING_THRESHOLD_CONSOLE`), readable with
`docker compose … logs -f <service>` and capped at 3 × 20 MB per container.

Services write ECS JSON into the shared `relay-logs` volume, which filebeat mounts read-only at
the path the dev globs already expect — so `elk/filebeat/filebeat.yml` and the Logstash pipeline
are the same files in both environments. Only `RELAY_ENV`, the data-stream namespace and the
Elasticsearch credentials differ, and all three come from the environment.

## Smoke test

In order, because each step depends on the one before:

1. `curl https://api.<domain>/api/v1/auth/...` — register, then log in.
2. Connect the socket to `wss://api.<domain>/ws`, send a message, confirm the ack.
3. Fetch history and the dialog list.
4. Push to a real device.
5. **A 1:1 call from a mobile network, not from your wifi.** This is the only test that exercises
   coturn's `--external-ip` and the relay port range. On the same LAN the peers find each other
   directly and a completely broken TURN setup looks fine.
6. A group call, then `docker compose … stop livekit` mid-call and confirm the reconcile sweep
   ends it.

## Known limits, accepted deliberately

- **One `websocket-gateway`.** See above and ARCHITECTURE.md §6. Redis is provisioned and
  password-protected but nothing connects to it yet.
- **Single Kafka broker, replication factor 1.** Broker disk loss = event loss. Acceptable only
  because the database is the source of truth.
- **No Kafka DLT or error handler.** Listeners catch and log; a transient failure is dropped like
  a permanent one.
- **No metrics and no tracing.** `/actuator/health` is the only endpoint exposed, purely so
  compose has something to probe. `trace.id` groups log records; it does not time anything.
- **coturn does not reload its certificate.** Add a host cron for the week after renewal:
  `0 4 * * 1 cd /opt/relay && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env restart coturn`
- **No REST fallback for sending.** The socket is the only send path (PROTOCOL.md §5.2 describes
  an endpoint that does not exist).
