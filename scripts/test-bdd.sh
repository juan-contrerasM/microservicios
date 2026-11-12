#!/usr/bin/env bash
# Fase 8 (8.BDD): ejecuta la suite Cucumber de e2e-tests/ contra el stack levantado con
# `docker compose up`, dentro de un contenedor Node (el host no necesita Node).
#
# - Se une a las redes del Gateway y de RabbitMQ, así habla por DNS de Compose.
# - Lee las credenciales de secrets/ (nunca de variables escritas en Git).
# - Monta el socket de Docker para los escenarios @caos (detener y arrancar un servicio).
#
# Uso:   bash scripts/test-bdd.sh            # una ejecución
#        bash scripts/test-bdd.sh 3          # tres ejecuciones seguidas; falla si alguna falla
# Variables: COMPOSE_PROJECT_NAME (default: el del directorio), CUCUMBER_TAGS (p. ej. "not @caos").
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
VECES="${1:-1}"
export MSYS_NO_PATHCONV=1
ruta_host() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

contenedor_de() {
  docker ps -q --filter "label=com.docker.compose.service=$1" \
    ${COMPOSE_PROJECT_NAME:+--filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"} | head -n1
}
GATEWAY="$(contenedor_de api-gateway)"
BROKER="$(contenedor_de message-broker)"
[ -n "$GATEWAY" ] || { echo "El api-gateway no está corriendo: levanta el stack con docker compose up" >&2; exit 1; }
PROYECTO="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$GATEWAY")"

redes() { docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$1"; }
mapfile -t REDES < <( { redes "$GATEWAY"; [ -n "$BROKER" ] && redes "$BROKER"; } | tr ' ' '\n' | sed '/^$/d' | sort -u)

NOMBRE="pf-bdd-$$"
docker create --name "$NOMBRE" --network "${REDES[0]}" \
  -v "$(ruta_host "$RAIZ/e2e-tests"):/e2e" -v /e2e/node_modules \
  -v "$(ruta_host "$RAIZ/secrets"):/secrets:ro" \
  -v //var/run/docker.sock:/var/run/docker.sock \
  -e BASE_URL=http://api-gateway:8080 -e COMPOSE_PROJECT="$PROYECTO" \
  -e RABBITMQ_MGMT_URL=http://message-broker:15672 \
  -e RABBITMQ_USER="${RABBITMQ_USER:-onboarding}" -e RABBITMQ_PASSWORD_FILE=/secrets/rabbitmq_password.txt \
  -e ADMIN_USUARIO="${AUTH_ADMIN_USUARIO:-admin}" -e VECES="$VECES" -e CUCUMBER_TAGS="${CUCUMBER_TAGS:-}" \
  -w /e2e node:22.21.1-alpine3.22 sh -c '
    set -e
    export ADMIN_PASSWORD="$(cat /secrets/auth_admin_password.txt)"
    export USER_PASSWORD="$(cat /secrets/e2e_user_password.txt)"
    npm ci --no-audit --no-fund -q
    for i in $(seq 1 "$VECES"); do
      echo "=== Ejecución BDD $i/$VECES"
      if [ -n "$CUCUMBER_TAGS" ]; then npm test -- --tags "$CUCUMBER_TAGS"; else npm test; fi
      cp reports/junit.xml "reports/junit-ejecucion-$i.xml"
    done' >/dev/null
trap 'docker rm -f "$NOMBRE" >/dev/null 2>&1 || true' EXIT
# `docker create` conecta la primera red; las demás se agregan antes de arrancar.
for red in "${REDES[@]:1}"; do docker network connect "$red" "$NOMBRE"; done
docker start -a "$NOMBRE"
