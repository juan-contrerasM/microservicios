#!/bin/bash
# Ajusta el grupo del socket al GID real del host y arranca Jenkins como usuario jenkins.
set -euo pipefail

SOCK=/var/run/docker.sock
if [ ! -S "$SOCK" ]; then
  echo "No está ${SOCK}. El plan monta el socket del host en el servicio jenkins." >&2
  exit 1
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

exec /usr/bin/tini -- runuser -u jenkins -- /usr/local/bin/jenkins.sh "$@"
