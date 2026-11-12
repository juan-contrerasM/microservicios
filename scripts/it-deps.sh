#!/usr/bin/env bash
# Fase 8/9 (E14): dependencias reales y efímeras para las pruebas de integración.
#
#   it-deps.sh up <prefijo> <red> <archivo-env>   levanta PostgreSQL, MySQL, RabbitMQ y Mailhog
#                                                  en <red> (debe existir) y escribe sus datos de
#                                                  conexión en <archivo-env> (formato KEY=valor)
#   it-deps.sh down <prefijo>                      borra los contenedores
#
# Lo usan scripts/test-integration.sh (local, red pf-it) y los Jenkinsfile (red cicd). Las
# credenciales son aleatorias en cada ejecución: los contenedores solo viven durante la prueba.
set -euo pipefail

PG_IMAGEN=postgres:16.15-alpine
MYSQL_IMAGEN=mysql:8.4.13
RABBIT_IMAGEN=rabbitmq:3.13.7-management-alpine
MAILHOG_IMAGEN=mailhog/mailhog:v1.0.1

aleatorio() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

esperar() {
  local nombre=$1; shift
  for _ in $(seq 1 90); do
    if docker exec "$nombre" "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "La dependencia $nombre no quedó lista" >&2
  return 1
}

down() {
  local p=$1
  docker rm -f "$p-pg" "$p-mysql" "$p-rabbit" "$p-mail" >/dev/null 2>&1 || true
}

up() {
  local p=$1 red=$2 archivo=$3 clave
  clave="it$(aleatorio 12)"
  down "$p"
  docker run -d --rm --name "$p-pg" --network "$red" -e POSTGRES_PASSWORD="$clave" "$PG_IMAGEN" >/dev/null
  docker run -d --rm --name "$p-mysql" --network "$red" -e MYSQL_ROOT_PASSWORD="$clave" \
    -e MYSQL_DATABASE=departamentos_it "$MYSQL_IMAGEN" >/dev/null
  docker run -d --rm --name "$p-rabbit" --network "$red" -e RABBITMQ_DEFAULT_USER=it \
    -e RABBITMQ_DEFAULT_PASS="$clave" "$RABBIT_IMAGEN" >/dev/null
  docker run -d --rm --name "$p-mail" --network "$red" "$MAILHOG_IMAGEN" >/dev/null
  esperar "$p-pg" pg_isready -U postgres
  esperar "$p-mysql" mysqladmin ping -uroot -p"$clave" --silent
  esperar "$p-rabbit" rabbitmq-diagnostics -q check_port_connectivity
  for db in vacaciones auth notificaciones; do
    docker exec "$p-pg" psql -qU postgres -c "CREATE DATABASE ${db}_it" >/dev/null
  done
  umask 077
  cat > "$archivo" <<EOF
IT_PASS=$clave
IT_PG_HOST=$p-pg
IT_MYSQL_HOST=$p-mysql
IT_RABBIT_HOST=$p-rabbit
IT_MAIL_HOST=$p-mail
EOF
}

case "${1:-}" in
  up) up "$2" "$3" "$4" ;;
  down) down "$2" ;;
  *) echo "Uso: $0 up <prefijo> <red> <archivo-env> | down <prefijo>" >&2; exit 2 ;;
esac
