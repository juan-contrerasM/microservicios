#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_TEMPLATE="$ROOT_DIR/.env.example"
ENV_FILE="$ROOT_DIR/.env"
SECRET_DIR="$ROOT_DIR/secrets"

if [ -f "$ENV_FILE" ] && [ "${1:-}" != "--force" ]; then
  echo '.env ya existe. Use --force únicamente si desea rotar todos los secretos.' >&2
  exit 1
fi

secret_names='EMPLEADOS_DB_PASSWORD DEPARTAMENTOS_DB_PASSWORD DEPARTAMENTOS_DB_ROOT_PASSWORD RABBITMQ_PASSWORD NOTIFICACIONES_DB_PASSWORD PERFILES_DB_PASSWORD VACACIONES_DB_PASSWORD AUTH_DB_PASSWORD JWT_SECRET AUTH_ADMIN_PASSWORD JENKINS_ADMIN_PASSWORD SONAR_ADMIN_PASSWORD SONAR_JDBC_PASSWORD E2E_USER_PASSWORD GRAFANA_ADMIN_PASSWORD'

mkdir -p "$SECRET_DIR"
cp "$ENV_TEMPLATE" "$ENV_FILE"
for name in $secret_names; do
  value="Aa1!$(openssl rand -hex 32)"
  escaped=$(printf '%s' "$value" | sed 's/[&|]/\\&/g')
  sed "s|^${name}=__GENERATE__$|${name}=${escaped}|" "$ENV_FILE" > "$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
  printf '%s' "$value" > "$SECRET_DIR/$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]').txt"
done

auth_admin=$(cat "$SECRET_DIR/auth_admin_password.txt")
e2e_user=$(cat "$SECRET_DIR/e2e_user_password.txt")
{
  printf '%s\n' 'BASE_URL=http://localhost:8080' 'ADMIN_USUARIO=admin'
  printf 'ADMIN_PASSWORD=%s\nUSER_PASSWORD=%s\n' "$auth_admin" "$e2e_user"
  printf '%s\n' 'POLL_INTENTOS=15' 'POLL_INTERVALO_MS=2000'
} > "$ROOT_DIR/e2e-tests/.env"

chmod 600 "$ENV_FILE" "$ROOT_DIR/e2e-tests/.env" "$SECRET_DIR"/*.txt 2>/dev/null || true
echo 'Configuración local creada en .env y secrets/.'
