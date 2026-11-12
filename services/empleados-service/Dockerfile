# Fase 7 (E01/E02): imagen multi-stage, base fijada, JRE y usuario no root.

# Etapa 1: compilar con JDK + Maven Wrapper. El JDK, ~/.m2 y el código fuente
# se quedan en esta etapa.
FROM eclipse-temurin:21.0.9_10-jdk-alpine AS build
WORKDIR /app

# Manifiestos primero: si solo cambia src/, Docker reutiliza la capa de dependencias.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -q -B dependency:go-offline
COPY src src

RUN ./mvnw -q -B -DskipTests package

# Etapa 2: runtime con JRE (sin compilador ni Maven).
FROM eclipse-temurin:21.0.9_10-jre-alpine AS runtime

# Fase 9 (seguridad de imagen): la base está fijada por versión, pero sus paquetes reciben
# parches después de publicada. Se aplican los de seguridad para que Trivy no encuentre
# vulnerabilidades CRITICAL ya corregidas (OpenSSL, GnuTLS...).
RUN apk upgrade --no-cache

LABEL org.opencontainers.image.title="empleados-service" \
      org.opencontainers.image.description="Empleados (Java 21 + Spring Boot + PostgreSQL)" \
      org.opencontainers.image.source="https://github.com/juan-contrerasM/reto1-microservicios"

WORKDIR /app

# curl: lo usa el healthcheck de docker-compose.yml raíz contra GET /health.
# Usuario de sistema con UID/GID fijos 10001, sin home ni shell de login.
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app

# Agente OpenTelemetry con versión fijada (Reto 8). Se descarga como root y
# queda de solo lectura para el proceso.
ARG OTEL_JAVA_AGENT_VERSION=2.33.1
RUN curl -fsSL "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    -o /app/opentelemetry-javaagent.jar \
    && chmod 0644 /app/opentelemetry-javaagent.jar

COPY --from=build /app/target/*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod 755 /app/docker-entrypoint.sh

EXPOSE 8080

# El entrypoint lee DB_PASSWORD_FILE y RABBITMQ_PASSWORD_FILE de /run/secrets:
# los archivos deben ser legibles por el UID 10001 (ver scripts/setup-secrets.*).
# Tomcat y la JVM solo escriben en /tmp (1777 en la imagen base).
USER 10001:10001
ENTRYPOINT ["/app/docker-entrypoint.sh"]
