# KubeMind backend image.
# Build from the REPO ROOT (the context must contain backend/):
#   docker build -f deploy/docker/backend.Dockerfile -t kubemind/backend:0.1.0 .

# ── Build stage ────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependency layer first so code-only changes don't re-download the world.
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# The in-app Helm feature (charts/releases pages) shells out to the `helm` CLI —
# see backend HelmCliService. Without this binary those endpoints return 503.
ARG TARGETARCH
ARG HELM_VERSION=v3.18.4
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" \
    | tar -xz --strip-components=1 -C /usr/local/bin "linux-${TARGETARCH}/helm" \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

# Non-root. HOME must be writable: HelmCliService keeps its isolated helm state
# under ~/.kubemind/helm, and helm itself needs a writable cache dir.
RUN useradd --create-home --uid 10001 kubemind
USER kubemind
WORKDIR /home/kubemind

COPY --from=build /build/target/kubemind-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]