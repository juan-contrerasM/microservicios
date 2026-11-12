#!/usr/bin/env bash
# Fase 12 (12.DEMO): escalado horizontal, reparto de tráfico y continuidad ante la caída de una réplica.
#
#   bash scripts/demo-escalado.sh            # proyecto Compose = COMPOSE_PROJECT (por defecto microservicios)
#
# 1. Escala api-gateway, empleados-service, departamentos-service y vacaciones-service a 2 réplicas.
# 2. Envía N peticiones por el balanceador (:8080) y muestra cuántas atendió cada réplica, leyendo
#    http_requests_total directamente del /metrics de cada contenedor.
# 3. Detiene una réplica de cada servicio y repite: el cliente no debe ver ningún error.
# 4. Vuelve a 1 réplica.
# Sale con 1 si hubo respuestas distintas de 2xx.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PROYECTO="${COMPOSE_PROJECT:-microservicios}"
BASE="${BASE_URL:-http://localhost:8080}"
N="${PETICIONES:-60}"
SERVICIOS=(api-gateway empleados-service departamentos-service vacaciones-service)

compose() { docker compose --project-directory "$RAIZ" -p "$PROYECTO" "$@"; }
contenedores() { docker ps -q --filter "label=com.docker.compose.project=$PROYECTO" --filter "label=com.docker.compose.service=$1"; }

esperar_sanos() {
  local servicio=$1 cantidad=$2
  for _ in $(seq 1 90); do
    local sanos=0
    for c in $(contenedores "$servicio"); do
      [ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = healthy ] && sanos=$((sanos + 1))
    done
    [ "$sanos" -ge "$cantidad" ] && return 0
    sleep 3
  done
  echo "$servicio no llegó a $cantidad réplicas sanas" >&2
  return 1
}

# Peticiones atendidas por cada réplica según su propio contador (la ruta es la de negocio).
reparto() {
  local servicio=$1 puerto=$2 ruta=$3
  for c in $(contenedores "$servicio"); do
    local nombre total
    nombre="$(docker inspect -f '{{.Name}}' "$c" | tr -d /)"
    total="$(docker exec "$c" sh -c "wget -qO- http://localhost:$puerto/metrics 2>/dev/null || curl -s http://localhost:$puerto/metrics" \
      | grep '^http_requests_total' | grep "route=\"$ruta\"" | grep 'method="GET"' \
      | awk '{s += $NF} END {print s + 0}')"
    printf '    %-40s %s peticiones GET %s\n' "$nombre" "$total" "$ruta"
  done
}

rafaga() {
  local etiqueta=$1 token=$2 ok=0 error=0 codigo
  for _ in $(seq 1 "$N"); do
    for ruta in /departamentos /vacaciones; do
      codigo="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE$ruta")"
      case "$codigo" in 2??) ok=$((ok + 1)) ;; *) error=$((error + 1)) ;; esac
    done
  done
  echo "  $etiqueta: $ok respuestas 2xx, $error errores"
  ERRORES=$((ERRORES + error))
}

ERRORES=0
clave="$(cat "$RAIZ/secrets/auth_admin_password.txt")"
token="$(printf '{"usuario":"%s","contrasena":"%s"}' "${AUTH_ADMIN_USUARIO:-admin}" "$clave" \
  | curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' --data-binary @- \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

echo "1) Escalando a 2 réplicas: ${SERVICIOS[*]}"
escala=()
for s in "${SERVICIOS[@]}"; do escala+=(--scale "$s=2"); done
compose up -d --no-build --no-recreate "${escala[@]}" "${SERVICIOS[@]}" >/dev/null 2>&1
for s in "${SERVICIOS[@]}"; do esperar_sanos "$s" 2; done
sleep 8  # el health check de Traefik (5 s) incorpora las réplicas nuevas
echo "   Réplicas en el balanceador:"
docker exec "$(contenedores balanceador | head -n1)" wget -qO- http://localhost:8082/metrics \
  | grep '^traefik_service_server_up' | grep -E 'gateway|empleados|departamentos|vacaciones' \
  | sed 's/traefik_service_server_up{service="\([^"]*\)",url="\([^"]*\)"} \(.*\)/     \1 \2 up=\3/'

echo "2) $((N * 2)) peticiones por el balanceador"
rafaga "con 2 réplicas" "$token"
echo "   Reparto por réplica:"
reparto departamentos-service 8081 /departamentos
reparto vacaciones-service 8085 /vacaciones

echo "3) Deteniendo una réplica de cada servicio, sin avisar al balanceador"
for s in "${SERVICIOS[@]}"; do docker stop -t 1 "$(contenedores "$s" | head -n1)" >/dev/null; done
rafaga "con 1 réplica caída" "$token"

echo "4) Volviendo a 1 réplica"
reducir=()
for s in "${SERVICIOS[@]}"; do reducir+=(--scale "$s=1"); done
compose up -d --no-build --no-recreate "${reducir[@]}" "${SERVICIOS[@]}" >/dev/null 2>&1
for s in "${SERVICIOS[@]}"; do esperar_sanos "$s" 1; done

if [ "$ERRORES" -gt 0 ]; then
  echo "Hubo $ERRORES respuestas con error" >&2
  exit 1
fi
echo "Escalado correcto: tráfico repartido y sin errores al caer una réplica."
