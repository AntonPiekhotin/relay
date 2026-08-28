# Deploying Relay

Single host, Docker Compose, nginx terminating TLS. Everything here lives in `deploy/`; the
repo-root `docker-compose.yml` stays what it is — a laptop's infrastructure, with the services
running as host JVMs under `scripts/start-all.sh`.

**Why a separate compose file rather than an override.** Compose *merges* `ports:` lists across
files and gives you no way to remove an entry. The dev file publishes Postgres, Kafka and Redis
to the host. Hardening by layering is therefore impossible — only by replacing.

---

## Roadmap — read this first

Eight shell scripts live in this repo. They fall into four groups, and **you only ever run three of
them by hand.** Everything else is either a laptop convenience or something CI calls for you.

| | Script | Run it | Why it can't just be a compose command |
|---|---|---|---|
| **Fresh box, once** | `deploy/oci-bootstrap.sh` | `sudo`, on the server | docker install, swap, sysctl, host firewall |
| | `deploy/init-letsencrypt.sh` | on the server, before the first `up` | nginx won't start without a cert; certbot needs nginx to serve the challenge. Circular — something has to break it |
| | `deploy/bootstrap-elk.sh` | on the server, after the first `up` | `kibana_system`'s password can only be set through the security API, once the cluster is live |
| **Every deploy** | `deploy/apply.sh <svc> <tag>` | **CI runs it** | writes one tag, pulls, restarts one container, waits on health, rolls back if it fails |
| **Laptop only** | `scripts/start-all.sh` / `stop-all.sh` | when developing | runs the eight services as host JVMs |
| | `elk/setup.sh` | after `--profile logging up` | Kibana shows nothing without a data view |
| **Optional** | `scripts/build-images.sh` | only to build images without CI | CI's `docker/build-push-action` does the same thing |

So: **three commands on a fresh box, then `git push` forever.**

### Fresh deploy, start to finish

Roughly 45 minutes, most of it waiting on DNS and image builds.

**Phase 1 — outside the box** (nothing here is reachable from inside the instance)

1. Create the Ampere A1 instance: 4 OCPU / 24 GB, Ubuntu 24.04, arm64. → § Oracle Cloud
2. Reserve its public IP, open the VCN security list, point one DNS A record at it. → § Networking, § DNS
3. `dig +short <host>` must print that IP before you go near Let's Encrypt.

**Phase 2 — prepare the box**

4. `git clone <repo> /opt/relay && cd /opt/relay` — the whole repo, not just `deploy/`
   (compose bind-mounts `../keycloak/import` and `../elk`).
5. `sudo deploy/oci-bootstrap.sh`
6. `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env`, then fill in **every** blank.
   → § Secrets
7. `mkdir -p secrets && cp <your>-fcm.json secrets/fcm.json`

**Phase 3 — TLS, before anything else starts**

8. `deploy/init-letsencrypt.sh --staging` — then check it succeeded.
9. `deploy/init-letsencrypt.sh` — the real certificate. Staging first is not optional: the
   production CA allows five failed authorisations per hostname per hour.

**Phase 4 — first start**

10. Get images into the registry. Either push to `main` once and let the eight workflows build
    them (leave every `*_TAG=latest`), or run `scripts/build-images.sh --registry ghcr.io/<you>
    --push` from a machine with a JDK. → § CI/CD
11. `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d`
12. `deploy/bootstrap-elk.sh` — Kibana crash-loops until this runs. Expected, not a fault.
    Keycloak imports the realm on this first boot and resolves `KEYCLOAK_CLIENT_SECRET` from
    `.env` into it, so there is nothing to run afterwards.

**Phase 5 — hand it to CI**

14. Add the four repo secrets and generate the deploy key. → § CI/CD § One-time setup
15. Push to `main`. From here every deploy is: the service's workflow builds an image, sshes in,
    and runs `deploy/apply.sh <service> <sha>`. Nothing else.
16. Run the smoke test. → § Smoke test

### The whole CI/CD flow, in five lines

```
push to main
  └─ .github/workflows/<service>.yml         fires on its own paths: filter
       └─ .github/workflows/service.yml      build → test → image → push to ghcr
            └─ ssh <box>: deploy/apply.sh <service> <sha>
                 └─ writes <SERVICE>_TAG in .env, pulls, up -d --no-deps, waits on health,
                    puts the old tag back if it does not come up
```

There is no orchestrator and no change detection — GitHub's `paths:` filter is the whole of it.
Eight near-identical workflow files, each four lines long, calling one shared `service.yml`.

