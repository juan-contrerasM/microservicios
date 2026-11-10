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
