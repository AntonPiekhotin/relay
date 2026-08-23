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

## Oracle Cloud (one instance, infra included)

**Yes — all of it fits on one instance, and on the free tier.** Ampere A1 Always Free is 4 OCPU
and 24 GB across your instances; give it all to one and the whole stack fits with room over. The
declared `mem_limit`s total ~13 GB, the five Postgres containers are unbounded and settle around
1 GB, and the host wants the rest.

| Shape | Verdict |
|---|---|
| **VM.Standard.A1.Flex, 4 OCPU / 24 GB** | what to use. Always Free, arm64. |
| VM.Standard.A1.Flex, 2 OCPU / 12 GB | app services + infra, but not ELK. |
| VM.Standard.E2.1.Micro (the other free shape) | 1 GB. Not even Keycloak. |

Ampere is **arm64**, which is the one thing that actually changes: `deploy.yml` builds
`linux/arm64,linux/amd64` and every third-party image in the compose file has an arm64 manifest —
Postgres, Kafka, Keycloak, coturn, LiveKit, nginx, certbot and all four Elastic images. The jars
themselves are architecture-independent, so cross-building costs seconds of QEMU, not a rebuild.

Instance setup:

- **Ubuntu 24.04 (aarch64)** or Oracle Linux 9. Either works; the bootstrap script handles both.
- **Boot volume 200 GB**, not the 47 GB default — Always Free covers 200 GB of block storage
  total, and five Postgres plus Kafka plus an Elasticsearch index will use it. Resizing in the
  console does not grow the filesystem; `oci-growfs` does, and the script runs it.
- **Reserve the public IP** before anything else. An ephemeral IP changes on every stop/start and
  it is baked into DNS, coturn's `--external-ip` and LiveKit's `node_ip`.
- Expect `Out of host capacity` on A1. It is capacity, not your account — retry, try another
  availability domain, or upgrade to pay-as-you-go, which keeps the Always Free resources and
  competes better for them.

```bash
sudo deploy/oci-bootstrap.sh     # docker, swap, sysctl, host firewall — idempotent
```

That covers three traps worth naming, because two of them fail silently:

- **The host firewall.** Both OCI images ship one that rejects everything but ssh, so the VCN
  security list is only half the job. And the half that fails is invisible: nginx and LiveKit are
  bridge-published, so Docker DNATs them through `FORWARD` where its own rules sit above the
  distro's `REJECT` — they work. coturn is `network_mode: host`, so its packets hit `INPUT` and
  get dropped. The symptom is exactly what smoke-test step 5 exists to catch: calls connect and
  there is no audio over mobile data.
- **`vm.max_map_count`.** Elasticsearch refuses to start below 262144 and reports it as a failed
  bootstrap check, not as a missing sysctl.
- **No swap.** OCI images have none, so an OOM kill takes a container instead of a page-out.

### Networking

