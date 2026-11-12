#!/usr/bin/env bash
# Fase 8 (E17): prueba de mutación manual reproducible, una por servicio.
#
# Para cada servicio rompe a propósito una regla de negocio (el "mutante"), corre las pruebas
# unitarias que la cubren dentro de la imagen de toolchain del servicio y exige que FALLEN.
# Si la suite sigue verde, el mutante sobrevivió y el script sale con 1. El archivo original se
# restaura siempre, también ante Ctrl+C o error.
#
# Uso:   bash scripts/test-mutation.sh                 # los siete servicios
#        bash scripts/test-mutation.sh vacaciones auth  # solo los indicados
set -uo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export MSYS_NO_PATHCONV=1
ruta_host() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

NODE_IMAGEN=node:22.21.1-alpine3.22
PY_IMAGEN=python:3.12.12-slim-bookworm
GO_IMAGEN=golang:1.25.11-alpine
JDK_IMAGEN=eclipse-temurin:21.0.9_10-jdk-alpine

RESPALDO=""
ARCHIVO=""
restaurar() {
  if [ -n "$RESPALDO" ] && [ -f "$RESPALDO" ]; then
    mv -f "$RESPALDO" "$ARCHIVO"
    RESPALDO=""
  fi
}
trap restaurar EXIT INT TERM

# mutar <servicio> <archivo relativo> <buscar> <reemplazo> <imagen> <comando> [opciones docker...]
mutar() {
  local servicio=$1 relativo=$2 buscar=$3 reemplazo=$4 imagen=$5 comando=$6
  shift 6
  ARCHIVO="$RAIZ/services/$servicio/$relativo"
  if ! grep -qF -- "$buscar" "$ARCHIVO"; then
    echo "[$servicio] no se encontró el código a mutar en $relativo" >&2
    return 2
  fi
  RESPALDO="$ARCHIVO.mut-bak"
  cp "$ARCHIVO" "$RESPALDO"
  BUSCAR="$buscar" REEMPLAZO="$reemplazo" perl -0pi -e 's/\Q$ENV{BUSCAR}\E/$ENV{REEMPLAZO}/' "$ARCHIVO"
  echo "[$servicio] mutante en $relativo:"
  echo "    - $buscar"
  echo "    + $reemplazo"
  docker run --rm -v "$(ruta_host "$RAIZ/services/$servicio"):/src" -w /src "$@" "$imagen" sh -c "$comando" \
    > "$RAIZ/services/$servicio/reports/mutacion.log" 2>&1
  local salida=$?
  restaurar
  if [ $salida -ne 0 ]; then
    echo "[$servicio] MUTANTE ELIMINADO: la suite falló (salida $salida). Detalle en services/$servicio/reports/mutacion.log"
    return 0
  fi
  echo "[$servicio] MUTANTE SOBREVIVIÓ: ninguna prueba detectó el cambio" >&2
  return 1
}

PY='pip install -q --disable-pip-version-check -r requirements-dev.txt && python -m pytest -q -p no:cacheprovider -o addopts=""'
# mvnw del checkout de Windows puede traer CRLF: se normaliza en /tmp junto con .mvn/ (que el
# wrapper busca al lado del script) para no tocar el árbol de trabajo.
MVN="cp -r .mvn /tmp/ && tr -d '\r' < mvnw > /tmp/mvnw && sh /tmp/mvnw -B -q test -Dtest="

m_empleados() {
  mutar empleados-service src/main/java/com/microservicios/Reto1/service/EmpleadoService.java \
    'if (empleado.getEstado() == EstadoEmpleado.RETIRADO) {' 'if (false) {' \
    "$JDK_IMAGEN" "${MVN}EmpleadoServiceTest" -v pf-it-m2:/root/.m2
}
m_departamentos() {
  mutar departamentos-service internal/department/service.go \
    'if d.ID == "" || d.Nombre == "" {' 'if d.ID == "" {' \
    "$GO_IMAGEN" 'go test -count=1 ./internal/department/' -v pf-it-gomod:/go/pkg/mod
}
m_auth() {
  mutar auth-service src/auth_service/seguridad.py \
    'if len(clave) < 8:' 'if len(clave) < 1:' \
    "$PY_IMAGEN" "$PY tests/test_reglas.py tests/test_ciclo.py" -v pf-it-pip:/root/.cache/pip -e PYTHONDONTWRITEBYTECODE=1
}
m_perfiles() {
  mutar perfiles-service src/main/java/com/microservicios/perfiles/service/PerfilEventoService.java \
    'if (eventos.existsById(eventId)) {' 'if (false) {' \
    "$JDK_IMAGEN" "${MVN}PerfilEventoServiceTest" -v pf-it-m2:/root/.m2
}
m_vacaciones() {
  mutar vacaciones-service src/validar.js \
    'fechaFin < fechaInicio' 'fechaFin < "0000-00-00"' \
    "$NODE_IMAGEN" 'npm ci -q --no-audit --no-fund >/dev/null 2>&1; node --test tests/validar.test.js' -v /src/node_modules
}
m_notificaciones() {
  mutar notificaciones-service src/notificaciones/procesar.py \
    'if repositorio.ya_procesado(event_id):' 'if False:' \
    "$PY_IMAGEN" "$PY tests/test_procesar.py" -v pf-it-pip:/root/.cache/pip -e PYTHONDONTWRITEBYTECODE=1
}
m_gateway() {
  mutar api-gateway src/auth.js \
    "if (payload.role === 'ADMIN') {" "if (payload.role === 'ADMIN' || payload.role === 'USER') {" \
    "$NODE_IMAGEN" 'npm ci -q --no-audit --no-fund >/dev/null 2>&1; node --test tests/app.test.js' -v /src/node_modules
}

SERVICIOS=("$@")
[ ${#SERVICIOS[@]} -eq 0 ] && SERVICIOS=(empleados departamentos auth perfiles vacaciones notificaciones gateway)

sobrevivientes=()
for s in "${SERVICIOS[@]}"; do
  mkdir -p "$RAIZ/services/$(case $s in gateway) echo api-gateway ;; *) echo "$s-service" ;; esac)/reports"
  "m_$s" || sobrevivientes+=("$s")
done

if [ ${#sobrevivientes[@]} -gt 0 ]; then
  echo "Mutantes sobrevivientes en: ${sobrevivientes[*]}" >&2
  exit 1
fi
echo "Todos los mutantes fueron eliminados: ${SERVICIOS[*]}"
