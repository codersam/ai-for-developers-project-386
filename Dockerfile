# syntax=docker/dockerfile:1.7
#
# Standalone deployment image (STEP9): Spring Boot + bundled PostgreSQL 16
# in a single container. Boots end-to-end with only PORT set in the env.
# For the external-Postgres variant used by Render and compose.prod.yaml,
# see Dockerfile.render.

FROM node:24-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-builder
WORKDIR /app/backend
COPY backend/gradlew backend/gradlew.bat ./
COPY backend/gradle/ ./gradle/
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY backend/spec/ ./spec/
COPY backend/src/ ./src/
COPY --from=frontend-builder /app/frontend/dist/ ./src/main/resources/static/
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache postgresql16 postgresql16-contrib
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /var/lib/postgresql/data \
 && chown -R app:app /var/lib/postgresql \
 && chmod 700 /var/lib/postgresql/data
COPY --from=backend-builder --chown=app:app /app/backend/build/libs/*.jar /app/app.jar
COPY --chown=app:app docker/standalone-entrypoint.sh /usr/local/bin/standalone-entrypoint.sh
RUN chmod +x /usr/local/bin/standalone-entrypoint.sh
ENV PGDATA=/var/lib/postgresql/data \
    SPRING_DOCKER_COMPOSE_ENABLED=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
VOLUME /var/lib/postgresql/data
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/api/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["/usr/local/bin/standalone-entrypoint.sh"]
