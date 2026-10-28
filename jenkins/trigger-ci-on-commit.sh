#!/usr/bin/env bash
# Dispara los jobs de Jenkins tras un commit en main. Lo invoca jenkins/git-hooks/post-commit.
set -u

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$ROOT" || exit 0

branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" || exit 0
if [ "$branch" != "main" ]; then
  exit 0
fi

read_env() {
  local key=$1
  local file=$2
  local line
  line="$(grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | tr -d '\r')" || return 1
  line="${line#${key}=}"
  line="${line#\"}"
  line="${line%\"}"
  printf '%s' "$line"
}

JENKINS_URL="${JENKINS_URL:-http://localhost:9090}"
JENKINS_ADMIN_ID="${JENKINS_ADMIN_ID:-admin}"
JENKINS_ADMIN_PASSWORD="${JENKINS_ADMIN_PASSWORD:-admin}"

if [ -f "$ROOT/.env" ]; then
  JENKINS_URL="$(read_env JENKINS_URL "$ROOT/.env" 2>/dev/null)" || true
  JENKINS_URL="${JENKINS_URL:-http://localhost:9090}"
  id_from_env="$(read_env JENKINS_ADMIN_ID "$ROOT/.env" 2>/dev/null)" || true
  [ -n "${id_from_env:-}" ] && JENKINS_ADMIN_ID="$id_from_env"
  pass_from_env="$(read_env JENKINS_ADMIN_PASSWORD "$ROOT/.env" 2>/dev/null)" || true
  [ -n "${pass_from_env:-}" ] && JENKINS_ADMIN_PASSWORD="$pass_from_env"
fi

auth="${JENKINS_ADMIN_ID}:${JENKINS_ADMIN_PASSWORD}"

if command -v curl.exe >/dev/null 2>&1; then
  CURL=curl.exe
else
  CURL=curl
fi

cookie_jar="$(mktemp 2>/dev/null || true)"
if [ -z "${cookie_jar:-}" ]; then
  cookie_jar="${TEMP:-/tmp}/jenkins-ci-cookies-$$.txt"
fi
trap 'rm -f "$cookie_jar"' EXIT

if ! "$CURL" -sf --connect-timeout 3 -c "$cookie_jar" -b "$cookie_jar" -o /dev/null -u "$auth" \
  "${JENKINS_URL}/login"; then
  echo "CI: Jenkins no responde en ${JENKINS_URL}; omitiendo disparo (¿docker compose up?)." >&2
  exit 0
fi

trigger_job() {
  local job=$1
  local crumb_json crumb_field crumb
  crumb_json="$("$CURL" -sf -c "$cookie_jar" -b "$cookie_jar" -u "$auth" \
    "${JENKINS_URL}/crumbIssuer/api/json" 2>/dev/null)" || crumb_json=""
  if [ -n "$crumb_json" ]; then
    crumb="$(printf '%s' "$crumb_json" | grep -o '"crumb":"[^"]*"' | head -1 | cut -d'"' -f4)"
    crumb_field="$(printf '%s' "$crumb_json" | grep -o '"crumbRequestField":"[^"]*"' | head -1 | cut -d'"' -f4)"
  fi
  if [ -n "${crumb:-}" ] && [ -n "${crumb_field:-}" ]; then
    if "$CURL" -sf -X POST -c "$cookie_jar" -b "$cookie_jar" -u "$auth" \
      -H "${crumb_field}: ${crumb}" \
      "${JENKINS_URL}/job/${job}/build" >/dev/null; then
      echo "CI: encolado ${job}."
      return 0
    fi
  fi
  if "$CURL" -sf -X POST -c "$cookie_jar" -b "$cookie_jar" -u "$auth" \
    "${JENKINS_URL}/job/${job}/build" >/dev/null; then
    echo "CI: encolado ${job}."
  else
    echo "CI: no se pudo encolar ${job}." >&2
  fi
}

trigger_job empleados-service
trigger_job vacaciones-service

exit 0
