# Multi-stage build. The build stage uses a Gradle-preinstalled image (not ./gradlew) because
# gradlew/gradle-wrapper.jar are intentionally NOT committed to this repo (see .gitignore) — the
# HAOS Supervisor builds this addon by cloning the repo via git and running this Dockerfile
# locally on the device, so the build can't depend on a wrapper that isn't there.
FROM gradle:9.3.0-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle build --no-daemon -x test

# Pure-JVM app — the same image works for amd64 and aarch64 under buildx with no special handling.
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client jq \
    && rm -rf /var/lib/apt/lists/*

# /data is the persistent-storage convention for HA add-ons (mapped via config.yaml's "map: data:rw")
# and doubles as the working directory for standalone-docker (mount a volume here). gateway.db,
# gateway.creds.path and gateway.conf.path already default to relative paths, so this is enough
# to persist them without touching application.properties.
WORKDIR /data

COPY --from=build /src/build/libs/*-lean.jar /app/gateway.jar
COPY --from=build /src/build/libs/lib /app/lib
COPY run.sh /run.sh
RUN chmod +x /run.sh

ENV GATEWAY_DEPLOYMENT_MODE=container

ENTRYPOINT ["/run.sh"]
