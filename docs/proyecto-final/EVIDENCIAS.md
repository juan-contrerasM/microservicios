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

## Fase 3B — Funcionalidad Node/Python y cierre de la Fase 3

Fecha de cierre: 2026-11-10.

- Vacaciones incorpora `PUT /vacaciones/{id}` para modificar únicamente períodos futuros en estado
  `PROGRAMADA`, evita solapamientos y recalcula los días hábiles. OpenAPI actualizado.
- Suite completa de Vacaciones: 66/66 pruebas correctas y 98,83 % de líneas en los módulos medidos.
- Notificaciones carga asuntos y cuerpos desde `config/templates.json`; Compose permite reemplazar el
  catálogo mediante un montaje de solo lectura. Suite: 14/14 pruebas correctas.
- Auth no expone `/register`; conserva alta por eventos, BCrypt, tokens expirantes, recuperación,
  suspensión temporal, reactivación y retiro permanente. Suite: 31/31 pruebas correctas.
- Se levantó el proyecto aislado `pfphase3` con volúmenes nuevos para evitar modificar los datos del
  entorno habitual. Bases, broker, siete servicios y Gateway iniciaron correctamente.
- Regresión BDD contra `http://localhost:8080`: 18/18 escenarios y 137/137 pasos correctos.
- Los volúmenes previos del proyecto principal conservan credenciales anteriores; no se eliminaron.
  Para reutilizarlos se deben rotar sus usuarios o recrearlos conscientemente con los secretos actuales.

## Ola 1 — Fases 4, 5 y 7 (implementación)

Fecha: 2026-11-11. Rama: `proyecto-final`. Merges: fase 7 (`4514472`), fase 4, fase 5 Java/Go
(`c3cf293`) y fase 5 Node/Python (`a82e0fd`).

### Pruebas sin Docker (todas en verde sobre el árbol integrado)

| Suite | Comando | Resultado |
|---|---|---|
| Empleados | `JAVA_HOME=<jdk-21> mvn -q -B test` | Correcto (5 pruebas Testcontainers omitidas sin Docker) |
| Perfiles | `JAVA_HOME=<jdk-21> mvn -q -B test` | Correcto |
| Departamentos | `go test ./...` | Correcto (db, department, httpapi, observability) |
| Gateway | `npm test` | 70/70 |
| Vacaciones | `npm test` | 109/109; 88 % de líneas |
| Auth | `pytest --cov=src` | 76 correctas; 82 % |
| Notificaciones | `pytest --cov=src` | 42 correctas; 81 % |
| Arnés E2E | `npm run test:unit` | 16/16 |

### Hallazgos corregidos durante la Ola 1

- `ad01d03` dejó `consumidor.py` de Auth y Notificaciones con mezcla de tabuladores y espacios
  (`TabError`): los módulos no se podían importar y los contenedores no habrían arrancado.
- El reintento de `ad01d03` volvía al exchange topic con la routing key original y se re-difundía a
  todos los consumidores del evento. Ahora `<q>.retry` hace dead-letter al exchange por defecto con
  routing key `<q>` (ver `docs/eventos.md`).
- Vacaciones dejaba un mensaje sin ack ni reject si fallaba `pool.connect()`, bloqueando la cola.
- Notificaciones enviaba el correo antes del commit: un fallo posterior podía duplicar el correo.
- Empleados no respondía `/health` al caer PostgreSQL: ahora cada check tiene timeout de 2 s y el pool
  espera como máximo 5 s (`DB_CONNECTION_TIMEOUT_MS`).

### Cambios de contrato que afectan a clientes

- Cuerpo de error C3 (`message` en lugar de `mensaje`); los pasos BDD leen `message ?? mensaje`.
- Duplicados (email, número de empleado) responden 409 en lugar de 400.
- Método no soportado responde 405 en lugar de 404.
- Las colecciones Postman de `docs/reto2`–`docs/reto5` no se actualizaron (históricas).

### Ola 1 — validación integrada con Docker

Fecha: 2026-11-11. Proyecto Compose aislado: `pfola1`.

- `docker compose build --no-cache`: las diez imágenes del Compose construyeron correctamente con
  las versiones fijadas, incluidas las siete aplicaciones, Jenkins y el bootstrap de SonarQube.
- Stack completo disponible: bases, RabbitMQ, siete servicios, Gateway, observabilidad, Jenkins y
  SonarQube. `sonar-bootstrap` terminó con código 0.
- Las siete aplicaciones se ejecutaron como `10001:10001` y quedaron `healthy`.
- Los siete `/health` respondieron HTTP 200 con sus dependencias disponibles.
- Al detener PostgreSQL de Empleados, `/health` respondió HTTP 503 en 2,13 segundos con
  `database: DOWN` y `messageBroker: UP`; después se restauró la base.
- Los siete contratos agregados en `/swagger/specs/<servicio>` respondieron HTTP 200.
- Dos publicaciones del mismo `empleado.creado` dejaron exactamente una cuenta, un perfil, un
  destinatario y una réplica de Vacaciones; cada tabla `eventos_procesados` conservó un solo id.
- Un envelope incompleto y un cuerpo no JSON dejaron dos mensajes en cada una de las cuatro DLQ.
- Con PostgreSQL de Auth detenido, un evento falló tres veces con espera y luego se procesó al
  restaurar la base. En otra ejecución persistente agotó los tres reintentos y terminó únicamente
  en `q.auth.dlq`, sin re-fan-out a los otros consumidores.
- Regresión BDD final: 18/18 escenarios y 137/137 pasos correctos en 44,086 segundos.
- Hallazgo de orquestación pendiente para Fase 14: los nombres globales `sonar-db`, `sonarqube`,
  `sonar-bootstrap` y `jenkins` colisionan con otros proyectos Compose; sus contenedores detenidos
  tuvieron que retirarse, conservando los volúmenes.
