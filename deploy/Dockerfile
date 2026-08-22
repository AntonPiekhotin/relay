# One Dockerfile for all eight services. They differ only in which bootJar goes in, so a per-
# service copy would be eight identical files drifting apart.
#
#   docker build -f deploy/Dockerfile --build-arg SERVICE=message -t relay/message:<tag> .
#
# The build context is the REPO ROOT, and the jar must already exist — this image does not compile
# anything. That is deliberate: `common` is consumed from ~/.m2 as a published artifact rather than
# as a project dependency (CLAUDE.md invariant 1), so a self-contained Gradle build stage inside
# the image would not be able to resolve it without dragging the whole repo in as context and
# rebuilding `common` per service. scripts/build-images.sh does the publish-then-build ordering.

FROM eclipse-temurin:25-jre

# Java 25 is required, not preferred: JEP 491 (Java 24) removed the `synchronized` pinning of
# virtual threads, and this codebase is blocking-style on virtual threads throughout.

ARG SERVICE
ARG VERSION=0.0.1-SNAPSHOT

# curl is only here for the compose healthcheck — the temurin images ship without it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# uid/gid pinned rather than left to useradd. All eight images share one log volume, and the
# first container to mount it seeds its ownership — an auto-assigned uid that happened to differ
# between two images would leave one service unable to write its own log file.
RUN groupadd --system --gid 1001 relay \
 && useradd --system --uid 1001 --gid relay --home /app relay

WORKDIR /app

COPY ${SERVICE}/build/libs/${SERVICE}-${VERSION}.jar /app/app.jar

# The shared log volume is mounted here; see RELAY_LOG_DIR in docker-compose.prod.yml. Created and
# chowned at build time so the non-root user can write even before the volume has an owner.
RUN mkdir -p /var/log/relay/json && chown -R relay:relay /var/log/relay /app

USER relay

# -XX:MaxRAMPercentage=60 because the JVM otherwise claims 25% of HOST RAM per process and there
# are eight of these; -XX:+UseSerialGC because G1's overhead is not worth it below ~1 GB heaps and
# it costs more memory. Overridden per service in compose where a service needs more.
# -Djdk.tracePinnedThreads is deliberately absent — it is a diagnostic and it costs on every pin.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"

# `sh -c` so JAVA_OPTS expands, `exec` so java becomes PID 1 and receives SIGTERM directly —
# without it the shell swallows the signal and every stop waits out the 10s kill timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
