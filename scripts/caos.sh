#!/usr/bin/env bash
# Fase 16: pruebas de caos con verificación automática. Cada escenario rompe una dependencia,
# comprueba el comportamiento esperado y la restaura.
#
#   COMPOSE_PROJECT=microservicios bash scripts/caos.sh
#
#   1. Base de datos caída: /health de Empleados responde 503 en < 3 s y vuelve a 200 al restaurarla.
#   2. Broker caído: los consumidores reportan messageBroker DOWN; el balanceador saca a Empleados
#      de rotación y el alta falla rápido (503 C3); al volver el broker todo se reconecta y el alta da 201.
#   3. Departamentos caído: el alta queda PENDIENTE_VALIDACION (circuit breaker de Empleados) y el
#      Gateway responde 503 C3 para /departamentos.
#   4. Réplica caída: ver scripts/demo-escalado.sh (reparto y continuidad sin errores).
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PROYECTO="${COMPOSE_PROJECT:-microservicios}"
BASE="${BASE_URL:-http://localhost:8080}"
FALLOS=0

compose() { docker compose --project-directory "$RAIZ" -p "$PROYECTO" "$@"; }
contenedor() { compose ps -q "$1" | head -n1; }
ok() { echo "  ✔ $1"; }
mal() { echo "  ✖ $1" >&2; FALLOS=$((FALLOS + 1)); }

salud() { # salud <servicio> <puerto> → "<código> <segundos>"
  local salida
  salida="$(docker exec "$(contenedor "$1")" curl -s -o /dev/null -w '%{http_code} %{time_total}' -m 5 "http://localhost:$2/health" 2>/dev/null || true)"
  echo "${salida:-000 5}"
}

esperar_sano() {
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(contenedor "$1")" 2>/dev/null)" = healthy ] && return 0
    sleep 3
  done
  return 1
}

token="$(printf '{"usuario":"%s","contrasena":"%s"}' "${AUTH_ADMIN_USUARIO:-admin}" "$(cat "$RAIZ/secrets/auth_admin_password.txt")" \
  | curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' --data-binary @- \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
api() { curl -s -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"; }
sufijo="$(date +%s)"
api -X POST "$BASE/departamentos" -d "{\"id\":\"CAOS-$sufijo\",\"nombre\":\"Caos\"}" >/dev/null
alta() { # alta <id> → cuerpo de la respuesta
  printf '{"id":"%s","nombre":"Caos","apellido":"Prueba","email":"%s@caos.com","numeroEmpleado":"N-%s","cargo":"QA","area":"TI","departamentoId":"CAOS-%s","fechaIngreso":"2026-01-15","estado":"ACTIVO"}' \
    "$1" "$(echo "$1" | tr 'A-Z' 'a-z')" "$1" "$sufijo" | api -X POST "$BASE/empleados" --data-binary @- -w ' HTTP%{http_code}'
}

echo "1) PostgreSQL de Empleados caído"
compose stop database-empleados >/dev/null 2>&1
read -r codigo segundos <<<"$(salud empleados-service 8080)"
if [ "$codigo" = 503 ] && awk "BEGIN{exit !($segundos < 3)}"; then ok "/health → 503 en ${segundos}s"; else mal "/health → $codigo en ${segundos}s"; fi
compose start database-empleados >/dev/null 2>&1
# El healthcheck de Docker tarda en cambiar de estado: se espera a que el propio /health dé 200.
recuperado=no
for _ in $(seq 1 30); do [ "$(salud empleados-service 8080 | cut -d' ' -f1)" = 200 ] && { recuperado=si; break; }; sleep 3; done
[ "$recuperado" = si ] && ok "recuperado: /health → 200" || mal "no se recuperó"

echo "2) RabbitMQ caído"
compose stop message-broker >/dev/null 2>&1
sleep 8
if [ "$(salud perfiles-service 8083 | cut -d' ' -f1)" = 503 ]; then ok "Perfiles reporta messageBroker DOWN (503)"; else mal "Perfiles no detectó el broker caído"; fi
# Sin broker, el /health de Empleados reporta messageBroker DOWN y el balanceador lo saca de
# rotación: el alta falla rápido con 503 C3 en vez de aceptar un empleado cuyo evento de onboarding
# se perdería (no hay outbox). Es fail-fast deliberado; ver docs/arquitectura.md §5.
sleep 6
respuesta="$(alta "CB$sufijo")"
case "$respuesta" in *'"status":503'*HTTP503) ok "alta sin broker → 503 C3 inmediato (no se aceptan altas cuyo evento se perdería)" ;; *) mal "alta sin broker: $respuesta" ;; esac
compose start message-broker >/dev/null 2>&1
if esperar_sano message-broker && esperar_sano perfiles-service && esperar_sano auth-service && esperar_sano empleados-service; then ok "consumidores reconectados y sanos"; else mal "los consumidores no se recuperaron"; fi
# Durante la caída el circuito del Gateway hacia Empleados se abrió: queda OPEN CB_OPEN_DURATION_MS
# (30 s) y luego deja pasar la prueba HALF_OPEN. Se reintenta el alta hasta que el circuito cierra.
alta_hasta_201() {
  for i in $(seq 1 15); do
    respuesta="$(alta "$1$i")"
    case "$respuesta" in *HTTP201) return 0 ;; esac
    sleep 5
  done
  return 1
}
alta_hasta_201 "CR$sufijo"
case "$respuesta" in *HTTP201) ok "con el broker de vuelta el alta responde 201" ;; *) mal "alta tras recuperar el broker: $respuesta" ;; esac

echo "3) Departamentos caído"
compose stop departamentos-service >/dev/null 2>&1
sleep 2
respuesta="$(alta "CD$sufijo")"
case "$respuesta" in *PENDIENTE_VALIDACION*HTTP201) ok "alta registrada como PENDIENTE_VALIDACION" ;; *) mal "alta con Departamentos caído: $respuesta" ;; esac
codigo="$(api -o /dev/null -w '%{http_code}' "$BASE/departamentos")"
[ "$codigo" = 503 ] && ok "GET /departamentos → 503 C3" || mal "GET /departamentos → $codigo"
compose start departamentos-service >/dev/null 2>&1
esperar_sano departamentos-service && ok "Departamentos recuperado" || mal "Departamentos no se recuperó"
api -X POST "$BASE/empleados/reconciliar" -o /dev/null -w '' && ok "reconciliación de pendientes lanzada"

echo
if [ "$FALLOS" -gt 0 ]; then echo "Caos: $FALLOS verificaciones fallidas" >&2; exit 1; fi
echo "Caos: todas las verificaciones pasaron"
