# Maintenance Manager (CMMS) - single container for Cloud Run
# API + React UI served on one port ($PORT). PostgreSQL is external (Cloud SQL via CMMS_DB_URL).

# ---- UI ----
FROM node:20-bookworm-slim AS web
WORKDIR /src/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- API ----
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# ---- Runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /src/backend/target/cmms-api.jar /app/cmms-api.jar
COPY --from=web /src/frontend/dist /app/web
ENV CMMS_BIND=0.0.0.0 \
    CMMS_WEB_DIR=/app/web \
    CMMS_UPLOAD_DIR=/tmp/cmms/uploads \
    CMMS_REPORT_DIR=/tmp/cmms/reports \
    CMMS_PM_SCHEDULER=true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["sh", "-c", "CMMS_PORT=${PORT:-8080} exec java -jar /app/cmms-api.jar"]
