#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# Git Bash (Windows): docker.exe necesita la ruta nativa (C:/...) para el bind mount, y sin
# MSYS_NO_PATHCONV Git Bash reescribe /repo como C:/Program Files/Git/repo. En Linux y macOS
# `pwd -W` no existe y ninguna de las dos cosas cambia el comportamiento.
if ROOT_WIN=$(cd "$ROOT_DIR" && pwd -W 2>/dev/null); then
  ROOT_DIR="$ROOT_WIN"
  export MSYS_NO_PATHCONV=1
fi
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
