#!/usr/bin/env bash
# Fase 8 (E14): pruebas de integración de los siete servicios contra dependencias reales.
#
# Crea una red Docker propia (`pf-it`), levanta PostgreSQL, MySQL, RabbitMQ y Mailhog efímeros
# (scripts/it-deps.sh) y ejecuta la suite de cada servicio (scripts/it-run.sh) dentro de la misma
# imagen de toolchain que usa su Dockerfile. Borra todo al terminar. No toca el stack de
# `docker compose` ni sus volúmenes. Jenkins usa las mismas dos piezas sobre la red cicd.
#
# Uso:   bash scripts/test-integration.sh                 # los siete servicios
#        bash scripts/test-integration.sh vacaciones auth  # solo los indicados
# Salida distinta de 0 si alguna suite falla. Reportes JUnit en services/<svc>/reports/.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
RED=pf-it
PREFIJO=pf-it
export MSYS_NO_PATHCONV=1

# Ruta del host montable en `docker run -v` (Git Bash en Windows necesita la forma C:/...).
ruta_host() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

declare -A IMAGEN=(
  [empleados]=eclipse-temurin:21.0.9_10-jdk-alpine [perfiles]=eclipse-temurin:21.0.9_10-jdk-alpine
  [departamentos]=golang:1.25.11-alpine
  [auth]=python:3.12.12-slim-bookworm [notificaciones]=python:3.12.12-slim-bookworm
  [vacaciones]=node:22.21.1-alpine3.22 [gateway]=node:22.21.1-alpine3.22
)
# Cachés de dependencias en volúmenes con nombre para no descargar en cada ejecución.
declare -A CACHE=(
  [empleados]="-v pf-it-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"
  [perfiles]="-v pf-it-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"
  [departamentos]="-v pf-it-gomod:/go/pkg/mod"
  [auth]="-v pf-it-pip:/root/.cache/pip" [notificaciones]="-v pf-it-pip:/root/.cache/pip"
  [vacaciones]="-v /src/services/vacaciones-service/node_modules"
  [gateway]="-v /src/services/api-gateway/node_modules"
)
carpeta() { case $1 in gateway) echo api-gateway ;; *) echo "$1-service" ;; esac; }

ENV_IT="$(mktemp)"
# docker.exe (Windows) no entiende /tmp de Git Bash: se le pasa la ruta nativa.
ENV_IT_DOCKER="$(cygpath -w "$ENV_IT" 2>/dev/null || echo "$ENV_IT")"
limpiar() {
  bash "$RAIZ/scripts/it-deps.sh" down "$PREFIJO"
  docker network rm "$RED" >/dev/null 2>&1 || true
  rm -f "$ENV_IT"
}

it() {
  local s=$1 previo=""
  case $s in vacaciones|gateway) previo='npm ci --no-audit --no-fund -q && ' ;; esac
  # shellcheck disable=SC2086
  docker run --rm --network "$RED" -v "$(ruta_host "$RAIZ"):/src" --env-file "$ENV_IT_DOCKER" \
    ${CACHE[$s]} -w "/src/services/$(carpeta "$s")" "${IMAGEN[$s]}" \
    sh -c "${previo}sh /src/scripts/it-run.sh $s"
}

SERVICIOS=("$@")
[ ${#SERVICIOS[@]} -eq 0 ] && SERVICIOS=(empleados departamentos auth perfiles vacaciones notificaciones gateway)

trap limpiar EXIT
docker network rm "$RED" >/dev/null 2>&1 || true
docker network create "$RED" >/dev/null
bash "$RAIZ/scripts/it-deps.sh" up "$PREFIJO" "$RED" "$ENV_IT"

fallidos=()
for s in "${SERVICIOS[@]}"; do
  echo "=== Integración: $s"
  if it "$s"; then echo "=== $s: OK"; else echo "=== $s: FALLÓ"; fallidos+=("$s"); fi
done

if [ ${#fallidos[@]} -gt 0 ]; then
  echo "Suites de integración fallidas: ${fallidos[*]}" >&2
  exit 1
fi
echo "Integración correcta en: ${SERVICIOS[*]}"
