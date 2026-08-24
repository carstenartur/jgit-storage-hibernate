# syntax=docker/dockerfile:1.7
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl jgit-storage-hibernate-server -am -DskipTests package \
    && find jgit-storage-hibernate-server/target -maxdepth 1 -type f -name 'jgit-storage-hibernate-server-*.jar' \
         ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit \
       | xargs -r -I{} cp {} /workspace/server.jar \
    && test -s /workspace/server.jar

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="jgit-storage-hibernate-server" \
      org.opencontainers.image.description="PostgreSQL-backed JGit Smart HTTP server with searchable history" \
      org.opencontainers.image.source="https://github.com/carstenartur/jgit-storage-hibernate" \
      org.opencontainers.image.licenses="BSD-3-Clause"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system jgit \
    && useradd --system --gid jgit --home-dir /var/lib/jgit-storage \
         --shell /usr/sbin/nologin jgit \
    && mkdir -p /app /var/lib/jgit-storage/search \
    && chown -R jgit:jgit /app /var/lib/jgit-storage
COPY --from=build --chown=jgit:jgit /workspace/server.jar /app/server.jar
USER jgit
EXPOSE 8080
VOLUME ["/var/lib/jgit-storage/search"]
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/server.jar"]
