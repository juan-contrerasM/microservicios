#!/usr/bin/env bash
# Fase 10: despliegue de una imagen del Registry por tag inmutable, smoke test y rollback.
#
#   bash scripts/deploy.sh <servicio> <tag>      despliega REGISTRY/<servicio>:<tag>
#   bash scripts/deploy.sh rollback <servicio>   vuelve al tag desplegado antes del actual
#   bash scripts/deploy.sh historial <servicio>  muestra los despliegues registrados
#
# Despliega con docker-compose.deploy.yml sobre el proyecto Compose indicado en COMPOSE_PROJECT
# (por defecto `microservicios`, el nombre del compose). Solo recrea ese servicio (--no-deps).
# Smoke test: el healthcheck del contenedor debe quedar `healthy` (su /health real) y el Gateway
# debe seguir respondiendo. Si el smoke falla, se redespliega automáticamente el tag anterior.
# El historial vive en .deploy/<servicio>.log (ignorado por Git): fecha, tag y digest.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PROYECTO="${COMPOSE_PROJECT:-microservicios}"
REGISTRY="${REGISTRY:-localhost:5001}"
HISTORIAL="${DEPLOY_HISTORIAL:-$RAIZ/.deploy}"
ESPERA_S="${DEPLOY_TIMEOUT_S:-240}"
# Desde Jenkins: ENV_FILE=/workspace/.env (el workspace del job no tiene .env), HOST_REPO para los
# bind mounts (ver docker-compose.deploy.yml) y DEPLOY_HISTORIAL=/workspace/.deploy para compartir
# el historial con el repositorio del host.
ENV_FILE="${ENV_FILE:-}"
mkdir -p "$HISTORIAL"

compose() {
  docker compose --project-directory "$RAIZ" -p "$PROYECTO" ${ENV_FILE:+--env-file "$ENV_FILE"} \
    -f "$RAIZ/docker-compose.yml" -f "$RAIZ/docker-compose.deploy.yml" "$@"
}

variable_tag() { echo "$1" | tr 'a-z-' 'A-Z_' | sed 's/_SERVICE$//'; }

contenedor() { compose ps -q "$1" | head -n1; }

smoke() {
  local servicio=$1 id estado limite=$((SECONDS + ESPERA_S))
  while [ $SECONDS -lt $limite ]; do
    id="$(contenedor "$servicio")"
    estado="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo ausente)"
    case "$estado" in
      healthy)
        # El borde sigue atendiendo: el Gateway responde su /health a través de la red interna.
        if docker exec "$(contenedor api-gateway)" curl -fsS -m 5 http://localhost:8080/health >/dev/null; then
          echo "Smoke OK: $servicio healthy y Gateway responde"
          return 0
        fi ;;
      unhealthy|exited|dead) echo "Smoke FALLÓ: $servicio quedó $estado" >&2; return 1 ;;
    esac
    sleep 3
  done
  echo "Smoke FALLÓ: $servicio no quedó healthy en ${ESPERA_S}s" >&2
  return 1
}

desplegar() {
  local servicio=$1 tag=$2
  echo "Desplegando $REGISTRY/$servicio:$tag en el proyecto $PROYECTO"
  export REGISTRY "$(variable_tag "$servicio")_TAG=$tag"
  compose up -d --no-build --no-deps "$servicio"
}

registrar() {
  local servicio=$1 tag=$2 digest
  digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$REGISTRY/$servicio:$tag" 2>/dev/null || echo sin-digest)"
  printf '%s %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$tag" "$digest" >> "$HISTORIAL/$servicio.log"
}

tag_actual() { [ -f "$HISTORIAL/$1.log" ] && tail -n1 "$HISTORIAL/$1.log" | awk '{print $2}' || true; }
tag_anterior() { [ -f "$HISTORIAL/$1.log" ] && tail -n2 "$HISTORIAL/$1.log" | head -n1 | awk '{print $2}' || true; }

case "${1:-}" in
  rollback)
    servicio=$2
    anterior="$(tag_anterior "$servicio")"
    [ -n "$anterior" ] && [ "$(wc -l < "$HISTORIAL/$servicio.log")" -ge 2 ] \
      || { echo "No hay un despliegue anterior registrado para $servicio" >&2; exit 1; }
    desplegar "$servicio" "$anterior"
    smoke "$servicio"
    registrar "$servicio" "$anterior"
    echo "Rollback de $servicio a $anterior completado" ;;
  historial)
    cat "$HISTORIAL/$2.log" 2>/dev/null || echo "Sin despliegues registrados para $2" ;;
  ""|-h|--help)
    sed -n '2,13p' "$0" ;;
  *)
    servicio=$1 tag=${2:?indique el tag}
    previo="$(tag_actual "$servicio")"
    desplegar "$servicio" "$tag"
    if smoke "$servicio"; then
      registrar "$servicio" "$tag"
      echo "Despliegue de $servicio:$tag completado"
    else
      if [ -n "$previo" ]; then
        echo "Rollback automático de $servicio a $previo" >&2
        desplegar "$servicio" "$previo"
        smoke "$servicio" || true
      fi
      exit 1
    fi ;;
esac
