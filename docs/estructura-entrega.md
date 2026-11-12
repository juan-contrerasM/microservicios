# Equivalencia con la estructura de entrega del PDF

El enunciado del proyecto final (`docs/proyecto-final/proyectoFinal.pdf`, «Estructura de entrega»)
propone un árbol de referencia. Este repositorio conserva sus nombres de carpeta porque renombrarlas
rompería los Jenkinsfile, las rutas de Compose y el historial importado con `git subtree` de
`empleados-service` (ver `CLAUDE.md`). Esta tabla da la correspondencia exacta.

| Referencia del PDF | Este repositorio | Nota |
|---|---|---|
| `README.md` | `README.md` | Consolidado en la Fase 15 |
| `docker-compose.yml` | `docker-compose.yml` | Seis redes, puertos del PDF, Jenkins/SonarQube/Registry incluidos (Fase 14) |
| `docker-compose.dev.yml` (opcional) | — | No se usa: un solo Compose; las variaciones van por `.env` |
| `.env.example` | `.env.example` | Sin valores reales (`__GENERATE__`) |
| `.gitignore` | `.gitignore` | Excluye `.env`, `secrets/`, reportes y artefactos |
| `secrets.example/` | `secrets.example/` | Documenta los nombres esperados, sin valores |
| `microservicios/` | `services/` | Mismo contenido, nombre en inglés heredado del Reto 1 |
| `microservicios/gestion-empleados/` | `services/empleados-service/` | Java 21 + Spring Boot |
| `microservicios/gestion-departamentos/` | `services/departamentos-service/` | Go + chi |
| `microservicios/autenticacion/` | `services/auth-service/` | Python + FastAPI |
| `microservicios/gestion-perfiles/` | `services/perfiles-service/` | Java 21 + Spring Boot |
| `microservicios/gestion-vacaciones/` | `services/vacaciones-service/` | Node.js 22 + Express |
| `microservicios/notificaciones/` | `services/notificaciones-service/` | Python + FastAPI |
| `microservicios/api-gateway/` | `services/api-gateway/` | Node.js 22 + Express |
| `<servicio>/Dockerfile` | `services/<servicio>/Dockerfile` | Multi-stage, no root (Fase 7) |
| `<servicio>/Jenkinsfile` | `services/<servicio>/Jenkinsfile` | Plantilla común en `jenkins/plantillas/` (Fase 9) |
| `<servicio>/src/` | `src/` (Java, Node, Python) · `cmd/` + `internal/` (Go) | Go sigue el layout estándar de módulos |
| `<servicio>/tests/` | `tests/` (Node, Python) · `src/test/java/` (Java) · `*_test.go` junto al código (Go) | Convención de cada toolchain: Maven y `go test` no buscan en `tests/` |
| `<servicio>/openapi.yaml` | `services/<servicio>/openapi.yaml` | Los siete existen, con prueba de drift (Fase 5) |
| `e2e-tests/` | `e2e-tests/` | Cucumber.js; `scripts/test-bdd.sh` la ejecuta en contenedor |
| `observabilidad/logs/` | `observability/loki/`, `observability/promtail/` | Loki + Promtail |
| `observabilidad/monitoreo/` | `observability/prometheus/` | Scrape, recording rules |
| `observabilidad/trazas/` | servicio `zipkin` en `docker-compose.yml` | Zipkin en memoria; no necesita archivos propios |
| `observabilidad/dashboards/` | `observability/grafana/dashboards/` + `observability/grafana/provisioning/` | Dashboards, datasources y alertas aprovisionados |
| `docs/arquitectura.md` | `docs/arquitectura.md` | Fase 15 |
| `docs/eventos.md` | `docs/eventos.md` | Fase 4 |
| `docs/guias/` | `docs/guias/` | Fase 15 |
| `scripts/setup-secrets.sh` | `scripts/setup-secrets.sh` (+ `.ps1`) | Genera `.env` y `secrets/` |
| `scripts/init-db.sql` | Migraciones por servicio | Ver abajo |
| `scripts/seed-data.sql` | `scripts/seed-data.sh` | Ver abajo |

## Por qué no hay `init-db.sql` ni `seed-data.sql`

Cada servicio tiene su propia base y versiona su esquema con una herramienta con rollback
(Liquibase, golang-migrate, Alembic, node-pg-migrate). Las migraciones corren al arrancar cada
contenedor, así que `docker compose up --build` deja todas las bases listas sin un script global.
Un `init-db.sql` único tendría que conocer las seis bases y duplicaría (y contradiría) esas
migraciones.

Por la misma razón la semilla no es SQL: `scripts/seed-data.sh` crea departamentos y empleados a
través del API Gateway, de forma idempotente. Así los demás servicios reciben los eventos reales
y generan cuentas, perfiles y correos como en producción, sin escribir en la base de otro servicio.

## Pruebas y reportes por servicio

| Comando | Qué ejecuta |
|---|---|
| Comando unitario de cada servicio (README del servicio) | Unitarias + cobertura con umbral 70 % + JUnit |
| `bash scripts/test-integration.sh [servicio...]` | Integración contra PostgreSQL, MySQL, RabbitMQ y SMTP reales (Fase 8, E14) |
| `bash scripts/test-mutation.sh [servicio...]` | Un mutante por servicio que debe hacer fallar la suite (E17) |
| `bash scripts/test-bdd.sh [N]` | BDD de `e2e-tests/` contra el stack levantado, N veces seguidas |
