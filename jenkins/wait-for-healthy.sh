#!/bin/sh
# Espera a que el proyecto Compose micro-ci no tenga healthchecks en starting
# ni unhealthy. El /health del Gateway no basta: responde 200 con un backend caído.
set -eu

PROJECT=micro-ci
DEADLINE=$(($(date +%s) + 240))

while true; do
  lines="$(docker compose -p "$PROJECT" ps --format '{{.Service}} {{.State}} {{.Health}}' || true)"
  if [ -z "$lines" ]; then
    echo "No hay contenedores del proyecto ${PROJECT}" >&2
    exit 1
  fi

  bad="$(printf '%s\n' "$lines" | awk '
    NF >= 2 {
      state = $2
      health = (NF >= 3 ? $3 : "")
      if (state != "running") print
      else if (health == "starting" || health == "unhealthy") print
    }
  ')"

  if [ -z "$bad" ]; then
    echo "Proyecto ${PROJECT} sano"
    printf '%s\n' "$lines"
    exit 0
  fi

  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "Timeout de 240s. Siguen sin estar sanos:" >&2
    printf '%s\n' "$bad" >&2
    exit 1
  fi

  sleep 5
done
