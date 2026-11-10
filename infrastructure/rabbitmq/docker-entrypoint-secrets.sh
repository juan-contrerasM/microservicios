#!/bin/sh
set -eu

if [ -n "${RABBITMQ_DEFAULT_PASS_FILE:-}" ]; then
  [ -r "$RABBITMQ_DEFAULT_PASS_FILE" ] || { echo 'No se puede leer el secreto de RabbitMQ' >&2; exit 1; }
  RABBITMQ_DEFAULT_PASS="$(tr -d '\r\n' < "$RABBITMQ_DEFAULT_PASS_FILE")"
  export RABBITMQ_DEFAULT_PASS
  unset RABBITMQ_DEFAULT_PASS_FILE
fi

exec docker-entrypoint.sh "$@"
