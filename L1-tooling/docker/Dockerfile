# Block Reality — Multi-stage build for reproducible CI/CD environments
#
# ★ Audit fix (DevOps 專家): 完全缺失容器化 → 提供標準 Dockerfile
#
# Usage:
#   docker build -t block-reality .
#   docker run --rm block-reality                          # Run all tests
#   docker run --rm block-reality ./gradlew mergedJar      # Build merged JAR
#   docker cp $(docker create block-reality):/app/mpd.jar ./mpd.jar  # Extract JAR
#
# Stages:
#   1. sidecar-build: Node.js 20, builds TypeScript sidecar
#   2. mod-build:     Java 17 + Node.js 20, builds Forge mod + runs tests

# ═══ Stage 1: Build TypeScript sidecar ═══
FROM node:20-slim AS sidecar-build
WORKDIR /app/MctoNurbs-review

COPY MctoNurbs-review/package.json MctoNurbs-review/package-lock.json* ./
RUN npm ci --ignore-scripts

COPY MctoNurbs-review/ ./
RUN npm run build && npm test

# ═══ Stage 2: Build Forge mod ═══
FROM eclipse-temurin:17-jdk AS mod-build

# Install Node.js 20 for sidecar integration during Gradle processResources
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy sidecar build output
COPY --from=sidecar-build /app/MctoNurbs-review /app/MctoNurbs-review

# Copy Gradle wrapper first (cache layer)
COPY "Block Reality/gradle/" "Block Reality/gradle/"
COPY "Block Reality/gradlew" "Block Reality/gradlew"
RUN chmod +x "Block Reality/gradlew"

# Copy build configuration (cache layer)
COPY "Block Reality/build.gradle" "Block Reality/build.gradle"
COPY "Block Reality/settings.gradle" "Block Reality/settings.gradle"
COPY "Block Reality/api/build.gradle" "Block Reality/api/build.gradle"
COPY "Block Reality/fastdesign/build.gradle" "Block Reality/fastdesign/build.gradle"

# Copy source code
COPY "Block Reality/" "Block Reality/"

# Set Gradle options: no daemon, 3GB heap
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx3g"

WORKDIR /app/Block Reality

# Build and test
RUN ./gradlew build --no-daemon --stacktrace

# Default command: run tests
CMD ["./gradlew", "test", "--no-daemon"]
