#!/bin/bash
# Ajusta el grupo del socket al GID real del host y arranca Jenkins como usuario jenkins.
set -euo pipefail

load_secret() {
  name="$1"
  eval "file=\${${name}_FILE:-}"
  if [ -n "$file" ]; then
    [ -r "$file" ] || { echo "No se puede leer ${name}_FILE" >&2; exit 1; }
    value="$(tr -d '\r\n' < "$file")"
    export "$name=$value"
  fi
}

load_secret JENKINS_ADMIN_PASSWORD
load_secret ADMIN_PASSWORD
load_secret USER_PASSWORD

SOCK=/var/run/docker.sock
if [ ! -S "$SOCK" ]; then
  echo "No está ${SOCK}. El plan monta el socket del host en el servicio jenkins." >&2
  exit 1
fi

# Fase 9: la red cicd se llama <proyecto>_cicd-network y el proyecto cambia con `-p`. Con
# DOCKER_NETWORK=auto se toma la red cicd a la que Compose conectó este contenedor.
if [ "${DOCKER_NETWORK:-auto}" = auto ]; then
  DOCKER_NETWORK="$(docker inspect "$(hostname)" \
    --format '{{range $red, $_ := .NetworkSettings.Networks}}{{$red}} {{end}}' \
    | tr ' ' '\n' | grep -- 'cicd-network$' | head -n1)"
  [ -n "$DOCKER_NETWORK" ] || { echo "No se encontró la red cicd del contenedor" >&2; exit 1; }
  export DOCKER_NETWORK
  echo "DOCKER_NETWORK=${DOCKER_NETWORK}"
fi

DOCKER_GID="$(stat -c '%g' "$SOCK")"
export DOCKER_GID
if ! getent group "${DOCKER_GID}" >/dev/null; then
  groupadd -o -g "${DOCKER_GID}" dockerhost
fi
usermod -aG "${DOCKER_GID}" jenkins

if ! runuser -u jenkins -- git config --global --get-all safe.directory 2>/dev/null | grep -qx '\*'; then
  runuser -u jenkins -- git config --global --add safe.directory '*'
fi

if [ -s /run/sonar-bootstrap/token ]; then
  SONAR_TOKEN="$(tr -d '\r\n' < /run/sonar-bootstrap/token)"
  export SONAR_TOKEN
fi

exec /usr/bin/tini -- runuser -u jenkins -- /usr/local/bin/jenkins.sh "$@"
