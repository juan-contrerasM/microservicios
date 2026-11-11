#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_TEMPLATE="$ROOT_DIR/.env.example"
ENV_FILE="$ROOT_DIR/.env"
SECRET_DIR="$ROOT_DIR/secrets"

# --permisos: solo reaplica los permisos (p. ej. secretos creados con una versión
# anterior del script en 0600, ilegibles para los contenedores no root). No rota nada.
if [ "${1:-}" = "--permisos" ]; then
  [ -d "$SECRET_DIR" ] || { echo 'No existe secrets/. Ejecute el script sin argumentos.' >&2; exit 1; }
  chmod 700 "$SECRET_DIR"
  chmod 644 "$SECRET_DIR"/*.txt
  echo 'Permisos de secrets/ actualizados (directorio 0700, archivos 0644).'
  exit 0
fi

if [ -f "$ENV_FILE" ] && [ "${1:-}" != "--force" ]; then
  echo '.env ya existe. Use --force únicamente si desea rotar todos los secretos, o --permisos para solo corregir permisos.' >&2
  exit 1
fi

secret_names='EMPLEADOS_DB_PASSWORD DEPARTAMENTOS_DB_PASSWORD DEPARTAMENTOS_DB_ROOT_PASSWORD RABBITMQ_PASSWORD NOTIFICACIONES_DB_PASSWORD PERFILES_DB_PASSWORD VACACIONES_DB_PASSWORD AUTH_DB_PASSWORD JWT_SECRET AUTH_ADMIN_PASSWORD JENKINS_ADMIN_PASSWORD SONAR_ADMIN_PASSWORD SONAR_JDBC_PASSWORD E2E_USER_PASSWORD GRAFANA_ADMIN_PASSWORD'

# Todo se crea primero solo para el usuario actual (umask 077: directorios 0700,
# archivos 0600); los permisos definitivos de secrets/*.txt se fijan al final.
umask 077
mkdir -p "$SECRET_DIR"
chmod 700 "$SECRET_DIR"
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

# .env y e2e-tests/.env solo los lee el host: 0600.
# secrets/*.txt: Compose (sin Swarm) los monta como bind mount con los permisos
# del host y los servicios corren como UID 10001, distinto del dueño. Por eso
# los archivos quedan 0644 y la protección la da el directorio 0700: ningún otro
# usuario del host puede atravesar secrets/, y en el contenedor solo se ve el
# archivo montado en /run/secrets/<nombre>.
chmod 600 "$ENV_FILE" "$ROOT_DIR/e2e-tests/.env" 2>/dev/null || true
chmod 700 "$SECRET_DIR"
chmod 644 "$SECRET_DIR"/*.txt
echo 'Configuración local creada en .env y secrets/.'
