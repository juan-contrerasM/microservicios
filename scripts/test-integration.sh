#!/usr/bin/env bash
# Fase 8 (E14): pruebas de integración de los siete servicios contra dependencias reales.
#
# Levanta PostgreSQL, MySQL, RabbitMQ y Mailhog efímeros en una red Docker propia (`pf-it`),
# ejecuta la suite de integración de cada servicio dentro de la misma imagen de toolchain que usa
# su Dockerfile y borra todo al terminar. No toca el stack de `docker compose` ni sus volúmenes.
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

PG_IMAGEN=postgres:16.4-alpine
MYSQL_IMAGEN=mysql:8.4.3
RABBIT_IMAGEN=rabbitmq:3.13.7-management-alpine
MAILHOG_IMAGEN=mailhog/mailhog:v1.0.1
NODE_IMAGEN=node:22.21.1-alpine3.22
PY_IMAGEN=python:3.12.12-slim-bookworm
GO_IMAGEN=golang:1.25.11-alpine
JDK_IMAGEN=eclipse-temurin:21.0.9_10-jdk-alpine

# Credenciales efímeras distintas en cada ejecución: los contenedores solo viven durante la prueba.
aleatorio() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
PG_PASS="it$(aleatorio 12)"
JWT_IT="$(aleatorio 32)"
ADMIN_IT="Aa1!$(aleatorio 8)"
AMQP_URL="amqp://it:it-pass@${PREFIJO}-rabbit:5672"

limpiar() {
  docker rm -f "${PREFIJO}-pg" "${PREFIJO}-mysql" "${PREFIJO}-rabbit" "${PREFIJO}-mail" >/dev/null 2>&1 || true
  docker network rm "$RED" >/dev/null 2>&1 || true
}

esperar() {
  local nombre=$1; shift
  for _ in $(seq 1 90); do
    if docker exec "$nombre" "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "La dependencia $nombre no quedó lista" >&2
  return 1
}

levantar_infra() {
  limpiar
  docker network create "$RED" >/dev/null
  docker run -d --rm --name "${PREFIJO}-pg" --network "$RED" -e POSTGRES_PASSWORD="$PG_PASS" "$PG_IMAGEN" >/dev/null
  docker run -d --rm --name "${PREFIJO}-mysql" --network "$RED" -e MYSQL_ROOT_PASSWORD="$PG_PASS" \
    -e MYSQL_DATABASE=departamentos_it "$MYSQL_IMAGEN" >/dev/null
  docker run -d --rm --name "${PREFIJO}-rabbit" --network "$RED" -e RABBITMQ_DEFAULT_USER=it \
    -e RABBITMQ_DEFAULT_PASS="$PG_PASS" "$RABBIT_IMAGEN" >/dev/null
  docker run -d --rm --name "${PREFIJO}-mail" --network "$RED" "$MAILHOG_IMAGEN" >/dev/null
  esperar "${PREFIJO}-pg" pg_isready -U postgres
  esperar "${PREFIJO}-mysql" mysqladmin ping -uroot -p"$PG_PASS" --silent
  esperar "${PREFIJO}-rabbit" rabbitmq-diagnostics -q check_port_connectivity
  for db in vacaciones auth notificaciones; do
    docker exec "${PREFIJO}-pg" psql -qU postgres -c "CREATE DATABASE ${db}_it" >/dev/null
  done
}

pg_url() { echo "postgresql://postgres:${PG_PASS}@${PREFIJO}-pg:5432/$1_it"; }

# correr <servicio> [opciones de docker run...] <imagen> <comando...>
correr() {
  local servicio=$1; shift
  docker run --rm --network "$RED" -v "$(ruta_host "$RAIZ/services/$servicio"):/src" -w /src "$@"
}

it_vacaciones() {
  correr vacaciones-service -v /src/node_modules \
    -e IT_DATABASE_URL="$(pg_url vacaciones)" -e IT_AMQP_URL="$AMQP_URL" "$NODE_IMAGEN" \
    sh -c 'npm ci --no-audit --no-fund -q && npm run test:integration'
}

