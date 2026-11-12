# Fase 7 (E01/E02): imagen multi-stage, base fijada y usuario no root.

# Etapa 1: compilar el binario con el toolchain completo de Go. La versión
# coincide con la directiva `go 1.25.11` de go.mod.
FROM golang:1.25.11-alpine AS build
WORKDIR /app

# Copiar solo los manifiestos de módulo primero: si el código cambia pero las
# dependencias no, Docker reutiliza esta capa cacheada y no vuelve a
# descargar nada.
COPY go.mod go.sum ./
RUN go mod download

COPY cmd cmd
COPY internal internal

# CGO_ENABLED=0: binario estático, sin dependencias de libc del sistema —
# necesario para poder correrlo sobre la imagen mínima alpine de la etapa 2.
# -trimpath y -s -w: sin rutas locales ni tablas de depuración (menor tamaño).
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/departamentos-service ./cmd/api

# Etapa 2: imagen final, sin el toolchain de Go ni el código fuente.
# Alpine (y no distroless) porque el healthcheck de Compose ejecuta curl.
FROM alpine:3.22.2 AS runtime

# Fase 9 (seguridad de imagen): la base está fijada por versión, pero sus paquetes reciben
# parches después de publicada. Se aplican los de seguridad para que Trivy no encuentre
# vulnerabilidades CRITICAL ya corregidas (OpenSSL, GnuTLS...).
RUN apk upgrade --no-cache

LABEL org.opencontainers.image.title="departamentos-service" \
      org.opencontainers.image.description="Departamentos (Go + chi + MySQL)" \
      org.opencontainers.image.source="https://github.com/juan-contrerasM/reto1-microservicios"

# curl: lo usa el healthcheck de docker-compose.yml raíz para pegarle a
# GET /health desde dentro del propio contenedor.
# Usuario de sistema con UID/GID fijos 10001, sin home ni shell de login.
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app

WORKDIR /app
COPY --from=build /out/departamentos-service ./departamentos-service
# Los changelogs de golang-migrate se leen del disco en tiempo de ejecución
# (ver internal/db/migrate.go, ruta relativa file://db/migrations desde
# WORKDIR /app), por eso viajan junto al binario. Dueño root, solo lectura.
COPY db/migrations ./db/migrations

EXPOSE 8081

USER 10001:10001
ENTRYPOINT ["./departamentos-service"]
