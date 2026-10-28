#!/usr/bin/env bash
# Respaldo cada 30 s: Jenkins pollSCM no admite intervalos menores a 1 minuto.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
echo "CI: vigilando main cada 30 s (Ctrl+C para parar)."
while true; do
  sleep 30
  JENKINS_CI_FORCE=0 bash "$ROOT/jenkins/trigger-ci-on-commit.sh" || true
done