it_departamentos() {
  correr departamentos-service -v pf-it-gomod:/go/pkg/mod \
    -e IT_MYSQL_DSN="root:${PG_PASS}@tcp(${PREFIJO}-mysql:3306)/departamentos_it?parseTime=true&multiStatements=true" \
    "$GO_IMAGEN" sh -c 'mkdir -p reports && go run gotest.tools/gotestsum@v1.12.3 \
      --junitfile reports/junit-integracion.xml -- -tags integration -count=1 ./internal/integration/...'
}

# Variables comunes de los dos servicios Python: Settings lee BROKER_URL, usuario y clave por separado.
py_env() {
  echo -e "-e DATABASE_URL=postgresql+psycopg://postgres:${PG_PASS}@${PREFIJO}-pg:5432/$1_it" \
    "-e BROKER_URL=amqp://${PREFIJO}-rabbit:5672 -e RABBITMQ_USER=it -e RABBITMQ_PASSWORD=${PG_PASS}" \
    "-e IT_AMQP_HOST=${PREFIJO}-rabbit -e PYTHONDONTWRITEBYTECODE=1"
}

PY_PRUEBAS='pip install -q --disable-pip-version-check -r requirements-dev.txt && \
  python -m pytest tests/integracion -p no:cacheprovider -o addopts="" --junitxml=reports/junit-integracion.xml -v'

it_auth() {
  # shellcheck disable=SC2046
  correr auth-service -v pf-it-pip:/root/.cache/pip $(py_env auth) \
    -e JWT_SECRET="$JWT_IT" -e AUTH_ADMIN_PASSWORD="$ADMIN_IT" \
    "$PY_IMAGEN" sh -c "$PY_PRUEBAS"
}

it_notificaciones() {
  # shellcheck disable=SC2046
  correr notificaciones-service -v pf-it-pip:/root/.cache/pip $(py_env notificaciones) \
    -e SMTP_HOST="${PREFIJO}-mail" -e SMTP_PORT=1025 -e IT_MAILHOG_API="http://${PREFIJO}-mail:8025" \
    "$PY_IMAGEN" sh -c "$PY_PRUEBAS"
}

# Java: Testcontainers crea su propio PostgreSQL/RabbitMQ a través del socket de Docker.
# `mvn verify` corre unitarias (Surefire) + *IT (Failsafe) y aplica el umbral JaCoCo combinado.
it_java() {
  correr "$1" -v pf-it-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal "$JDK_IMAGEN" \
    sh -c "cp -r .mvn /tmp/ && tr -d '\r' < mvnw > /tmp/mvnw && sh /tmp/mvnw -B -q verify"
}
it_empleados() { it_java empleados-service; }
it_perfiles() { it_java perfiles-service; }

# El Gateway no tiene base ni broker: su integración levanta servidores HTTP reales que hacen de
# backends (tests/app.test.js y tests/circuit-breaker.test.js) y pasa por JWT, RBAC, proxy y CB.
it_gateway() {
  correr api-gateway -v /src/node_modules "$NODE_IMAGEN" \
    sh -c 'npm ci --no-audit --no-fund -q && mkdir -p reports && node --test --test-force-exit \
      --test-reporter=spec --test-reporter-destination=stdout \
      --test-reporter=junit --test-reporter-destination=reports/junit-integracion.xml \
      tests/app.test.js tests/circuit-breaker.test.js'
}

SERVICIOS_TODOS=(empleados departamentos auth perfiles vacaciones notificaciones gateway)
SERVICIOS=("$@")
[ ${#SERVICIOS[@]} -eq 0 ] && SERVICIOS=("${SERVICIOS_TODOS[@]}")

trap limpiar EXIT
levantar_infra

fallidos=()
for s in "${SERVICIOS[@]}"; do
  echo "=== Integración: $s"
  if "it_$s"; then echo "=== $s: OK"; else echo "=== $s: FALLÓ"; fallidos+=("$s"); fi
done

if [ ${#fallidos[@]} -gt 0 ]; then
  echo "Suites de integración fallidas: ${fallidos[*]}" >&2
  exit 1
fi
echo "Integración correcta en: ${SERVICIOS[*]}"
