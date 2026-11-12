#!/usr/bin/env bash
# Fase 14: datos de prueba idempotentes, cargados a través del API Gateway (nunca por SQL cruzado).
#
# Crea dos departamentos y tres empleados si no existen. Volver a ejecutarlo no duplica nada:
# cada recurso se consulta antes y solo se crea ante un 404. Las cuentas, perfiles y correos de
# bienvenida los generan los propios servicios a partir de los eventos (onboarding real).
#
# Sustituye a un seed-data.sql: cada servicio tiene su base y sus migraciones (Liquibase,
# golang-migrate, Alembic, node-pg-migrate), así que un SQL único rompería el aislamiento.
#
# Uso:   bash scripts/seed-data.sh                       # contra http://localhost:8080
#        BASE_URL=http://otro:8080 bash scripts/seed-data.sh
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_USUARIO="${AUTH_ADMIN_USUARIO:-admin}"
ADMIN_PASSWORD="${AUTH_ADMIN_PASSWORD:-$(cat "$RAIZ/secrets/auth_admin_password.txt")}"

json_campo() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

token="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"usuario\":\"$ADMIN_USUARIO\",\"contrasena\":\"$ADMIN_PASSWORD\"}" | json_campo token)"
[ -n "$token" ] || { echo "No se pudo iniciar sesión como $ADMIN_USUARIO en $BASE_URL" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')

# asegurar <ruta-colección> <id> <json>: GET /<ruta>/<id>; si da 404, POST /<ruta>.
asegurar() {
  local ruta=$1 id=$2 cuerpo=$3 estado
  estado="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE_URL/$ruta/$id")"
  case "$estado" in
    200) echo "= $ruta/$id ya existe" ;;
    404)
      # El cuerpo va por stdin: en Git Bash para Windows un -d en la línea de comandos pasa por la
      # página de códigos ANSI y rompe los acentos (el servicio responde 400 JSON inválido).
      estado="$(printf '%s' "$cuerpo" | curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" \
        "$BASE_URL/$ruta" --data-binary @-)"
      [ "$estado" = 201 ] || { echo "POST /$ruta ($id) respondió $estado" >&2; exit 1; }
      echo "+ $ruta/$id creado" ;;
    *) echo "GET /$ruta/$id respondió $estado" >&2; exit 1 ;;
  esac
}

asegurar departamentos SEED-TI '{"id":"SEED-TI","nombre":"Tecnología","descripcion":"Datos de prueba"}'
asegurar departamentos SEED-RH '{"id":"SEED-RH","nombre":"Recursos Humanos","descripcion":"Datos de prueba"}'

empleado() {
  local id=$1 nombre=$2 apellido=$3 depto=$4
  asegurar empleados "$id" "{\"id\":\"$id\",\"nombre\":\"$nombre\",\"apellido\":\"$apellido\",\
\"email\":\"$(echo "$id" | tr '[:upper:]' '[:lower:]')@empresa.com\",\"numeroEmpleado\":\"N-$id\",\
\"cargo\":\"Analista\",\"area\":\"$depto\",\"departamentoId\":\"$depto\",\"fechaIngreso\":\"2026-01-15\",\"estado\":\"ACTIVO\"}"
}
empleado SEED-E001 Ana Pérez SEED-TI
empleado SEED-E002 Luis Gómez SEED-TI
empleado SEED-E003 Marta Díaz SEED-RH

echo "Semilla aplicada en $BASE_URL. Los enlaces de activación llegan a Mailhog (http://localhost:8025)."
