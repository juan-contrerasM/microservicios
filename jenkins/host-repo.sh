#!/bin/sh
# Fase 9: imprime la ruta del repositorio tal como la ve el daemon de Docker del HOST (el Source
# del montaje /workspace de este contenedor Jenkins). Compose dentro de Jenkins la usa para los
# bind mounts (secrets, plantillas), porque el daemon no ve el workspace de Jenkins.
# Docker Desktop en Windows puede reportar "C:\Users\..."; el daemon la monta como
# /run/desktop/mnt/host/c/Users/..., que es lo que se devuelve.
set -eu
origen="$(docker inspect "$(hostname)" \
  --format '{{range .Mounts}}{{if eq .Destination "/workspace"}}{{.Source}}{{end}}{{end}}')"
[ -n "$origen" ] || { echo "Este contenedor no monta /workspace" >&2; exit 1; }
printf '%s\n' "$origen" | tr '\\' '/' | sed -E 's#^([A-Za-z]):/#/run/desktop/mnt/host/\L\1/#'