---

## What runs where

| | Published | Reachable how |
|---|---|---|
| nginx | 80, 443 | the only HTTP entry point |
| coturn | 3478 tcp+udp, 5349 tcp+udp, 49160-49200/udp | host networking, direct |
| livekit | 7881 tcp, 7882 udp | direct — media cannot be proxied |
| everything else | nothing | the `relay-network` bridge only |

LiveKit's signaling port 7880 is *not* published: it goes through nginx so clients get `wss://`.
Elasticsearch, Kafka, Redis and all five Postgres instances have no host ports at all. Kibana is
served by nginx at `/kibana` and *also* binds `127.0.0.1:5601`, so an ssh tunnel still reaches it
when nginx is the thing that is broken.

**One hostname, routed by path.** nginx serves a single vhost; `/api/v1/` and `/ws` go to the app,
`/realms/` to Keycloak, `/rtc` to LiveKit. See § DNS.

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
on every stop/start, and it is baked into your DNS record, a TLS cert, and `PUBLIC_IP`.

Then the ingress rules — a Network Security Group attached to the VNIC, or the subnet's default
security list. Stateful (the default; leave "stateless" unchecked), source `0.0.0.0/0`:

| Proto | Port | Serves | Fails as |
|---|---|---|---|
| TCP | 22 | ssh, and the deploy job | see below |
| TCP | 80 | ACME http-01 + the redirect to 443 | certbot cannot issue |
| TCP | 443 | everything HTTP — `/api/v1/`, `/ws`, `/realms/` (Keycloak), `/rtc` (LiveKit signaling) | nothing works |
| TCP | 3478 | TURN over TCP | — |
| UDP | **3478** | STUN + TURN. The one that matters. | 1:1 calls fail off-LAN |
| TCP | 5349 | TURN over TLS | networks that block 3478 fail |
| UDP | 5349 | TURN over DTLS | — |
| UDP | **49160-49200** | coturn's relay range | ICE completes, no audio |
| TCP | 7881 | LiveKit media, TCP fallback | group calls fail behind strict NAT |
| UDP | **7882** | LiveKit media | group calls have no audio |

Egress: leave the default allow-all. Certbot, GHCR pulls, FCM and APNs all need it.

There is no rule for 7880 (LiveKit signaling goes through nginx so clients get `wss://`) and none
for Elasticsearch, Kafka, Redis or any Postgres — they have no host ports at all. Kibana needs no
rule of its own either: it rides the 443 rule as a path on the one vhost, gated by
`ADMIN_ALLOW_CIDR` in nginx rather than by a security rule.

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

### DNS

**One A record.** The edge is a single nginx vhost routed by path, so one hostname carries
everything. Set `API_HOST`, `AUTH_HOST`, `SFU_HOST` and `TURN_HOST` in `deploy/.env` to that same
value — four consumers of one name, not four names:

| `.env` | Read by | For |
|---|---|---|
| `API_HOST` | nginx | `server_name`, and the certificate every listener loads |
| `AUTH_HOST` | Keycloak | `KC_HOSTNAME` — stamped into every token's `iss` claim |
| `SFU_HOST` | call-service | the `wss://` URL handed to clients for LiveKit |
| `TURN_HOST` | coturn | its TLS subject and its TURN realm |

Disagreement between them is an outage, not a smell: a token minted under one issuer URL is
rejected by all eight services validating against another.

Paths on that name: `/api/v1/` and `/ws` → the app · `/realms/` and `/resources/` → Keycloak ·
`/rtc` → LiveKit signaling. Everything else returns 404, `/internal/` and `/actuator/` explicitly
so. Nothing is path-rewritten — Keycloak already serves `/realms/` at its own root and LiveKit
already serves `/rtc` at its own root, which is what makes the collapse safe rather than fiddly.

**Kibana rides the same vhost at `/kibana`**, using `server.basePath` + `rewriteBasePath` so it
serves correctly under a prefix. It is gated by `ADMIN_ALLOW_CIDR` and also binds
`127.0.0.1:5601` as a fallback (§ Logs).

#### Where the record lives

- **Registering a domain**: OCI is not a registrar. Any registrar, ~$10/yr for a `.com`.
- **Hosting the zone**: **OCI DNS is not in the Always Free tier** — the official list covers
  VCNs, load balancers, VPN and flow logs, no DNS. It is paid and metered. Cloudflare DNS is free
  and unlimited, as is the DNS most registrars bundle.
