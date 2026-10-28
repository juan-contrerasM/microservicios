#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
git config core.hooksPath jenkins/git-hooks
chmod +x jenkins/git-hooks/post-commit jenkins/trigger-ci-on-commit.sh 2>/dev/null || true
echo "Listo: core.hooksPath = jenkins/git-hooks"
echo "Cada commit en main dispara empleados-service y vacaciones-service si Jenkins está en ${JENKINS_URL:-http://localhost:9090}."
