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

### Fase 6 — resiliencia síncrona

Fecha: 2026-11-11. Stack integrado: `pfola1`.

- Gateway: circuito independiente para cada uno de los seis backends, timeout configurable por
  servicio y fallback C3 con HTTP 503.
- Solo `GET` se reintenta, con tres esperas configurables de 1, 2 y 4 segundos; los métodos que
  cambian estado se ejecutan una vez para evitar duplicados.
- Las métricas `gateway_circuit_breaker_state{servicio}` y
  `gateway_upstream_errors_total{servicio,causa}` permiten observar estado y fallos.
- Suite del Gateway: 72/72 pruebas correctas. Incluye `CLOSED → OPEN → HALF_OPEN → CLOSED` y
  retorno a `OPEN` cuando falla la llamada de prueba.
- Suite de Empleados correcta dentro de su etapa de compilación, incluidas las pruebas existentes
  de Resilience4j, fallback `PENDIENTE_VALIDACION` y reconciliación.
- Prueba integrada: con Departamentos detenido, tres POST fallaron sin reintento y abrieron su
  circuito; el GET siguiente respondió 503 en 5 ms y la métrica quedó en 2 (`OPEN`). Tras restaurar
  el backend y esperar 30 segundos, el GET respondió 200 y la métrica volvió a 0 (`CLOSED`).
- Regresión funcional posterior al cambio: 18/18 escenarios y 137/137 pasos BDD correctos en
  42,181 segundos contra el stack integrado.

### Fase 8 — primer bloque de cobertura

Fecha: 2026-11-11. Ejecuciones reproducibles dentro de las imágenes de construcción.

- Gateway: `npm test`, 72 pruebas, 97,03 % de líneas y 83,33 % de ramas. Genera JUnit y LCOV y
  falla automáticamente bajo 70 %.
- Perfiles: `mvn verify`, 54 pruebas, 80,78 % de líneas y 57,99 % de ramas. JaCoCo genera XML/HTML
  y aplica un mínimo de 70 % sobre la lógica ejecutable.
- Departamentos: `sh scripts/test.sh`, 41 pruebas y 74,3 % global sin excluir paquetes. Genera
  `coverage.out` y JUnit; dominio/repositorio quedó en 86,9 %, HTTP en 85,6 %, configuración en
  100 % y observabilidad en 74 %.
- Este bloque no cierra la Fase 8: faltan revalidar los otros cuatro servicios, integración con
  dependencias reales, evidencia de mutación y tres regresiones BDD consecutivas.

### Fase 8 — cierre: cobertura, integración, mutación y BDD

Fecha: 2026-11-12. Todo se ejecuta en contenedores con la misma imagen de toolchain del Dockerfile
de cada servicio; el host solo necesita Docker.

| Servicio | Unitarias | Cobertura medida (umbral automático 70 %) | Integración real (E14) | Mutante (E17) |
|---|---|---|---|---|
| Empleados | 104 | 87,95 % de líneas, JaCoCo unitarias + IT (`check` en `verify`) | PostgreSQL en Testcontainers | Permitir un segundo retiro → `segundoRetiroNoPublica` falla |
| Departamentos | 43 | 74,2 % global, sin exclusiones | MySQL: migraciones up/down/up y CRUD HTTP | Quitar la validación de `nombre` en el alta. **Sobrevivió** la primera vez: faltaba la prueba; se añadió y ahora falla |
| Auth | 76 | 82,79 % (líneas + ramas) | PostgreSQL + RabbitMQ: Alembic up/down/up, ciclo de cuenta por eventos y API, poison a DLQ | Longitud mínima de contraseña 8 → 1 → fallan pruebas de política |
| Perfiles | 54 | 88,67 % de líneas, JaCoCo unitarias + IT | PostgreSQL + RabbitMQ (Testcontainers): creado, duplicado, actualizado, retirado, poison, API HTTP | Ignorar `existsById` → `elMismoIdDeEventoNoInsertaDosPerfiles` falla |
| Vacaciones | 115 | 82,27 % de **todo** `src/` (salvo `index.js` y `telemetria.js`) | PostgreSQL + RabbitMQ: node-pg-migrate up/down/up, consumidor, CRUD, scheduler y 7 eventos publicados | Aceptar `fechaFin < fechaInicio` → `validar.test.js` falla |
| Notificaciones | 42 | 78,21 % (líneas + ramas) | PostgreSQL + RabbitMQ + SMTP (Mailhog): correo verificado, duplicado sin efecto, poison a DLQ | Desactivar la deduplicación → `test_procesar.py` falla |
| Gateway | 72 | 97,03 % de líneas | Servidores HTTP reales como backends: JWT, RBAC, proxy, 503 y circuito | USER tratado como ADMIN → `app.test.js` falla |

