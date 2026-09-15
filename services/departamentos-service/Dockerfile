# Etapa 1: compilar el binario con el toolchain completo de Go.
FROM golang:1.25-alpine AS build
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
RUN CGO_ENABLED=0 go build -o /out/departamentos-service ./cmd/api

# Etapa 2: imagen final, sin el toolchain de Go ni el código fuente.
FROM alpine:3.20
WORKDIR /app

# curl: lo usa el healthcheck de docker-compose.yml raíz para pegarle a
# GET /health desde dentro del propio contenedor.
RUN apk add --no-cache curl

COPY --from=build /out/departamentos-service ./departamentos-service
# Los changelogs de golang-migrate se leen del disco en tiempo de ejecución
# (ver internal/db/migrate.go), por eso viajan junto al binario.
COPY db/migrations ./db/migrations

EXPOSE 8081

ENTRYPOINT ["./departamentos-service"]
