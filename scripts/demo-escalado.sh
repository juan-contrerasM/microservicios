#!/usr/bin/env bash
# Fase 12 (12.DEMO): escalado horizontal de los SIETE servicios, reparto de tráfico y continuidad
# ante la caída de una réplica.
#
#   COMPOSE_PROJECT=<proyecto> bash scripts/demo-escalado.sh
#
# 1. Escala los siete servicios a 2 réplicas y espera a que todas estén healthy.
# 2. Comprueba que el balanceador ve 2 réplicas sanas de cada uno y que las cinco colas de
#    consumidores (auth, perfiles, notificaciones, vacaciones, empleados) tienen 2 consumidores.
# 3. Envía N peticiones por servicio a través del balanceador (:8080) y muestra cuántas atendió cada
#    réplica, leyendo el contador HTTP del propio contenedor (route en Go/Node, uri en Java,
#    handler en Python).
# 4. Detiene una réplica de cada servicio sin avisar y repite: el cliente no debe ver errores.
# 5. Vuelve a 1 réplica. Sale con 1 si algo falló.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PROYECTO="${COMPOSE_PROJECT:-microservicios}"
BASE="${BASE_URL:-http://localhost:8080}"
N="${PETICIONES:-30}"
SERVICIOS=(api-gateway empleados-service departamentos-service auth-service perfiles-service vacaciones-service notificaciones-service)
FALLOS=0

compose() { docker compose --project-directory "$RAIZ" -p "$PROYECTO" "$@"; }
contenedores() { docker ps -q --filter "label=com.docker.compose.project=$PROYECTO" --filter "label=com.docker.compose.service=$1"; }
mal() { echo "  ✖ $1" >&2; FALLOS=$((FALLOS + 1)); }

esperar_sanos() {
  local servicio=$1 cantidad=$2
  for _ in $(seq 1 100); do
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

# Petición de negocio usada para cada servicio (por el balanceador) y cómo contarla en su réplica.
#   servicio | método | ruta | puerto métricas | ruta métricas | filtro 1 | filtro 2
TABLA=(
  "api-gateway|GET|/departamentos|8080|/metrics|^http_requests_total|route=\"/departamentos\""
  "empleados-service|GET|/empleados|8080|/actuator/prometheus|^http_server_requests_seconds_count|uri=\"/empleados\""
  "departamentos-service|GET|/departamentos|8081|/metrics|^http_requests_total|route=\"/departamentos\""
  "auth-service|POST|/auth/login|8086|/metrics|^http_requests_total|handler=\"/auth/login\""
  "perfiles-service|GET|/perfiles|8083|/actuator/prometheus|^http_server_requests_seconds_count|uri=\"/perfiles\""
  "vacaciones-service|GET|/vacaciones|8085|/metrics|^http_requests_total|route=\"/vacaciones\""
  "notificaciones-service|GET|/notificaciones|8084|/metrics|^http_requests_total|handler=\"/notificaciones\""
)

clave="$(cat "$RAIZ/secrets/auth_admin_password.txt")"
login_json="$(printf '{"usuario":"%s","contrasena":"%s"}' "${AUTH_ADMIN_USUARIO:-admin}" "$clave")"
token="$(printf '%s' "$login_json" | curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' --data-binary @- \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

peticion() { # peticion <método> <ruta> → código HTTP
  if [ "$1" = POST ]; then
    printf '%s' "$login_json" | curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE$2" -H 'Content-Type: application/json' --data-binary @-
  else
    curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE$2"
  fi
}

rafaga() {
  local etiqueta=$1 ok=0 error=0
  for fila in "${TABLA[@]}"; do
    IFS='|' read -r _ metodo ruta _ _ _ _ <<<"$fila"
    for _ in $(seq 1 "$N"); do
      case "$(peticion "$metodo" "$ruta")" in 2??) ok=$((ok + 1)) ;; *) error=$((error + 1)) ;; esac
    done
  done
  echo "  $etiqueta: $ok respuestas 2xx, $error errores"
  [ "$error" -eq 0 ] || mal "$etiqueta: $error respuestas con error"
}

reparto() {
  for fila in "${TABLA[@]}"; do
    IFS='|' read -r servicio _ ruta puerto metricas filtro1 filtro2 <<<"$fila"
    local linea="    $servicio ($ruta):"
    for c in $(contenedores "$servicio"); do
      local total
      total="$(docker exec "$c" sh -c "curl -s http://localhost:$puerto$metricas 2>/dev/null || wget -qO- http://localhost:$puerto$metricas" \
        | grep -E "$filtro1" | grep -F "$filtro2" | awk '{s += $NF} END {printf "%d", s}')"
      linea="$linea  $(docker inspect -f '{{.Name}}' "$c" | tr -d / | sed 's/.*-\([0-9]*\)$/réplica \1/')=$total"
    done
    echo "$linea"
  done
}

echo "1) Escalando los siete servicios a 2 réplicas"
escala=(); for s in "${SERVICIOS[@]}"; do escala+=(--scale "$s=2"); done
compose up -d --no-build --no-recreate "${escala[@]}" "${SERVICIOS[@]}" >/dev/null 2>&1
for s in "${SERVICIOS[@]}"; do esperar_sanos "$s" 2 || mal "$s sin 2 réplicas sanas"; done
sleep 10  # health check de Traefik (5 s) y reconexión de los consumidores

echo "2) Réplicas sanas en el balanceador y consumidores por cola"
lb="$(contenedores balanceador | head -n1)"
docker exec "$lb" wget -qO- http://localhost:8082/metrics | grep '^traefik_service_server_up' \
  | sed -n 's/traefik_service_server_up{service="\([a-z]*\)@docker".*} 1$/\1/p' | sort | uniq -c \
  | while read -r n s; do echo "    $s: $n réplicas sanas"; [ "$n" -ge 2 ] || echo "    ✖ $s con menos de 2" >&2; done
rabbit="$(cat "$RAIZ/secrets/rabbitmq_password.txt")"
for cola in q.auth q.perfiles q.notificaciones q.vacaciones q.empleados; do
  consumidores="$(curl -s -u "${RABBITMQ_USER:-onboarding}:$rabbit" "http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}/api/queues/%2F/$cola" \
    | sed -n 's/.*"consumers":\([0-9]*\).*/\1/p')"
  echo "    $cola: ${consumidores:-?} consumidores"
  [ "${consumidores:-0}" -ge 2 ] || mal "$cola no tiene 2 consumidores"
done

echo "3) $N peticiones por servicio a través del balanceador"
rafaga "con 2 réplicas"
echo "   Peticiones atendidas por cada réplica (contador acumulado del contenedor):"
reparto

echo "4) Deteniendo una réplica de cada servicio, sin avisar al balanceador"
for s in "${SERVICIOS[@]}"; do docker stop -t 1 "$(contenedores "$s" | head -n1)" >/dev/null; done
rafaga "con 1 réplica de cada servicio caída"

echo "5) Volviendo a 1 réplica"
reducir=(); for s in "${SERVICIOS[@]}"; do reducir+=(--scale "$s=1"); done
compose up -d --no-build --no-recreate "${reducir[@]}" "${SERVICIOS[@]}" >/dev/null 2>&1
for s in "${SERVICIOS[@]}"; do esperar_sanos "$s" 1 || mal "$s no volvió a quedar sano"; done

if [ "$FALLOS" -gt 0 ]; then echo "Escalado: $FALLOS verificaciones fallidas" >&2; exit 1; fi
echo "Escalado correcto: los siete servicios con 2 réplicas, tráfico repartido y sin errores al caer una réplica."
