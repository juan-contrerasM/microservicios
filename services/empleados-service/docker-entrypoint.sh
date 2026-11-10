#!/bin/sh
set -eu

load_secret() {
  name="$1"
  eval "file=\${${name}_FILE:-}"
  if [ -n "$file" ]; then
    [ -r "$file" ] || { echo "No se puede leer ${name}_FILE" >&2; exit 1; }
    value=$(tr -d '\r\n' < "$file")
    export "$name=$value"
  fi
}

load_secret DB_PASSWORD
load_secret RABBITMQ_PASSWORD
exec java -javaagent:/app/opentelemetry-javaagent.jar -jar /app/app.jar
