# API image. Build from the REPO ROOT (multi-module Maven build needs
# the parent pom):
#
#   docker build -f services/api/Dockerfile -t control-plane-api .
#
# Two stages: a JDK builder that produces the Spring Boot fat jar, and
# a JRE runtime that only carries the jar. The builder layers are
# ordered so dependency resolution is cached until a pom changes —
# source edits alone don't re-download the world.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Maven wrapper + poms first: this layer (and the dependency download
# below) only busts when the build definition changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY services/api/pom.xml services/api/pom.xml

# Warm the local repo. Best-effort (|| true): go-offline can't always
# resolve every plugin for a multi-module build, and the package step
# below will fetch whatever's missing anyway.
RUN ./mvnw -q -pl services/api -am dependency:go-offline || true

COPY services/api/src services/api/src
RUN ./mvnw -q -pl services/api -am package -DskipTests \
    && cp services/api/target/*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
# curl is only here for the container HEALTHCHECK; the JRE base image
# ships without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user — the app needs no filesystem writes beyond
# /tmp, so this is free hardening.
RUN useradd --system --create-home appuser
USER appuser
WORKDIR /home/appuser

COPY --from=build /workspace/app.jar app.jar

EXPOSE 8080
# start-period is generous because Flyway migrations run on boot.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
