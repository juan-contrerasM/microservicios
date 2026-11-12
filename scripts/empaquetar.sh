#!/usr/bin/env bash
# Fase 16: genera el paquete de entrega (.tar.gz y .zip) desde el commit actual.
#
#   bash scripts/empaquetar.sh [destino]     # por defecto dist/
#
# Usa `git archive`: solo entra lo versionado, así que nunca viajan .env, secrets/, volúmenes,
# node_modules, target, .venv, reportes ni el historial .deploy/ (todos ignorados por Git).
# Después verifica el contenido y falla si encuentra algo que no debe estar.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
DESTINO="${1:-$RAIZ/dist}"
NOMBRE="proyecto-final-microservicios"
mkdir -p "$DESTINO"

cd "$RAIZ"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Hay cambios sin commit: el paquete se genera desde HEAD y no los incluiría." >&2
  exit 1
fi
commit="$(git rev-parse --short HEAD)"

git archive --format=tar.gz --prefix="$NOMBRE/" -o "$DESTINO/$NOMBRE-$commit.tar.gz" HEAD
git archive --format=zip --prefix="$NOMBRE/" -o "$DESTINO/$NOMBRE-$commit.zip" HEAD

prohibidos='(^|/)(\.env|secrets/[^/]+\.txt|node_modules/|target/|\.venv/|reports/|\.deploy/|coverage\.out)'
if tar -tzf "$DESTINO/$NOMBRE-$commit.tar.gz" | grep -E "$prohibidos" | grep -v '\.env\.example$'; then
  echo "El paquete contiene archivos que no deben entregarse (ver arriba)" >&2
  exit 1
fi

echo "Paquetes generados desde $commit:"
ls -l "$DESTINO/$NOMBRE-$commit.tar.gz" "$DESTINO/$NOMBRE-$commit.zip"
echo "Archivos: $(tar -tzf "$DESTINO/$NOMBRE-$commit.tar.gz" | grep -vc '/$')"