Comandos de la evidencia:

```bash
bash scripts/test-integration.sh      # siete suites contra dependencias reales efímeras (red pf-it)
bash scripts/test-mutation.sh         # siete mutantes; sale 1 si alguno sobrevive
bash scripts/test-bdd.sh 3            # BDD tres veces seguidas contra el stack
```

- Hallazgo corregido durante la evidencia: los scripts copiaban `mvnw` a `/tmp` sin `.mvn/`, de
  modo que Maven fallaba antes de compilar y los mutantes Java «morían» por ese error. Se corrigió
  y se repitió: ahora fallan las pruebas nombradas arriba.
- BDD ampliada de 18 a 23 escenarios: CRUD y RBAC de Departamentos, `empleado.creado` duplicado
  (una notificación y un perfil), mensaje no JSON en las cuatro DLQ con la cola sana y circuito del
  Gateway `OPEN → CLOSED` deteniendo y arrancando Departamentos por la API de Docker.
- Tres ejecuciones seguidas antes de la Fase 14: 23/23 escenarios y 179/179 pasos cada una.

### Fase 14 — redes, puertos y orquestación

Fecha: 2026-11-12. Stack `pfola1` reconstruido con el Compose nuevo, conservando volúmenes.

- Publicados en el host únicamente: Gateway `8080`, Jenkins `8090`, Prometheus `9090`, Registry
  `5001`, Grafana `3000`, Zipkin `9411`, SonarQube `9000`, RabbitMQ UI `15672` y Mailhog `8025`.
- `8081`–`8086` y AMQP `5672` rechazan la conexión desde el host.
- Aislamiento: desde el Gateway `database-empleados` y `message-broker` no resuelven y
  `departamentos-service` sí; Jenkins no resuelve `database-auth` y sí `sonarqube`.
- Los siete servicios, seis bases, RabbitMQ, Mailhog, Registry, Prometheus, Loki, Promtail, Zipkin,
  Grafana, SonarQube y Jenkins quedaron arriba; `curl localhost:5001/v2/` → 200.
- Sin `container_name` fijos: Jenkins y SonarQube se recrearon como `pfola1-*` y `sonar-bootstrap`
  terminó con código 0.
- Imágenes de infraestructura fijadas a la versión que ya corría (PostgreSQL 16.15, MySQL 8.4.13,
  RabbitMQ 3.13.7, Mailhog v1.0.1, Jenkins 2.580.2, Registry 2.8.3) para no degradar volúmenes.
- `scripts/seed-data.sh` crea dos departamentos y tres empleados vía Gateway; la segunda ejecución
  no crea nada.
- Regresión sobre la topología nueva: 23/23 escenarios y 179/179 pasos en tres ejecuciones
  seguidas (2 min 18 s, 2 min 26 s y 2 min 22 s).

### Fase 11 — observabilidad uniforme

Fecha: 2026-11-12.

- Prometheus raspa los siete servicios (`up = 1`) con las métricas de negocio nuevas: por ejemplo
  `empleados_creados_total`, `perfiles_creados_total`, `vacaciones_programadas_total`,
  `logins_total{resultado}`, `cuentas_activadas_total`, `notificaciones_enviadas_total{tipo}` y
  `eventos_{procesados,duplicados,dlq}_total{tipo}` en los cuatro consumidores.
- Hallazgo corregido: un contador de DLQ que nace en 1 no lo ve `increase()`, así que la alerta
  ignoraba el primer mensaje. Los consumidores crean ahora sus series en 0 al arrancar.
