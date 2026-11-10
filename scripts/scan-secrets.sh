#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MODE='árbol actual'
GIT_OPTION='--no-git'
if [ "${1:-}" = '--history' ]; then
  MODE='historial completo'
  GIT_OPTION=''
fi

echo "Analizando secretos en $MODE..."
# shellcheck disable=SC2086
docker run --rm -v "$ROOT_DIR:/repo" ghcr.io/gitleaks/gitleaks:v8.24.3 \
  detect --source=/repo --config=/repo/.gitleaks.toml --redact --verbose --exit-code=1 $GIT_OPTION
echo "Sin secretos detectados en el $MODE."
