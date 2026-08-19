# ELK — centralized logs for local development

Dev only. No TLS, no authentication on `:9200` or `:5601`, 3-day retention.

## What ships where

```
<service> JVM on the host
  └─ console (stdout)  ──> logs/<service>.log        plain text, DEBUG, what start-all.sh greps
  └─ file appender     ──> logs/json/<service>.json  ECS JSON, INFO+, rotated by logback
                                │
                                └─ filebeat (container, read-only bind mount)
                                     └─ logstash :5044  redact + drop noise + MDC→ECS
                                          └─ elasticsearch :9200  data stream logs-relay.app-dev
                                               └─ kibana :5601
```

The console/file split is load-bearing. `scripts/start-all.sh` greps stdout for
`': Started …Kt in N seconds'` (readiness) and `'(Tomcat|Netty) started on port N'` (port
discovery — five services run `server.port: 0` and are otherwise unfindable). Structured JSON on
the console would break startup orchestration, which is why `logging.structured.format.console` is
left unset everywhere.

Because the hand-off is a file, ELK being down never affects a service. The files are the buffer
and filebeat resumes from its registry.

## Start it

```bash
docker compose --profile logging up -d
elk/setup.sh                       # required once per ES volume — creates the Kibana data view
# or, together with the services:
scripts/start-all.sh --infra --logging && elk/setup.sh
```

The containers are behind the `logging` compose profile, so a plain `docker compose up -d` leaves
them down. They cost ~2.2 GB; give Docker Desktop at least 6 GB or Elasticsearch is OOM-killed
mid-index.

## One-time setup

```bash
elk/setup.sh
```

Idempotent, safe to re-run. It applies the ILM policy (3-day retention — Elasticsearch's built-in
`logs` policy rolls over but never deletes), the index template, **and the Kibana data view**.

That last one is not optional: Discover queries a data view, not an index. Without it Kibana shows
"no data" no matter how many documents Elasticsearch holds, and nothing in the UI points at the
cause. If Kibana looks empty, run this script first.

## Kibana

`http://localhost:5601/app/discover`, data view **Relay logs** (`logs-relay.app-*`). Useful columns:
`service.name`, `log.level`, `trace.id`, `user.id`, `log.logger`, `message`.

## Why Discover can look empty

Three causes, in the order worth checking:

1. **No data view.** Run `elk/setup.sh`. See above.
2. **The time range.** Idle services log almost nothing — steady state across all eight is ~10
   records/minute — so the default "Last 15 minutes" is legitimately empty unless something is
   happening. Widen to Last 24 hours.
3. **Only INFO and above is shipped** (`logging.threshold.file`). A *successful* request produces no
   records at all in Kibana: this codebase logs its per-request narrative at DEBUG under
   `com.relay`, and that stays in `logs/<service>.log`. Kibana holds startup, warnings and errors.
   To follow one request end to end, take its `trace.id` from Kibana and grep the console log — or
   set `logging.threshold.file: DEBUG`, which measured at only 4 extra records per run.

## Where the log files must land

`logging.file.name` is `${RELAY_LOG_DIR:logs}/json/<service>.json`, and filebeat bind-mounts
**`./logs/json` inside the repo**. Anything written elsewhere is invisible to Kibana, silently.

- `scripts/start-all.sh` exports `RELAY_LOG_DIR` as an absolute path — always correct.
- **IDE run configurations** set no working directory, and IntelliJ then picks one per module: the
  repo root for most services, the module directory for others. Both are covered, because filebeat
  mounts the repo root read-only and globs `logs/json/*.json` **and** `*/logs/json/*.json`.
- This bit once, and is why the mount is that wide: an earlier `../logs` default sent every
  IDE-launched service to `<parent-of-repo>/logs/json/`, outside the mount entirely, so those runs
  wrote perfectly good JSON that nothing ever read.
- Anywhere else still misses. Set `RELAY_LOG_DIR` to an absolute path if in doubt.

Quick check — the file should be under the repo:

```bash
lsof -p <pid> | grep logs/json
```

## Checks

```bash
# is anything arriving?
curl -s 'localhost:9200/logs-relay.app-dev/_count'

# which services have reported?
curl -s 'localhost:9200/logs-relay.app-dev/_search?size=0' -H 'Content-Type: application/json' \
  -d '{"aggs":{"svc":{"terms":{"field":"service.name","size":20}}}}'

# credential leak check — must be 0
curl -s 'localhost:9200/logs-relay.app-dev/_count?q=eyJ'

# is the pipeline healthy?
curl -s 'localhost:9600/_node/stats/pipelines' | head -c 400
docker compose --profile logging logs --tail=30 filebeat   # expect "Harvester started for paths"
```

## Editing the pipeline

`elk/logstash/pipeline/relay.conf` reloads automatically in ~5s (`config.reload.automatic`), no
restart needed. That is most of why Logstash is here rather than shipping filebeat → Elasticsearch
directly: each service's `application.yaml` is baked into its bootJar, so muting a chatty logger
there costs a rebuild and a restart, while doing it here costs an edit.

The other reason is redaction. `auth`'s Apache HttpClient loggers print bearer tokens and the
Keycloak client secret verbatim; the levels are capped in `auth/src/main/resources/application.yaml`,
and the `drop` rules here are what survive someone re-enabling them to debug Keycloak.

## Wiping the data

```bash
curl -XDELETE localhost:9200/_data_stream/logs-relay.app-dev
# or nuke the volume entirely:
docker compose --profile logging down && docker volume rm relay_elasticsearch_data relay_filebeat_data
```
