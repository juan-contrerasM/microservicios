#!/usr/bin/env bash
# Fase 9: genera services/<servicio>/Jenkinsfile desde la plantilla común. Los Jenkinsfile
# generados no se editan a mano: se cambia la plantilla y se vuelve a ejecutar este script.
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
for servicio in empleados-service perfiles-service departamentos-service auth-service \
  notificaciones-service vacaciones-service api-gateway; do
  {
    printf '// GENERADO desde jenkins/plantillas/Jenkinsfile.plantilla (bash jenkins/plantillas/generar.sh).\n'
    printf '// No editar a mano: los cambios van en la plantilla.\n'
    sed "s/@@SERVICIO@@/$servicio/" "$RAIZ/jenkins/plantillas/Jenkinsfile.plantilla"
  } > "$RAIZ/services/$servicio/Jenkinsfile"
  echo "services/$servicio/Jenkinsfile"
done