- Logs: los siete servicios escriben `timestamp`, `level`, `service`, `message`, `traceId` y
  `spanId` (Java renombró `@timestamp`/`trace_id`; Go, `time`/`msg`). El mismo `traceId` aparece en
  Gateway, Empleados, Departamentos y Perfiles.
- Zipkin registra los siete servicios y una traza de onboarding contiene spans de los siete.
- Grafana aprovisionó cinco reglas (servicio caído, 5xx > 10 %, p95 > 2 s, DLQ, memoria > 85 %),
  el contact point `equipo-microservicios` (Discord + correo) y el dashboard `pf-negocio-eventos`.
- Alerta real: el escenario BDD de mensaje inválido disparó «Mensajes enviados a una DLQ» para auth,
  perfiles, notificaciones y vacaciones; los cuatro correos llegaron a Mailhog. Grafana intentó
  Discord y falló porque `secrets/discord_webhook_url.txt` todavía tiene el marcador.
- Con Prometheus, Loki, Promtail, Zipkin y Grafana detenidos, la BDD completa pasó 23/23: la caída
  del stack de observabilidad no afecta el negocio.
- Discord (2026-11-12): con el webhook real en `secrets/discord_webhook_url.txt` y Grafana recreado,
  un primer intento devolvió 404 «Unknown Webhook» porque el Alertmanager aún usaba el marcador
  (aplicó la configuración nueva 11 s después). Una prueba directa al webhook respondió 204 y un
  segundo mensaje inválido (`empleado.retirado`) disparó la alerta de DLQ en los cuatro
  consumidores: las notificaciones `FIRING` y `RESOLVED` salieron sin ningún error de Discord en el
  log de Grafana y llegaron también por correo a Mailhog. La URL no está en Git (Gitleaks limpio).

### Fase 9 — plantilla

- `jenkins/plantillas/Jenkinsfile.plantilla` contiene todas las etapas de E18 para los cuatro
  stacks. El linter de Jenkins (`/pipeline-model-converter/validate`) respondió
  «Jenkinsfile successfully validated».

### Fase 9 — siete pipelines en Jenkins

Fecha: 2026-11-12. Jenkins del Compose (`http://localhost:8090`), jobs creados por JCasC.

- Los siete `Jenkinsfile` se generan desde `jenkins/plantillas/Jenkinsfile.plantilla`
  (`generar.sh`); el validador de Jenkins aceptó la plantilla y los siete archivos.
- Primera ejecución completa, en secuencia, todas **SUCCESS** con las 14 etapas (Checkout,
  dependencias de integración, Build, Lint, Unit, Integración, SonarQube, Quality Gate, Trivy de
  dependencias, Gitleaks, Package con Trivy de imagen, Push Registry, E2E con BDD y Deploy):
  api-gateway #1 (8 min 40 s), departamentos #1 (5 min), vacaciones #1 (5 min 23 s),
  notificaciones #1 (5 min 25 s), auth #8 (4 min 42 s), empleados #1 (9 min 53 s) y perfiles #1.
- SonarQube: siete proyectos con el gate de cobertura ≥ 70 %; los siete Quality Gates en verde.
- Hallazgos corregidos al construir los pipelines:
  - **PyJWT 2.10.1 con CVE crítico** de bypass de autenticación → 2.14.0.
  - **Imágenes con CVE críticos** de OpenSSL, GnuTLS y Perl en la base, y de Tomcat, Spring y Netty
    en Java → parches de seguridad del SO en el runtime y Spring Boot 4.1.2. Resultado: 0 CRITICAL
    en las siete imágenes.
  - **E2E rota desde la Fase 2:** Jenkins pasaba al daemon rutas de su workspace para secretos y
    plantillas; ahora usa la ruta del host (`jenkins/host-repo.sh`). RabbitMQ de CI no leía su
    contraseña desde el secreto.
  - **Red de CI fija** (`microservicios_cicd-network`) aunque el proyecto se llame distinto:
    Jenkins la detecta al arrancar.
  - **Workspace sin limpiar:** un fallo simulado contaminaba el build siguiente; el checkout ahora
    deja el workspace idéntico al commit.

### Fase 10 — Registry, despliegue y rollback

- Cada pipeline publica `localhost:5001/<servicio>:<commit>-<build>` y `latest`, archiva el digest
  y despliega con `scripts/deploy.sh` (smoke test + rollback automático).