In the Create Instance wizard: a **public subnet** (the VCN wizard's "VCN with Internet
Connectivity" builds the internet gateway and route table for you), **assign a public IPv4
address**, and paste an ssh public key. Nothing else in that panel matters.

The private address on the instance's NIC is correct and expected — OCI does 1:1 NAT, which is
exactly why `PUBLIC_IP` is a separate setting that coturn's `--external-ip` and LiveKit's
`node_ip` both read rather than something either could discover.

**Make the public IP reserved**, which the wizard cannot do: after launch, Instance → Attached
VNICs → primary VNIC → IPv4 Addresses → edit → Ephemeral → Reserved. An ephemeral IP is released
on every stop/start, and it is baked into five DNS records, a TLS cert, and `PUBLIC_IP`.

Then the ingress rules — a Network Security Group attached to the VNIC, or the subnet's default
security list. Stateful (the default; leave "stateless" unchecked), source `0.0.0.0/0`:

| Proto | Port | Serves | Fails as |
|---|---|---|---|
| TCP | 22 | ssh, and the deploy job | see below |
| TCP | 80 | ACME http-01 + the redirect to 443 | certbot cannot issue |
| TCP | 443 | all four vhosts: `api.` (REST + `/ws`), `auth.`, `sfu.` (LiveKit signaling), `logs.` | nothing works |
| TCP | 3478 | TURN over TCP | — |
| UDP | **3478** | STUN + TURN. The one that matters. | 1:1 calls fail off-LAN |
| TCP | 5349 | TURN over TLS | networks that block 3478 fail |
| UDP | 5349 | TURN over DTLS | — |
| UDP | **49160-49200** | coturn's relay range | ICE completes, no audio |
| TCP | 7881 | LiveKit media, TCP fallback | group calls fail behind strict NAT |
| UDP | **7882** | LiveKit media | group calls have no audio |

Egress: leave the default allow-all. Certbot, GHCR pulls, FCM and APNs all need it.

There is no rule for 7880 (LiveKit signaling goes through nginx so clients get `wss://`) and none
for Kibana, Elasticsearch, Kafka, Redis or any Postgres — they have no host ports at all. Kibana
is a vhost on 443, narrowed to `ADMIN_ALLOW_CIDR` by nginx rather than by a security rule.

**Port 22 is open to the internet, and that follows from the deploy design.** The deploy job ssh's
in from GitHub-hosted runners, whose address ranges are large and change; they cannot be pinned in
a security rule. OCI images are already key-only (`PasswordAuthentication no`), which is most of
the mitigation. If that is not enough, the options are a self-hosted runner on the box, a Tailscale
or OCI Bastion path, or switching the deploy job to have the server pull instead of being pushed
to. Narrowing the CIDR is not one of them.

Ten rules is tedious and easy to fat-finger. With the OCI CLI configured, against an NSG —
`nsg rules add` appends, where `security-list update` would *replace* every rule the list already
has, ssh included:

```bash
NSG=ocid1.networksecuritygroup.oc1...
rule() { printf '{"direction":"INGRESS","protocol":"%s","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,"%sOptions":{"destinationPortRange":{"min":%s,"max":%s}}}\n' "$1" "$2" "$3" "$4"; }
{ rule 6 tcp 80 80;      rule 6 tcp 443 443
  rule 6 tcp 3478 3478;  rule 17 udp 3478 3478
  rule 6 tcp 5349 5349;  rule 17 udp 5349 5349
  rule 17 udp 49160 49200
  rule 6 tcp 7881 7881;  rule 17 udp 7882 7882
} | paste -sd, - | sed 's/^/[/; s/$/]/' > /tmp/rules.json
oci network nsg rules add --nsg-id "$NSG" --security-rules file:///tmp/rules.json
```

Protocol 6 is TCP, 17 is UDP. Check the result in the console afterwards — a rule that silently
did not land looks identical to a working one until a call drops its audio.

Both layers must allow every port above: these rules are the *cloud* firewall, and
`oci-bootstrap.sh` handles the *host* one. Opening only one is the failure described above.

Last, five A records at the reserved IP — `api.` `auth.` `sfu.` `logs.` `turn.` — then
**First deploy** below.

Two OCI-specific notes once it is running. Free-tier egress is 10 TB/month, and a TURN-relayed
call is roughly 60 kB/s each way — the relay is the only thing here that moves real volume.
And an Always Free instance can be **reclaimed while idle**; the load this stack idles at is
above that bar, but a stopped instance is not.

---

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

`deploy/remote-apply.sh` does the three lines above for a *subset* of services, waits on the
healthchecks compose already defines, and rolls back to the previous tag if one does not come up:

```bash
deploy/remote-apply.sh --tag v12 message call     # restart two services, leave six alone
deploy/remote-apply.sh --tag v12 --infra          # reload nginx + ELK config, restart nothing else
deploy/remote-apply.sh --tag v12 --full           # recreate everything (drops live sockets)
```

Naming the services is not an optimisation, it is the only way to avoid a full restart: compose
hashes the resolved `image:` string into its recreate decision, so bumping `RELAY_IMAGE_TAG` marks
all eight as changed even where the digest is identical. The cost is that containers you did not
name keep an older tag in their config hash, so the *next* `--full` restarts them once with
identical bits.

---

## CI/CD (GitHub Actions)

Two workflows, both driven by `scripts/changed-services.sh`:

| | Trigger | Does |
|---|---|---|
| `.github/workflows/ci.yml` | PRs, pushes to any branch but `main` | builds + runs the test suites of the services that branch touched. No images, no registry. |
| `.github/workflows/deploy.yml` | push to `main`, or manual | builds and pushes images for the changed services, copies the rest forward, restarts only the changed ones on the box. |

**What "changed" means on a deploy.** The base is the tag in `deploy/.env` *on the server*, read
over ssh — "everything since what is actually running", not the push range. A run that was
skipped, cancelled or failed therefore cannot lose commits. A change under `common/` or to
`deploy/Dockerfile` rebuilds all eight, since every service embeds `common` as a published
artifact.

**Why unchanged services still get a new tag.** The server runs one `RELAY_IMAGE_TAG` for all
eight images, so every tag must be a complete set or the next `compose pull` 404s. The `retag`
job copies unchanged services forward with `docker buildx imagetools create` — a manifest write,
no layers move — instead of rebuilding them.

Manual runs (**Actions → deploy → Run workflow**) take three switches: `force_all` (ignore change
detection and rebuild everything — also the escape hatch when the registry has no image at the
base tag), `full_recreate` (recreate every container), `skip_tests`.

### One-time setup

**On GitHub** — Settings → Secrets and variables → Actions:

| Secret | |
|---|---|
| `DEPLOY_SSH_KEY` | private half of a keypair whose public half is in the deploy user's `authorized_keys` |
| `DEPLOY_HOST` | the server |
| `DEPLOY_USER` | the user that owns `/opt/relay` and is in the `docker` group |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan <host>`. Pinned deliberately — the alternative hands the deploy key to whatever answers on port 22 |

| Variable | Default | |
|---|---|---|
| `DEPLOY_PATH` | `/opt/relay` | the repo checkout on the server |
| `DEPLOY_SSH_PORT` | `22` | |

Then create a `production` environment (Settings → Environments). It is referenced already; adding
a **required reviewer** to it turns the pipeline into push-to-build / click-to-deploy without
editing a workflow.

**On the server**, once:

```bash
git clone https://github.com/<you>/relay.git /opt/relay && cd /opt/relay
# ...then the "First deploy" steps above, with these two lines in deploy/.env:
#   RELAY_REGISTRY=ghcr.io/<you-lowercased>
#   RELAY_IMAGE_TAG=<a real short sha, not `latest` — it is the diff base>
echo <a-ghcr-PAT-with-read:packages> | docker login ghcr.io -u <you> --password-stdin
```

The GHCR packages are private on first push, so that `docker login` is what lets `compose pull`
work. Make the packages public instead and it becomes optional.

The workflow leaves the server's checkout **detached at the deployed commit** — that is the point,
not a mistake: the compose file, nginx config and `remote-apply.sh` that ran are exactly the ones
CI built against. `git log -1` on the box tells you what is deployed.

### What CI does not cover

- **Keycloak realm changes.** `--import-realm` skips an existing realm; see the section below.
  Nothing in the pipeline applies one.
- **Database migrations** run on service startup (Flyway), so they ship with the image. A
  migration that fails leaves the service unhealthy and `remote-apply.sh` rolls the *image* back —
  it cannot roll a migration back. Keep them additive.
- **`docker-compose.prod.yml` changes.** The plan job flags them in the run summary and deploys
  the changed services anyway; applying the rest needs a deliberate `full_recreate`.
- **The smoke test below**, especially step 5. No pipeline can place a call over a mobile network.

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