- **Dynamic DNS** (No-IP, DuckDNS and friends) works, and is why this edge collapsed to one name
  in the first place — their free tiers give you exactly one hostname.

If you use Cloudflare, set the record to **DNS-only (grey cloud), not proxied (orange)**. A
proxied record resolves to Cloudflare's IP, and Cloudflare proxies HTTP(S) on a fixed port set —
so TURN on 3478/5349 breaks outright, and LiveKit breaks subtly: signaling survives on 443 while
media on 7881/7882 goes to an address the client can no longer discover.

#### If it is a free dynamic-DNS hostname

Two things to know, neither of which is a reason not to:

- **No-IP free hostnames must be confirmed every 30 days** or they are deleted. That name is
  inside your TLS certificate and every issued token's `iss` claim, so losing it is not a DNS
  outage, it is an auth outage. Put the reminder somewhere you will see it.
- Parents like `ddns.net` are shared across all their users, so whether Let's Encrypt's
  per-registered-domain rate limits are pooled depends on whether that parent is on the Public
  Suffix List. Do not guess — `init-letsencrypt.sh --staging` exists for exactly this, and the
  staging CA has no meaningful limits.

Also: dynamic DNS points at whatever IP last checked in. Your OCI IP is *reserved* and static, so
set it once and do not run a ddns update client — one would happily point your backend at a
laptop.

#### Verify before requesting certificates

http-01 means Let's Encrypt connects back to `http://<host>/.well-known/acme-challenge/…`, so the
name must resolve first. The production CA allows five failed authorisations per hostname per hour:

```bash
dig +short <host>          # must print the reserved IP
```

`init-letsencrypt.sh` requests one certificate per *distinct* name, so with all four `.env` vars
holding the same value it makes exactly one request — not four attempts at the same name, which
would burn the hour's budget before the first succeeded.

Then **First deploy** below.

## Requirements

- **16 GB RAM / 4 vCPU.** Eight JVMs ≈ 3 GB, Kafka 1.5 GB, Elasticsearch 1.5 GB, Kibana 1 GB,
  Keycloak 1 GB, five Postgres ≈ 1 GB, the rest ≈ 1 GB. 8 GB only works with the four logging
  containers removed.
- **Docker Engine on Linux.** `coturn` uses `network_mode: host`, which Docker Desktop does not
  implement the same way.
- **One DNS A record**, pointing at the box. `API_HOST`, `AUTH_HOST`, `SFU_HOST` and
  `TURN_HOST` in `deploy/.env` all hold it; the edge routes by path, not by name.
- **Firewall**: allow 80/tcp, 443/tcp, 3478/tcp+udp, 5349/tcp+udp, 49160-49200/udp, 7881/tcp,
  7882/udp. Deny everything else inbound.

## First deploy

See **§ Roadmap → Fresh deploy, start to finish** at the top of this file. It is the same
sequence, in the order you actually do it.

## Redeploying

Each service carries its own tag in `deploy/.env` (`MESSAGE_TAG`, `CALL_TAG`, …), so a deploy is
one line, one image and one container:

```bash
deploy/apply.sh message a1b2c3d      # on the server; CI calls exactly this over ssh
```

It writes the tag, pulls, `up -d --no-deps <service>`, waits on the healthcheck compose already
defines, and puts the previous tag back if the container does not come up. Rolling back is the
same command with the old sha — which is why each line in `.env` is a real commit rather than
`latest`.

Per-service tags are the reason this is simple. With one shared tag, bumping it changes the
resolved `image:` string for all eight, and compose hashes that string into its recreate decision
— so every deploy would restart everything, and every tag would have to be a complete set of eight
images. One tag per service removes both problems.

**Restarting `websocket-gateway` disconnects every live client.** There is one node and there can
only be one (ARCHITECTURE.md §6 — presence is decided from an in-memory registry, so a second node
would declare users connected elsewhere offline). Clients reconnect and catch up over REST, so it
is survivable, not invisible. It only restarts when its own code changes, which is the point.

Config-only containers are separate, because their config is bind-mounted and compose cannot see
a file edit:

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec nginx nginx -t
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec nginx nginx -s reload
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env restart logstash filebeat
```

---

## CI/CD (GitHub Actions)

**One workflow per service.** `.github/workflows/<service>.yml` fires on a push touching that
service, and does nothing otherwise — GitHub's own `paths:` filter decides, so there is no
change-detection script and no orchestration:

```yaml
name: Message CI/CD

