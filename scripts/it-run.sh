#!/bin/sh
# Fase 8/9 (E14): ejecuta la suite de integración de UN servicio. Corre dentro de la imagen de
# toolchain del servicio, con el directorio del servicio como cwd y las variables IT_* que escribe
# scripts/it-deps.sh (PostgreSQL, MySQL, RabbitMQ y Mailhog). Los servicios Java no las usan:
# Testcontainers crea sus propias dependencias por el socket de Docker.
#
#   it-run.sh <empleados|departamentos|auth|perfiles|vacaciones|notificaciones|gateway>
set -eu

mkdir -p reports
pg() { echo "postgresql://postgres:${IT_PASS}@${IT_PG_HOST}:5432/$1_it"; }

pytest_integracion() {
  export DATABASE_URL="postgresql+psycopg://postgres:${IT_PASS}@${IT_PG_HOST}:5432/$1_it"
  export BROKER_URL="amqp://${IT_RABBIT_HOST}:5672" RABBITMQ_USER=it RABBITMQ_PASSWORD="$IT_PASS"
  export IT_AMQP_HOST="$IT_RABBIT_HOST" PYTHONDONTWRITEBYTECODE=1
  pip install -q --disable-pip-version-check -r requirements-dev.txt
  python -m pytest tests/integracion -p no:cacheprovider -o addopts="" \
    --junitxml=reports/junit-integracion.xml -v
}

case "$1" in
  empleados|perfiles)
    # mvnw puede traer CRLF desde un checkout de Windows: se normaliza fuera del árbol.
    cp -r .mvn /tmp/ && tr -d '\r' < mvnw > /tmp/mvnw
    sh /tmp/mvnw -B -q ${MAVEN_OPTS_EXTRA:-} verify ;;
  departamentos)
    export IT_MYSQL_DSN="root:${IT_PASS}@tcp(${IT_MYSQL_HOST}:3306)/departamentos_it?parseTime=true&multiStatements=true"
    go run gotest.tools/gotestsum@v1.12.3 --junitfile reports/junit-integracion.xml -- \
      -tags integration -count=1 ./internal/integration/... ;;
  auth)
    export JWT_SECRET="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    export AUTH_ADMIN_PASSWORD="Aa1!$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    pytest_integracion auth ;;
  notificaciones)
    export SMTP_HOST="$IT_MAIL_HOST" SMTP_PORT=1025 IT_MAILHOG_API="http://${IT_MAIL_HOST}:8025"
    pytest_integracion notificaciones ;;
  vacaciones)
    IT_DATABASE_URL="$(pg vacaciones)" IT_AMQP_URL="amqp://it:${IT_PASS}@${IT_RABBIT_HOST}:5672" \
      npm run test:integration ;;
  gateway)
    # Sin base ni broker: servidores HTTP reales hacen de backends (JWT, RBAC, proxy, 503, CB).
    node --test --test-force-exit --test-reporter=spec --test-reporter-destination=stdout \
      --test-reporter=junit --test-reporter-destination=reports/junit-integracion.xml \
      tests/app.test.js tests/circuit-breaker.test.js ;;
  *) echo "Servicio desconocido: $1" >&2; exit 2 ;;
esac
