# syntax=docker/dockerfile:1.7

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
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=backend-builder --chown=app:app /app/backend/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
