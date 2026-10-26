#!/bin/sh
# Deja el Quality Gate Reto7, los dos proyectos, el webhook y el token.
# Idempotente: un segundo arranque no revoca un token que sigue siendo válido.
set -eu

apk add --no-cache curl >/dev/null

SONAR_URL="${SONAR_URL:-http://sonarqube:9000}"
PASS="${SONAR_ADMIN_PASSWORD:?Defina SONAR_ADMIN_PASSWORD}"
TOKEN_FILE=/bootstrap/token
WEBHOOK_URL="http://jenkins:8080/sonarqube-webhook/"

echo "Esperando a que SonarQube responda UP en ${SONAR_URL}"
i=0
status=""
while [ "$i" -lt 60 ]; do
  status="$(curl -sf "${SONAR_URL}/api/system/status" || true)"
  if printf '%s' "$status" | grep -q '"status":"UP"'; then
    break
  fi
  i=$((i + 1))
  sleep 5
done
if ! printf '%s' "$status" | grep -q '"status":"UP"'; then
  echo "SonarQube no llegó a UP. Última respuesta: ${status}" >&2
  exit 1
fi

if curl -sf -u admin:admin "${SONAR_URL}/api/authentication/validate" | grep -q '"valid":true'; then
  curl -sf -u admin:admin -X POST "${SONAR_URL}/api/users/change_password" \
    --data-urlencode "login=admin" \
    --data-urlencode "previousPassword=admin" \
    --data-urlencode "password=${PASS}"
  echo "Contraseña inicial de SonarQube reemplazada"
fi

if ! curl -sf -u "admin:${PASS}" "${SONAR_URL}/api/authentication/validate" | grep -q '"valid":true'; then
  echo "admin no autentica con SONAR_ADMIN_PASSWORD" >&2
  exit 1
fi

auth() {
  curl -sf -u "admin:${PASS}" "$@"
}

# SonarQube 26 rechaza webhooks hacia la red Docker (site-local).
# El curso corre Jenkins y Sonar en el mismo compose, así que la validación se apaga.
auth -X POST "${SONAR_URL}/api/settings/set" \
  --data-urlencode "key=sonar.validateWebhooks" \
  --data-urlencode "value=false" >/dev/null

if ! auth "${SONAR_URL}/api/qualitygates/show?name=Reto7" >/dev/null 2>&1; then
  auth -X POST "${SONAR_URL}/api/qualitygates/create" --data-urlencode "name=Reto7" >/dev/null
fi

show="$(auth "${SONAR_URL}/api/qualitygates/show?name=Reto7")"
# Sonar crea el gate con condiciones de "clean as you code" (cobertura nueva al 80 %).
# El plan pide solo cobertura total y en código nuevo, las dos al 70 %.
pairs="$(printf '%s' "$show" | grep -o '"id":"[^"]*","metric":"[^"]*"' || true)"
for pair in $pairs; do
  cid="$(printf '%s' "$pair" | sed -n 's/"id":"\([^"]*\)".*/\1/p')"
  metric="$(printf '%s' "$pair" | sed -n 's/.*"metric":"\([^"]*\)".*/\1/p')"
  case "$metric" in
    coverage|new_coverage)
      auth -X POST "${SONAR_URL}/api/qualitygates/update_condition" \
        --data-urlencode "id=${cid}" \
        --data-urlencode "metric=${metric}" \
        --data-urlencode "op=LT" \
        --data-urlencode "error=70" >/dev/null
      ;;
    *)
      auth -X POST "${SONAR_URL}/api/qualitygates/delete_condition" \
        --data-urlencode "id=${cid}" >/dev/null || true
      ;;
  esac
done

for metric in coverage new_coverage; do
  show="$(auth "${SONAR_URL}/api/qualitygates/show?name=Reto7")"
  if ! printf '%s' "$show" | grep -q "\"metric\":\"${metric}\""; then
    auth -X POST "${SONAR_URL}/api/qualitygates/create_condition" \
      --data-urlencode "gateName=Reto7" \
      --data-urlencode "metric=${metric}" \
      --data-urlencode "op=LT" \
      --data-urlencode "error=70" >/dev/null
  fi
done

auth -X POST "${SONAR_URL}/api/qualitygates/set_as_default" --data-urlencode "name=Reto7" >/dev/null

for project in empleados-service vacaciones-service; do
  found="$(auth "${SONAR_URL}/api/projects/search?projects=${project}" || true)"
  if ! printf '%s' "$found" | grep -q "\"key\":\"${project}\""; then
    auth -X POST "${SONAR_URL}/api/projects/create" \
      --data-urlencode "project=${project}" \
      --data-urlencode "name=${project}" >/dev/null
  fi
  auth -X POST "${SONAR_URL}/api/qualitygates/select" \
    --data-urlencode "gateName=Reto7" \
    --data-urlencode "projectKey=${project}" >/dev/null
done

hooks="$(auth "${SONAR_URL}/api/webhooks/list" || true)"
if ! printf '%s' "$hooks" | grep -q 'sonarqube-webhook'; then
  auth -X POST "${SONAR_URL}/api/webhooks/create" \
    --data-urlencode "name=jenkins" \
    --data-urlencode "url=${WEBHOOK_URL}" >/dev/null
fi

need_token=1
if [ -s "${TOKEN_FILE}" ]; then
  current="$(cat "${TOKEN_FILE}")"
  if curl -sf -H "Authorization: Bearer ${current}" "${SONAR_URL}/api/authentication/validate" | grep -q '"valid":true'; then
    need_token=0
    echo "El token de /bootstrap/token sigue siendo válido"
  fi
fi

if [ "$need_token" -eq 1 ]; then
  auth -X POST "${SONAR_URL}/api/user_tokens/revoke" --data-urlencode "name=jenkins" >/dev/null 2>&1 || true
  resp="$(auth -X POST "${SONAR_URL}/api/user_tokens/generate" --data-urlencode "name=jenkins")"
  token="$(printf '%s' "$resp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
  if [ -z "$token" ]; then
    echo "SonarQube no devolvió un token" >&2
    exit 1
  fi
  printf '%s\n' "$token" > "${TOKEN_FILE}"
  chmod 644 "${TOKEN_FILE}"
  echo "Token nuevo escrito en ${TOKEN_FILE}"
fi

echo "SonarQube listo: gate Reto7, proyectos empleados-service y vacaciones-service, webhook ${WEBHOOK_URL}"