on:
  push:
    paths:
      - 'message/**'
      - 'common/**'
      - 'deploy/Dockerfile'
      - '.github/workflows/message.yml'
      - '.github/workflows/service.yml'

jobs:
  ci:
    uses: ./.github/workflows/service.yml
    with:
      service: message
    secrets: inherit
```

`common/**` is in all eight lists because every service embeds it as a published artifact — so a
change there fires all eight workflows, in parallel, each rebuilding itself. `deploy/Dockerfile`
likewise, since one Dockerfile builds all eight images.

The shared body is `.github/workflows/service.yml`, called by all eight so the actual steps live
in one place:

1. JDK 25, `cd common && ./gradlew publishToMavenLocal` — every service resolves
   `com.relay:common:1.0` from `~/.m2`, so skipping this builds against a stale copy.
2. `cd <service> && ./gradlew build` — compile plus the Testcontainers integration tests.
3. **On `main` only**: build `linux/arm64,linux/amd64` (the deploy host is Ampere A1; the jar is
   architecture-independent so QEMU costs seconds), push `:<sha>` and `:latest` to GHCR, then ssh
   in and run `deploy/apply.sh <service> <sha>`.

On a branch it stops after step 2. So a branch push is CI, a main push is CD, in one file.

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

**On the server**, once:

```bash
git clone https://github.com/<you>/relay.git /opt/relay && cd /opt/relay
# ...then "First deploy" above, with RELAY_REGISTRY=ghcr.io/<you-lowercased> in deploy/.env
echo <a-ghcr-PAT-with-read:packages> | docker login ghcr.io -u <you> --password-stdin
```

GHCR packages are private on first push, so that `docker login` is what lets `compose pull` work.

The workflow leaves the checkout **detached at the deployed commit** — deliberate: the compose
file and `apply.sh` that ran are the ones CI built against. `git log -1` says what is deployed.

### Concurrency

A `common/**` change fires eight workflows at once, all sshing to the same box to edit the same
`deploy/.env`. Two guards: each workflow serialises against itself (`concurrency` keyed on the
service, newer run wins), and `apply.sh` takes an `flock` around the read-modify-write, so the
eight interleave safely rather than losing each other's tags.

### What CI does not cover

- **Keycloak realm changes.** `--import-realm` skips an existing realm; see below. The client
  secret is not affected — it comes from `.env` at import time.
- **Database migrations** run on service startup (Flyway), so they ship with the image. A
  migration that fails leaves the service unhealthy and `apply.sh` rolls the *image* back — it
  cannot roll a migration back. Keep them additive.
- **`docker-compose.prod.yml` and nginx/ELK config.** The checkout on the server moves to the new
  commit, but only the deployed service is recreated. Applying the rest is the manual `exec nginx
  -s reload` / `restart` above, or a deliberate `up -d`.
- **A push touching only paths no workflow lists** builds nothing. That is the trade for having no
  orchestrator: `paths:` is evaluated against the push's own commits, so if a run is skipped or
  fails, that service simply stays on its old tag until something touches it again.
- **The smoke test below**, especially step 5. No pipeline can place a call over a mobile network.

## Secrets

Everything is in `deploy/.env`, which is gitignored and must be `chmod 600`. Nothing is baked into
an image; FCM's service-account JSON and the APNs `.p8` are bind-mounted read-only at
`/run/secrets`.

**Rotate the Keycloak client secret before you accept a single real user.** The value in
`auth/src/main/resources/application.yaml` is committed to this repository, and before the logging
levels were capped it was also printed verbatim into log files by `org.apache.http.headers`, along
with every bearer and refresh token that passed through. Treat it as public:

Set it in `deploy/.env` **before the first `up -d`** and there is nothing else to do — Keycloak
resolves it into the realm at import time, and auth reads the same variable.

Rotating it *after* the realm exists is the one case that still needs two steps, because
`--import-realm` will not run again:

```bash
$EDITOR deploy/.env          # KEYCLOAK_CLIENT_SECRET=<new value>
# then set the same value on relay-client in the admin console, or with kcadm:
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec keycloak \
  /opt/keycloak/bin/kcadm.sh update clients/<id> -r relay-realm -s secret='<new value>'
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d auth
```

Set it in only one of the two places and auth presents a secret Keycloak never had: 401
`invalid_client` on every registration and login, surfacing as a 500 with a RESTEasy
`NotAuthorizedException` thrown from `ClientInvocation.filterRequest` — the admin token, not the
create-user call. A clean deploy cannot produce this, since both sides read the same variable; a
rotation against an existing realm can.

### Reaching the admin console

`https://<host>/admin` — Keycloak's admin console, behind the same `ADMIN_ALLOW_CIDR` allowlist
that gates Kibana. Log in with `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`.

`/admin` carries the admin **REST API** as well as the console UI, so anything reaching it with a
master-realm token can do whatever it likes to any realm — read client secrets, mint users, change
the issuer. The allowlist is the second lock, not the only one: leaving `ADMIN_ALLOW_CIDR` at
`0.0.0.0/0` publishes that login page to the internet. Narrow it to the address you administer
from.

Nothing else was needed to expose it — the console's assets already come from `/resources/` and
its login already goes through `/realms/`, both proxied, and `/realms/` is rate-limited, so the
admin login is throttled without a second `limit_req`. An ssh tunnel to `localhost:8080` on the
box still works if you ever lock yourself out with the CIDR.

## Keycloak realm changes

`--import-realm` **skips a realm that already exists**. It seeds an empty database on the very
first boot and does nothing on every boot after that. It is not a deployment mechanism: a change
to `keycloak/import/relay-realm.json` will not appear on the server. Either apply the change in
the admin console and re-export, or drop the realm and re-import deliberately.

The client secret is the exception, and it needs nothing extra. The realm file holds a
placeholder rather than a literal:

```json
"secret": "${KEYCLOAK_CLIENT_SECRET}"
```

Keycloak resolves that from its own environment when it imports the realm, and the compose file
passes `KEYCLOAK_CLIENT_SECRET` from `deploy/.env` to both Keycloak and auth. One variable, one
file, no step to remember. (Verified against `quay.io/keycloak/keycloak:26.0`: the value is
substituted at import, the `client_credentials` grant succeeds with it, and the old committed
secret is rejected.)

The dev compose file passes the same variable with the committed dev value, so a laptop stack is
unchanged.

Two dev values in the committed realm are worth fixing by hand, once, in the console:
`"sslRequired": "none"` should be `external` behind nginx, and `redirectUris`/`webOrigins` are `*`.

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

`https://<host>/kibana`, log in as `elastic` with `ELASTIC_PASSWORD`. Kibana is proxied by nginx
under a path (`SERVER_BASEPATH` + `SERVER_REWRITEBASEPATH`), so nginx passes the URI through
unchanged and Kibana strips the prefix itself.

`ADMIN_ALLOW_CIDR` is **open by default**, deliberately: a home address changes whenever the ISP
re-leases it, and an allowlist you cannot keep current locks you out of your own logs. Two things
guard it instead — Elasticsearch's own authentication, and a `limit_req` of 5r/s that nginx
applies to `/kibana/internal/security/login` only. It is on the login endpoint alone because one
Kibana page pulls dozens of assets at once and a limit on the whole prefix would throttle the UI.

Narrow the CIDR only if you have a genuinely stable address:

```
ADMIN_ALLOW_CIDR=203.0.113.45/32
```

Kibana also stays bound to `127.0.0.1:5601` on the host, so the tunnel still works and is what you
want on the day nginx is the thing that is broken:

```bash
ssh -L 5601:localhost:5601 <box>      # then http://localhost:5601
```

Retention is 30 days (`elk/es/ilm-relay-prod.json`); dev is 3. Only INFO and above is shipped —
`logging.threshold.file` — so a *successful* request produces no records at all. That is the usual
reason Discover looks empty, along with too narrow a time range.

The console stream stays at INFO here too (`LOGGING_THRESHOLD_CONSOLE`), readable with
`docker compose … logs -f <service>` and capped at 3 × 20 MB per container. The per-request DEBUG
narrative under `com.relay` never leaves the container log — find the failure in Kibana, take its
`trace.id`, grep the console log for the rest.

Services write ECS JSON into the shared `relay-logs` volume, which filebeat mounts read-only at
the path the dev globs already expect — so `elk/filebeat/filebeat.yml` and the Logstash pipeline
are the same files in both environments. Only `RELAY_ENV`, the data-stream namespace and the
Elasticsearch credentials differ, and all three come from the environment.

## Smoke test

In order, because each step depends on the one before:

1. `curl https://<host>/api/v1/auth/...` — register, then log in.
2. Connect the socket to `wss://<host>/ws`, send a message, confirm the ack.
   Also `curl https://<host>/realms/relay-realm/.well-known/openid-configuration` — the path
   routing to Keycloak is the part a name-based setup never had to prove.
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