- Trazabilidad verificada: `pfola1-auth-service-1` corría `localhost:5001/auth-service:b87f6c6b-6`
  con `org.opencontainers.image.revision=b87f6c6bf702961c470e3bbc7d8bc60a69a68469`.
- Despliegue y rollback manuales: `deploy.sh auth-service 97cd93e3-4`, luego `3621bde7-5` y
  `rollback` volvió a `97cd93e3-4`; los tres pasaron el smoke test y quedaron en `.deploy/`.

### Fase 12 — escalabilidad y balanceo

- Traefik 3.6 como único puerto de negocio (:8080), con health checks activos a `/health`,
  reintento entre réplicas y ruta de respaldo 502 cuando un servicio no tiene réplicas.
  Traefik 3.5 no funcionaba con Docker Engine 29 (400 del daemon).
- `scripts/demo-escalado.sh`: con 2 réplicas de Gateway, Empleados, Departamentos y Vacaciones,
  120 peticiones → 120 respuestas 2xx, reparto 30/30 en Departamentos y 30/30 en Vacaciones;
  tras detener una réplica de cada servicio sin avisar, otras 120 → 120 respuestas 2xx, 0 errores.
- Auth, Perfiles y Notificaciones con 2 réplicas: healthy, `q.perfiles` con 2 consumidores y BDD
  sin caos 22/22 (el evento duplicado sigue dejando una notificación y un perfil).
- Scheduler de Vacaciones con `pg_try_advisory_lock`: prueba de integración con dos pools reales
  a la vez, solo uno ejecuta el ciclo.
- Prometheus descubre cada réplica por DNS; la alerta «Servicio caído» usa `absent()` para no
  perder el caso en que caen todas.
- BDD a través del balanceador: 23/23 escenarios, 179/179 pasos, tres ejecuciones seguidas.

### Fase 13 — seguridad

- Gateway: cabeceras seguras (CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`), CORS por lista
  blanca, 413 por cuerpo excesivo y rate limiting por IP (login y reset cuentan fallos; recuperación
  cuenta todas) con 429 y `Retry-After`. 80 pruebas, 97,29 %.
- JWT: además de firma, expiración, tipo, sujeto y rol, Gateway y Auth rechazan arrancar con un
  secreto de menos de 32 bytes (RFC 7518 §3.2).
- `test_logs_sin_secretos.py`: el ciclo completo de una cuenta no deja contraseñas, tokens de
  activación o recuperación ni access JWT en ningún log.
- Dependencias: 0 CRITICAL y 0 HIGH en los siete servicios (Trivy), tras subir OpenTelemetry
  (0.217) y forzar `protobufjs`, `glob` y `propagator-jaeger` corregidos en los dos servicios Node.
- Gitleaks: árbol y historial completo (163 commits) sin hallazgos.

### Fase 16 — caos

`COMPOSE_PROJECT=pfola1 bash scripts/caos.sh`, todas las verificaciones correctas:

- PostgreSQL de Empleados caído → `/health` 503 en 2,0 s; recuperado → 200.
- RabbitMQ caído → consumidores reportan `messageBroker DOWN`; el alta falla rápido con 503 C3
  (fail-fast: no se aceptan altas cuyo evento se perdería); al volver el broker los consumidores se
  reconectan y, cerrado el circuito, el alta responde 201.
- Departamentos caído → alta `PENDIENTE_VALIDACION`, `GET /departamentos` 503 C3, recuperación y
  reconciliación.

### Fase 9 — fallos simulados (9.FAIL)

Parámetro `SIMULAR_FALLO` del pipeline de departamentos-service, con el workspace limpio entre builds:

| Build | Fallo inyectado | Etapa donde se detuvo | Etapas posteriores |
|---|---|---|---|
| #10 | prueba unitaria que falla | Unit | no se ejecutan |
| #11 | umbral de cobertura 99 % | Unit | no se ejecutan |
| #12 | `RUN exit 1` en el Dockerfile | Package | sin push ni deploy |
| #13 | escenario BDD que espera 418 | E2E | sin deploy |
| #14 | ninguno | — | SUCCESS completo |
