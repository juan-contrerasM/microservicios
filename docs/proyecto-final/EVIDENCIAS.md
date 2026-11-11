# Evidencias — Proyecto Final

Este documento se completa durante cada fase. Toda evidencia debe indicar:

- fecha y commit;
- comando o procedimiento reproducible;
- resultado esperado y resultado obtenido;
- archivo, reporte o captura relacionada;
- responsable de ejecución y revisión.

## Fase 0 — Punto de restauración

- Estado estable: `b28c220`.
- Etiqueta local: `reto8-completo`.
- Rama local: `proyecto-final`.
- Commit de documentación: `b95ce87`.
- Publicación remota: pendiente; GitHub respondió HTTP 403 para la cuenta `SebasGalleS` sobre el
  repositorio `juan-contrerasM/microservicios`.

## Fase 1 — Baseline

Fecha de primera ejecución: 2026-11-09. Rama: `proyecto-final`.

| Componente | Ejecución limpia | Resultado inicial |
|---|---|---|
| Docker Compose | `docker compose --env-file .env.example config --quiet` | Correcto |
| Departamentos | `golang:1.25-alpine`, `go test ./...` | Correcto; solo `internal/observability` tiene pruebas, los demás paquetes indican `[no test files]` |
| API Gateway | `node:22-alpine`, `npm ci && npm test` | 21/21 pruebas correctas |
| Vacaciones | `node:22-alpine`, `npm ci && npm test` | 64/64; 100 % líneas en los cuatro módulos incluidos por su configuración |
| Auth | `python:3.12-slim`, dependencias versionadas y `pytest` | 31/31; cobertura total 72 % |
| Notificaciones | `python:3.12-slim`, requirements + pytest/pytest-cov | 12/12; cobertura total 73 % |
| Empleados | `maven:3.9.11-eclipse-temurin-21-alpine`, `mvn -q test` | Correcto; Maven finalizó con código 0 |
| Perfiles | `maven:3.9.11-eclipse-temurin-21-alpine`, `mvn -q test` | Correcto; Maven finalizó con código 0 |

Hallazgos reproducibles:

1. El `go.mod` exige Go 1.25.11; Go 1.24 rechaza la ejecución.
2. Departamentos no prueba dominio, repositorio ni handlers.
3. Notificaciones no incluye `requirements-dev.txt`; la prueba limpia requirió instalar pytest y
   pytest-cov explícitamente.
4. Auth (72 %) y Notificaciones (73 %) apenas superan el umbral y necesitan integración adicional.
5. La cobertura de Vacaciones solo mide cuatro módulos seleccionados, no todo `src/`.
6. Falta capturar cobertura comparable de Gateway, Empleados, Perfiles y Departamentos.
7. La primera construcción completa detectó que Jenkins no arrancaba porque el entrypoint llegaba
   al contexto Linux con finales de línea CRLF.

### Integración completa

| Prueba | Resultado |
|---|---|
| `docker compose --env-file .env.example up -d --build` | Los siete servicios construyeron y levantaron correctamente; Jenkins fue la única excepción inicial |
| Suite unitaria del harness E2E | 9/9 pruebas correctas |
| BDD contra `http://api-gateway:8080` | 18 escenarios y 137 pasos correctos |
| Health de Empleados con PostgreSQL disponible | HTTP 200, `database: UP` y `messageBroker: UP` |
| Health de Empleados con PostgreSQL detenido | No respondió en 15 segundos; incumple el contrato esperado de HTTP 503 acotado |
| Recuperación posterior | Base y servicio restaurados; health volvió a HTTP 200 |

Hallazgos adicionales:

- `api-gateway` reportó 38 vulnerabilidades npm (32 moderadas y 6 altas) durante el build.
- `vacaciones-service` reportó 40 vulnerabilidades npm (33 moderadas y 7 altas).
- El comportamiento DOWN de Empleados queda registrado como brecha de la Fase 5; no se considera
  un health dependiente completamente verificado.
- Jenkins requiere normalización LF reproducible para poder iniciar desde un checkout de Windows.

### Corrección habilitante de Jenkins

- Se añadió `.gitattributes` para conservar LF en scripts de shell y Dockerfiles.
- El Dockerfile normaliza defensivamente `docker-entrypoint.sh` antes de marcarlo ejecutable.
- `docker compose --env-file .env.example build jenkins` finalizó correctamente.
- El contenedor quedó en estado `Up`, Jenkins informó `fully up and running` y
  `http://localhost:9090/login` respondió HTTP 200.

## Fase 2 — Gestión segura de secretos

- `.env.example` contiene marcadores, no credenciales académicas utilizables.
- Los generadores de Windows y Linux crean 15 valores criptográficos en destinos ignorados.
- La prueba aislada de `setup-secrets.ps1` generó 15 archivos y dejó cero marcadores pendientes.
- Gitleaks 8.24.3 analizó aproximadamente 3,47 MB del árbol actual: cero hallazgos.
- Gitleaks recorrió los 94 commits del historial, aproximadamente 7,65 MB: cero hallazgos.
- Las exclusiones del escáner se limitan a `.venv`, fixtures JWT inválidos y el bootstrap que reemplaza
  la credencial inicial pública de SonarQube.
- Se levantó el proyecto aislado `pfsecretstest` con volúmenes nuevos y los 15 secretos generados.
- Todas las bases, RabbitMQ, los siete procesos HTTP, Grafana, Prometheus, Loki, Zipkin, Jenkins y
  SonarQube quedaron disponibles; `sonar-bootstrap` terminó con código 0.
- RabbitMQ requirió un wrapper mínimo porque la imagen actual rechaza `RABBITMQ_DEFAULT_PASS_FILE`;
  el wrapper lee el archivo, elimina la variable obsoleta y delega al entrypoint oficial.
- El generador garantiza una contraseña con mayúscula, minúscula, número y símbolo, compatible con
  las políticas de Auth y SonarQube.
- Regresión posterior: Gateway 21/21, Vacaciones 64/64, Auth 31/31, Notificaciones 12/12, Go y ambas
  suites Java correctas; BDD integrada 18 escenarios y 137 pasos correctos.

## Fase 3A — Funcionalidad Java/Go

- Departamentos incorpora actualización y eliminación en repositorio, dominio, HTTP y OpenAPI.
- Prueba real contra MySQL: crear `PFCRUD`, actualizar conservando el id de la ruta y eliminar con
  HTTP 204. La suite Go completa pasa.
- Perfiles incorpora apellido, código postal, cargo, área, número de empleado, foto y redes sociales,
  con migración Liquibase incremental y sincronización desde eventos de empleado.
- La suite Maven de Perfiles finaliza correctamente después de ampliar el modelo.
