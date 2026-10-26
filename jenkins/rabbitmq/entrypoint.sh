#!/bin/sh
# El volumen de /var/lib/rabbitmq en Docker Desktop deja .erlang.cookie como
# root y el proceso no puede leerla. La cookie que usa el broker vive en /tmp,
# donde chown sí aplica. El entrypoint va dentro de la imagen: el daemon no ve
# el workspace de Jenkins, así que un bind mount no llega al contenedor.
set -eu
home=/tmp/rabbit-home
mkdir -p "$home/mnesia" "$home/log" /etc/rabbitmq/conf.d
if [ -n "${RABBITMQ_DEFAULT_USER:-}" ] && [ -n "${RABBITMQ_DEFAULT_PASS:-}" ]; then
  printf 'default_user = %s\ndefault_pass = %s\n' \
    "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS" \
    > /etc/rabbitmq/conf.d/10-defaults.conf
fi
cookie=${RABBITMQ_ERLANG_COOKIE:-microservicios-ci}
printf '%s' "$cookie" > "$home/.erlang.cookie"
chown -R rabbitmq:rabbitmq "$home"
chmod 600 "$home/.erlang.cookie"
export HOME="$home"
export RABBITMQ_MNESIA_BASE="$home/mnesia"
export RABBITMQ_LOG_BASE="$home/log"
export RABBITMQ_ERLANG_COOKIE="$cookie"
echo "ci-rabbit listo"
exec gosu rabbitmq rabbitmq-server
