# Plan de trabajo — Proyecto Final de Microservicios

Basado en [`proyectoFinal.pdf`](proyectoFinal.pdf), en los enunciados de los Retos 1–8
(`docs/retoN/retoN.pdf`, `docs/reto8/observabilidad.pdf`, `docs/reto4/catalogo-de-eventos.pdf`) y en el
estado real de la rama `proyecto-final`. El proyecto final **evoluciona el sistema existente**: no se
vuelve a implementar desde cero.

El avance real se registra en [`STATUS.md`](STATUS.md); la verificación requisito por requisito y
servicio por servicio, en [`MATRIZ-REQUISITOS.md`](MATRIZ-REQUISITOS.md); las pruebas de cierre, en
[`EVIDENCIAS.md`](EVIDENCIAS.md).

## Principio rector: toda práctica de un reto aplica a los siete servicios

Cada reto introdujo prácticas sobre uno o dos servicios de ejemplo (Swagger en el Reto 2, circuit
breaker en el Reto 3, deduplicación en el Reto 4, Bearer en Swagger en el Reto 5, cobertura en el Reto 6,
Jenkinsfile en el Reto 7, métricas/logs/trazas en el Reto 8). **En la entrega final cada práctica se
exige en los siete servicios**: `empleados-service`, `departamentos-service`, `auth-service`,
`perfiles-service`, `vacaciones-service`, `notificaciones-service` y `api-gateway`.

Por eso el plan se organiza alrededor de un **Estándar por servicio** (E01–E25). Una fase solo se
cierra cuando su parte del estándar está en verde **en las siete columnas** de la matriz por servicio.
Si un punto no aplica a un servicio (p. ej. base de datos en el Gateway), se marca `N/A` con la
justificación escrita; nunca se deja vacío.

## Objetivo de la entrega

Un sistema de gestión del ciclo de vida del empleado que se levanta desde cero con
`cp .env.example .env && bash scripts/setup-secrets.sh && docker compose up --build`, con siete
servicios en al menos cuatro lenguajes, comunicación síncrona solo Cliente↔Gateway↔Servicios (más
Empleados→Departamentos con resiliencia), eventos según el catálogo, seguridad JWT con RBAC y
propiedad, pruebas con cobertura ≥70 % por servicio, CI/CD en Jenkins con SonarQube y Registry,
observabilidad completa, cero secretos en Git, escalado horizontal con balanceo y documentación
reproducible, entregado como `.zip`/`.tar.gz`.

## Reglas de trabajo

1. La etiqueta `reto8-completo` es el punto de restauración; `main` queda intacta hasta aprobar la
   entrega.
2. Se trabaja en ramas cortas `proyecto-final/fN-<servicio|tema>` y se integran por PR a
   `proyecto-final` con revisión de otra persona.
3. Un PR = un paquete de trabajo (ver IDs `N.SERV` en cada fase). No mezclar secretos, funcionalidad,
   CI y evidencias en el mismo PR.
4. **`docker-compose.yml` tiene un solo dueño por ola** (Frente C). Los demás frentes piden cambios
   en su PR describiendo el bloque exacto; el dueño los integra para evitar conflictos.
5. Los contratos compartidos (catálogo de eventos, formato de health, formato de error, nombres de
   métricas, plantilla de README) se acuerdan **antes** de implementar y no se cambian sin avisar.
6. `STATUS.md` y la matriz por servicio se actualizan en el mismo PR que demuestra el avance.
7. No se reescribe historial publicado (ver `CLAUDE.md`, subtree de `empleados-service`).

## Frentes de trabajo y delegación

El trabajo se reparte en cuatro frentes. Con **dos personas**: Persona 1 = Frentes A + C,
Persona 2 = Frentes B + D (reparto original). Con **tres o cuatro personas** cada frente puede tener
dueño propio. Los nombres reales se asignan en `STATUS.md`.

| Frente | Alcance | Servicios / áreas propias |
|---|---|---|
| **A — Dominio Java/Go** | Código, pruebas, OpenAPI, métricas y logs de sus servicios | `empleados-service`, `perfiles-service`, `departamentos-service` |
| **B — Dominio Node/Python** | Código, pruebas, OpenAPI, métricas y logs de sus servicios | `api-gateway`, `vacaciones-service`, `auth-service`, `notificaciones-service` |
| **C — Plataforma** | Dockerfiles base, `docker-compose.yml`, redes, puertos, Jenkins, SonarQube, Registry, balanceador, empaquetado | Infraestructura y CI/CD transversales |
| **D — Observabilidad, seguridad y docs** | Prometheus, Grafana, Loki, Zipkin, alertas, auditoría de seguridad, documentación consolidada | `observability/`, `scripts/`, `docs/` |

Regla de delegación: **cada paquete de trabajo por servicio lo implementa el frente dueño del
servicio**; el frente transversal (C o D) define la plantilla/contrato y revisa.

## Arquitectura objetivo y decisiones compartidas

| Decisión | Elección | Estado |
|---|---|---|
| Entrada pública | API Gateway Express (`:8080`) como único puerto de negocio | Vigente |
| Comunicación síncrona | Cliente→Gateway→servicios; Empleados→Departamentos (exigida por el PDF) | Vigente |
| Comunicación asíncrona | RabbitMQ topic exchange según el catálogo del Reto 4 | Vigente |
| Identidad | Auth propio JWT HS256 (opción a). OIDC/JWKS (opción b) como bonificación | Vigente |
| Bases | Una por servicio con volumen propio; ninguna compartida | Vigente |
| Observabilidad | Loki/Promtail + Prometheus/Grafana + OTel/Zipkin | Vigente |
| CI/CD | Jenkins + SonarQube + Registry Docker local (`registry:2`) | Registry en Compose (Fase 14); publicación en Fase 10 |
| Secretos | Docker Compose Secrets + `*_FILE` generados por `scripts/setup-secrets.*` | Cerrado (Fase 2) |
| Balanceo | Traefik o Nginx delante de las réplicas; decisión en Fase 12 | Pendiente |
| Redes | Seis redes del PDF: `frontend`, `backend`, `messaging`, `data`, `observability`, `cicd` | Implementada (Fase 14) |
| Puertos de host | Los del PDF: Gateway 8080, Jenkins **8090**, Prometheus 9090, Grafana 3000, Zipkin 9411, SonarQube 9000, Registry **5001**, RabbitMQ UI 15672. Ningún servicio de negocio publica puerto | Implementada (Fase 14) |
| Estructura de entrega | Se conservan `services/` y `observability/` (renombrar rompe Jenkins y el subtree); se documenta la equivalencia con la estructura de referencia del PDF y se añaden las piezas faltantes (`openapi.yaml`, `Jenkinsfile`, `tests/` por servicio, `docs/arquitectura.md`, `docs/eventos.md`, `docs/guias/`, `scripts/seed-data`) | Vigente; equivalencia en `docs/estructura-entrega.md` (Fase 14) |
| Lenguajes | Java (Empleados, Perfiles), Go (Departamentos), Node.js (Gateway, Vacaciones), Python (Auth, Notificaciones) | Vigente |

## Contratos compartidos (se acuerdan antes de implementar)

### C1 — Envelope de eventos

```json
{ "id": "uuid", "type": "empleado.creado", "version": 1,
  "occurredAt": "2026-11-09T12:00:00Z", "producer": "empleados-service", "data": {} }
```

- Todos los campos son obligatorios. `id` se usa para deduplicación persistente.
- Contexto W3C (`traceparent`, `tracestate`, `baggage`) en headers AMQP.
- Nunca viajan contraseñas, hashes, secretos ni JWT de acceso. Los tokens de activación/recuperación
  solo en los eventos normativos y nunca en logs.

| Evento | Productor | Consumidores |
|---|---|---|
| `empleado.creado` | Empleados | Auth, Perfiles, Notificaciones (+ Vacaciones si mantiene réplica local) |
| `empleado.actualizado` | Empleados | Perfiles (+ Vacaciones si aplica) |
| `empleado.retirado` | Empleados | Auth, Perfiles, Notificaciones (+ Vacaciones si aplica) |
| `usuario.creado` | Auth | Notificaciones |
| `usuario.recuperacion` | Auth | Notificaciones |
| `cuenta.activada` | Auth | Notificaciones |
| `cuenta.desactivada` | Auth | Notificaciones |
| `vacaciones.programadas` | Vacaciones | Notificaciones |
| `vacaciones.iniciadas` | Vacaciones | Auth, Notificaciones |
| `vacaciones.finalizadas` | Vacaciones | Auth, Notificaciones |

En caso de discrepancia prevalece `docs/reto4/catalogo-de-eventos.pdf`.

### C2 — Health

```json
{ "status": "UP", "service": "empleados-service", "version": "x.y.z",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

HTTP 200 si todos los checks obligatorios están `UP`, 503 si alguno está `DOWN`, con respuesta en
menos de 3 s aunque la dependencia cuelgue (timeout interno del check). Solo dependencias reales del
proceso. El Gateway reporta su propio proceso, sin ping a los backends; un agregado opcional va en
`/health/services`.

### C3 — Error HTTP uniforme

```json
{ "timestamp": "...", "status": 404, "error": "Not Found", "message": "Empleado 42 no existe",
  "path": "/empleados/42", "traceId": "..." }
```

Sin trazas de pila ni detalles internos. Códigos: 400 validación, 401 sin token/token inválido,
403 rol o propiedad, 404 no existe, 409 conflicto/unicidad, 500 inesperado, 503 dependencia caída.

### C4 — Métricas (nombres comunes en los siete servicios)

- `http_requests_total{method,route,status}` (contador).
- `http_request_duration_seconds` (histograma, con `_sum`/`_count`/`_bucket`).
- CPU y memoria del proceso (métricas por defecto de cada librería).
- Métricas de negocio por servicio (tabla en Fase 11).
- Ruta: `/actuator/prometheus` (Spring) o `/metrics` (resto); sin JWT; un `scrape_job` por servicio.

### C5 — Log estructurado

JSON con `timestamp`, `level`, `service`, `message`, `traceId`, `spanId` y contexto de negocio
(`employeeId`, `eventId`, etc.). ERROR incluye stack trace. Nunca contraseñas, tokens ni secretos.

### C6 — Plantilla de README por servicio

1. Propósito y responsabilidad. 2. Lenguaje, framework, base y por qué. 3. Endpoints (tabla) y URL de
Swagger. 4. Eventos publicados/consumidos. 5. Variables de entorno y secretos (`*_FILE`), sin valores.
6. Migraciones y rollback. 7. Pruebas: comando, ruta de JUnit y de cobertura, cobertura actual,
exclusiones. 8. Observabilidad: librerías, ruta de métricas, métricas de negocio. 9. Resiliencia
(timeouts, reintentos, CB, DLQ). 10. Decisiones técnicas con su porqué.

## Estándar por servicio (E01–E25)

Aplica a los siete servicios. La columna **Fase** indica dónde se cierra.

| ID | Requisito | Origen | Fase |
|---|---|---|---|
| E01 | Dockerfile multi-stage, imagen base con versión fijada, `.dockerignore` | R1, R7, PDF §5 | 7 |
| E02 | Ejecución como usuario no root; `curl`/`wget` disponible para el healthcheck | R2, R8, PDF Seg. | 7 |
| E03 | Toda configuración, URL y credencial por variable de entorno o `*_FILE`; hostnames por DNS de Compose, nunca `localhost` | R2, R5, PDF §4 | 2 ✅ / revalidar 14 |
| E04 | Sin `ports:` en Compose (solo `expose:`); únicamente el Gateway y las UIs administrativas publican puerto | R3 | 14 |
| E05 | Base propia con volumen, migraciones versionadas con rollback (Liquibase / golang-migrate / Alembic / node-pg-migrate) y `UNIQUE` en el esquema donde haya unicidad (`N/A` Gateway) | R2, R4 | 5 |
| E06 | Healthcheck de Compose contra `/health` y `depends_on: condition: service_healthy` | R2, R8 | 14 |
| E07 | `GET /health` según contrato C2, 200/503 real, probado con la dependencia caída | R8, PDF §3 | 5 |
| E08 | OpenAPI completo (todas las operaciones, schemas, ejemplos, códigos 2xx/4xx/5xx, `bearerAuth`), Swagger UI interactiva y `openapi.yaml` exportado en la carpeta del servicio | R2, R5, PDF §1 | 5 |
| E09 | Errores según contrato C3 | R2 | 5 |
| E10 | Todo cliente HTTP saliente con timeout explícito, reintentos con backoff (1 s→2 s→4 s, máximo definido) y circuit breaker con fallback de negocio; `N/A` documentado si el servicio no llama a otro | R2, R3 | 6 |
| E11 | Productor: envelope C1 completo, publicación **después del commit** en BD, fallo de publicación registrado sin revertir, `traceparent` inyectado | R4, R8 | 4 |
| E12 | Consumidor: deduplicación persistente por `id` (atómica con el efecto de negocio), ack manual, reintento con espera, DLQ, rechazo de poison/inválidos, reconexión, `prefetch`, extracción de `traceparent` | R4, R8, PDF | 4 |
| E13 | Pruebas unitarias de dominio con dobles, rápidas, con casos de error | R6, PDF §2 | 8 |
| E14 | Pruebas de integración contra base/broker reales (Testcontainers o instancia dedicada) del camino feliz de **todas** las operaciones | R6, PDF §2 | 8 |
| E15 | Cobertura ≥70 % de la lógica de negocio, medida en el servicio, con exclusiones documentadas | R6, PDF §2 | 8 |
| E16 | Un comando ejecuta la suite y sale ≠0 si falla; JUnit XML + cobertura (JaCoCo/Cobertura/LCOV/`coverage.out`) en ruta documentada; reportes en `.gitignore` | R6 | 8 |
| E17 | Evidencia de prueba de mutación manual (se rompe una regla y una prueba falla) | R6 | 8 |
| E18 | `Jenkinsfile` declarativo: Checkout → Build → Lint → Unit → Integración → `junit`+`recordCoverage` → SonarQube → Quality Gate (≥70 %, `abortPipeline: true`) → Seguridad (dependencias, imagen, secretos) → Package (tag inmutable) → Push Registry → Deploy + smoke/E2E | R7, PDF §4 | 9 |
| E19 | Proyecto SonarQube propio (`sonar-project.properties` o plugin Maven) importando cobertura | R7 | 9 |
| E20 | Métricas según contrato C4 + métricas de negocio del servicio, scrapeadas por Prometheus | R8, PDF §9 | 11 |
| E21 | Logs según contrato C5, recogidos por Promtail/Loki con label `service` | R8, PDF §8 | 11 |
| E22 | OpenTelemetry: `service.name`, propagadores `tracecontext,baggage`, exportador a Zipkin, spans HTTP + AMQP (+BD donde la librería lo permita) | R8, PDF §10 | 11 |
| E23 | Stateless y escalable: sin estado en memoria que importe, sin `container_name` ni puerto de host fijo; arranca con `--scale <servicio>=2` sin errores | PDF Escal. | 12 |
| E24 | Seguridad: validación de entrada, sin detalles internos, RBAC/propiedad aplicados (en Gateway o servicio, documentado), endpoints técnicos (`/health`, métricas, docs) accesibles sin JWT solo en red interna o explícitamente públicos | R5, PDF Seg. | 13 |
| E25 | README del servicio según plantilla C6 | R2–R8 | 15 |

## Fases

Leyenda de olas (para paralelizar): **Ola 1** puede empezar ya; cada ola siguiente depende de la
anterior solo donde se indica.

### Fases 0–3 — Cerradas

| Fase | Resultado |
|---|---|
| 0 | Etiqueta `reto8-completo`, rama `proyecto-final`, PDF, plan y estado |
| 1 | Matriz normativa, baseline ejecutable, BDD 18/18, brechas registradas |
| 2 | Cero secretos: Docker Secrets, `*_FILE`, scripts de generación, Gitleaks sin hallazgos, rotación documentada |
| 3A | CRUD de Departamentos (PUT/DELETE) y modelo normativo de Perfiles |
| 3B | Vacaciones modificables, plantillas externas de Notificaciones, ciclo de cuenta revalidado |

Ver evidencias en [`EVIDENCIAS.md`](EVIDENCIAS.md).

### Fase 4 — Eventos, idempotencia y DLQ en todos los productores y consumidores (Ola 1, cerrada)

**Frentes:** A y B; integra B. **Estándar:** E11, E12.

| Paquete | Trabajo |
|---|---|
| 4.DOC | `docs/eventos.md`: por evento, productor, consumidores, exchange, routing key, cola, payload de ejemplo, versión y política de reintento/DLQ |
| 4.EMP | Productor: auditar envelope completo de `empleado.*`, publicación post-commit (`@TransactionalEventListener(AFTER_COMMIT)` o equivalente), traceparent |
| 4.AUTH | Productor `usuario.*`/`cuenta.*` post-commit; consumidor `empleado.*`/`vacaciones.*` con dedup atómica, retry, DLQ (avance del commit `ad01d03`) |
| 4.PERF | Consumidor `empleado.*` con dedup atómica, retry, DLQ (avance del commit `ad01d03`) |
| 4.VAC | Productor `vacaciones.*` post-commit (incluido el scheduler); consumidor con dedup, retry y DLQ (avance del commit `ad01d03`) |
| 4.NOTIF | Consumidor de todos los eventos que le corresponden con dedup que evite correos duplicados, retry, DLQ |
| 4.DEP / 4.GW | `N/A` documentado (no producen ni consumen eventos) |
| 4.E2E | Fixtures que republican cada evento crítico dos veces y un mensaje inválido; prueba automatizada |

**Cierre:** cada evento republicado no duplica cuentas, perfiles, correos ni transiciones; un poison
termina en DLQ sin bloquear la cola; columnas E11/E12 en verde.

### Fase 5 — Contrato HTTP por servicio: OpenAPI, errores, health y migraciones (Ola 1)

**Frentes:** A y B sobre sus servicios; D revisa. **Estándar:** E05, E07, E08, E09.

| Paquete | Trabajo |
|---|---|
| 5.EMP | Health con timeout interno: hoy no responde 503 al caer PostgreSQL (hallazgo de Fase 1); OpenAPI con todos los códigos; `openapi.yaml` exportado |
| 5.DEP | OpenAPI con PUT/DELETE, 409, `bearerAuth`; errores C3 |
| 5.PERF | OpenAPI con los nuevos campos del perfil, 403 por propiedad; health con broker |
| 5.VAC | OpenAPI con PUT, 400 de las cuatro validaciones (incluido el período en conflicto), `bearerAuth` |
| 5.AUTH | OpenAPI de los cuatro endpoints `/auth/*`, sin `/register`, `bearerAuth` en `change-password` |
| 5.NOTIF | OpenAPI de los endpoints de consulta; health con BD y broker |
| 5.GW | Auditar las rutas del PDF; **Swagger UI agregada en `/swagger`** con los siete contratos; `openapi.yaml` propio; 503 JSON cuando un destino no responde |
| 5.MIG | Revisar que los cinco servicios con BD tengan migraciones con rollback probado (`down`) y `UNIQUE` donde aplica |
| 5.TEST | Prueba automatizada por servicio que valida que el OpenAPI publicado es válido y que `/health` devuelve 503 con la dependencia caída |

**Cierre:** siete Swagger UI navegables desde el Gateway, siete `openapi.yaml`, health 200/503
demostrado por dependencia en cada servicio.

### Fase 6 — Resiliencia de la comunicación síncrona (Ola 2, cerrada)

**Frentes:** B (Gateway) y A (Empleados). **Estándar:** E10.

| Paquete | Trabajo |
|---|---|
| 6.GW | Circuit breaker por backend (opossum o equivalente), timeout por ruta y reintento con backoff **solo en métodos idempotentes** (GET); fallback 503 JSON; estado de los circuitos observable (métrica o endpoint) |
| 6.EMP | Revalidar Resilience4j Empleados→Departamentos: umbral, ventana, tiempo abierto, `PENDIENTE_VALIDACION` + reconciliación, timeout 5 s, backoff 1-2-4 s |
| 6.DOC | Tabla de parámetros y su porqué en los README; `N/A` justificado en los servicios sin llamadas salientes |
| 6.TEST | Pruebas que demuestran CLOSED → OPEN → HALF_OPEN → CLOSED en ambos clientes |

**Cierre:** los dos clientes HTTP del sistema cumplen E10 con evidencia.

### Fase 7 — Contenerización uniforme (Ola 1)

**Frente:** C define la plantilla; A y B la aplican a sus servicios. **Estándar:** E01, E02.

| Servicio | Situación actual | Trabajo |
|---|---|---|
| api-gateway | Una etapa, `USER node` | Multi-stage (deps → runtime), `npm ci --omit=dev` |
| vacaciones-service | Una etapa, `USER node` | Multi-stage, sin devDependencies en runtime |
| auth-service | Una etapa, root | Multi-stage (wheels → slim), usuario no root |
| notificaciones-service | Una etapa, root | Multi-stage, usuario no root, plantillas montadas en solo lectura |
| empleados-service | Multi-stage, root | Usuario no root, JRE mínimo |
| perfiles-service | Multi-stage, root | Usuario no root, JRE mínimo |
| departamentos-service | Multi-stage, root | Usuario no root (distroless o alpine + usuario) |

Todos: base con versión fijada, `.dockerignore`, `curl`/`wget` para el healthcheck, sin secretos en
capas.

**Cierre:** siete imágenes multi-stage no root; tamaño de cada imagen registrado en la matriz.

### Fase 8 — Pruebas, cobertura y aceptación funcional (Ola 2, cerrada)

**Frentes:** A y B sobre sus servicios; D integra la BDD. **Estándar:** E13–E17.

| Paquete | Situación actual (archivos de prueba) | Trabajo |
|---|---|---|
| 8.GW | 1 | Unitarias de JWT/RBAC/propiedad/proxy/CB; integración con backends simulados; ≥70 % |
| 8.PERF | 1 | Unitarias de servicio y consumidor; integración con BD real (Testcontainers); ≥70 % |
| 8.DEP | 2 | Unitarias de servicio y handlers; integración con MySQL real; ≥70 % con `coverage.out` |
| 8.AUTH | 3 (72 %) | Integración con BD y broker reales; mantener ≥70 % |
| 8.NOTIF | 3 (73 %) | Integración con BD, broker y SMTP simulado; mantener ≥70 % |
| 8.VAC | 6 (100 % de módulos seleccionados) | Medir sobre **toda** la lógica de negocio, no sobre módulos elegidos; integración con BD real |
| 8.EMP | 8 | Medir cobertura real con JaCoCo; integración con PostgreSQL real |
| 8.MUT | — | Una prueba de mutación documentada por servicio |
| 8.BDD | 18 escenarios | Completar onboarding, perfil, vacaciones, offboarding, seguridad (sin token, token inválido, USER vs ADMIN, perfil ajeno 403), duplicados y circuit breaker; tres ejecuciones seguidas sin intermitencias |

**Cierre:** siete reportes de cobertura ≥70 % en ruta documentada, BDD en verde tres veces seguidas.

### Fase 9 — Pipelines Jenkins para los siete servicios (Ola 2–3)

**Frente:** C; A y B aportan comandos exactos de sus stacks. **Estándar:** E18, E19.

| Paquete | Trabajo |
|---|---|
| 9.TPL | Plantilla de `Jenkinsfile` con todas las etapas de E18, agente Docker por stack y caché de dependencias en volúmenes |
| 9.EMP / 9.VAC | Endurecer los dos existentes: lint, integración, seguridad, push y deploy |
| 9.GW / 9.DEP / 9.AUTH / 9.PERF / 9.NOTIF | Crear `Jenkinsfile` y proyecto SonarQube de cada uno |
| 9.SEC | Etapa de seguridad: análisis de dependencias (OWASP dependency-check, `npm audit`, `pip-audit`, `govulncheck`), escaneo de imagen (Trivy) y de secretos (Gitleaks) |
| 9.JOBS | Siete jobs aprovisionados por JCasC/job-dsl sin pasos manuales; trigger por webhook o `pollSCM` |
| 9.E2E | Etapa E2E serializada (lock) para no chocar entre pipelines; `wait-for-healthy.sh`; `down -v` en `post always` |
| 9.FAIL | Cuatro fallos simulados por pipeline (unitaria, cobertura <70 %, Dockerfile roto, BDD) que se detienen en su etapa |

**Cierre:** siete pipelines verdes y un fallo real que detiene package/push/deploy.

### Fase 10 — Docker Registry, artefactos y despliegue (Ola 3)

**Frente:** C.

- `registry:2` persistente en Compose, publicado en `:5001`.
- Jenkins publica cada imagen con tag inmutable (`<commit>-<build>`) y digest, además de `latest`.
- Compose de despliegue (`docker-compose.deploy.yml` o perfil) que consume imágenes del Registry; el
  Compose principal sigue construyendo con `--build`.
- Smoke test tras el despliegue y procedimiento de rollback a un tag anterior.

**Cierre:** un pipeline verde publica y despliega una imagen identificable (commit → imagen → contenedor).

### Fase 11 — Observabilidad uniforme en los siete servicios (Ola 2, cerrada)

**Frente:** D define contratos y dashboards; A y B instrumentan. **Estándar:** E20, E21, E22.

| Servicio | Métricas de negocio mínimas |
|---|---|
| empleados | `empleados_creados_total`, `empleados_retirados_total`, `empleados_actualizados_total` |
| departamentos | `departamentos_creados_total`, `departamentos_consultas_total` |
| auth | `logins_total{resultado}`, `cuentas_activadas_total`, `cuentas_desactivadas_total{tipo}`, `recuperaciones_solicitadas_total` |
| perfiles | `perfiles_creados_total`, `perfiles_actualizados_total`, `perfiles_archivados_total` |
| vacaciones | `vacaciones_programadas_total`, `vacaciones_iniciadas_total`, `vacaciones_finalizadas_total` |
| notificaciones | `notificaciones_enviadas_total{tipo}`, `notificaciones_fallidas_total{tipo}` |
| gateway | `gateway_upstream_errors_total{servicio}`, estado del circuit breaker |
| consumidores | `eventos_procesados_total{tipo}`, `eventos_duplicados_total{tipo}`, `eventos_dlq_total{tipo}` |

- Auditar en cada servicio los nombres de C4, los campos de C5 y E22.
- Dashboards versionados: resumen (`up` por servicio), RED por servicio, negocio, eventos/DLQ,
  circuit breaker.
- Alertas: servicio caído (`up==0` 1 min), 5xx >10 % 2 min, latencia p95 >2 s 2 min, mensajes en DLQ,
  memoria >85 % 5 min; contact point Discord con webhook como secreto.
- Trazas completas de onboarding (Gateway → Empleados → Departamentos → broker → Auth/Perfiles →
  Notificaciones) y de vacaciones (scheduler → Auth → Notificaciones).
- Correlación dashboard → Loki (por `traceId`) → Zipkin.
- La caída del stack de observabilidad no rompe el negocio.

**Cierre:** las siete columnas E20–E22 en verde; dashboards y alertas se reconstruyen desde Git;
alerta real recibida en Discord.

### Fase 12 — Escalabilidad, balanceo y alta disponibilidad (Ola 3)

**Frentes:** C (balanceador y Compose); A y B (stateless de sus servicios). **Estándar:** E23.

| Paquete | Trabajo |
|---|---|
| 12.LB | Traefik o Nginx entre Gateway y servicios (o delante del Gateway) con health-aware routing contra `/health`; decisión justificada |
| 12.STATELESS | Auditar los siete servicios: sin estado en memoria, sin `container_name`, sin puerto host; `docker compose up --scale <servicio>=2` funciona para cada uno |
| 12.VAC | Scheduler seguro con N réplicas: lock en BD (`pg_try_advisory_lock` o `SELECT … FOR UPDATE SKIP LOCKED`) |
| 12.CONS | Consumidores RabbitMQ como competing consumers: dos réplicas no duplican efectos (se apoya en E12) |
| 12.GW | Gateway escalable a dos réplicas detrás del balanceador |
| 12.DEMO | Al menos Gateway + Empleados + Departamentos con dos réplicas; reparto de tráfico visible en métricas; detener una réplica sin errores para el cliente |

**Cierre:** cualquier servicio escala a dos réplicas; una réplica cae y el sistema sigue atendiendo.

### Fase 13 — Seguridad integral (Ola 3)

**Frente:** D; revisión cruzada. **Estándar:** E24.

- RBAC ADMIN/USER en todas las rutas públicas según el Reto 5 (USER solo lectura, salvo cambio de su
  contraseña y `PUT /perfiles/{empleadoId}` propio; ajeno = 403).
- Validación JWT: firma, expiración, tipo de token, sujeto y rol; el token de reset no sirve como acceso.
- Auth: estado de cuenta `ACTIVA|SUSPENDIDA_TEMPORAL|DESACTIVADA_PERMANENTE`; retiro durante vacaciones
  no se reactiva; BCrypt; política de contraseña; sin `/register`.
- Rate limiting en login y recuperación.
- Cabeceras seguras en el Gateway (helmet o equivalente), CORS explícito, límite de tamaño de cuerpo.
- Correos y eventos sin contraseñas; logs sin tokens (prueba automatizada).
- Auditoría de offboarding: fecha y hora de desactivación persistida y consultable.
- Escaneos de la Fase 9 sin hallazgos críticos.
- **Bonificación:** Keycloak/Authentik con OIDC y validación JWKS en el Gateway, solo con la opción JWT
  cerrada y en regresión.

**Cierre:** pruebas de seguridad en verde y cero hallazgos críticos.

### Fase 14 — Orquestación, redes, puertos y estructura de entrega (Ola 2, cerrada)

**Frente:** C. **Estándar:** E03, E04, E06.

- Seis redes del PDF y cada servicio solo en las redes que necesita (p. ej. bases solo en `data` con su
  servicio; Jenkins/Sonar/Registry en `cicd`).
- Quitar `ports:` de Departamentos (8081), Perfiles (8083), Notificaciones (8084) y Vacaciones (8085).
- Puertos del PDF: Jenkins 8090, Prometheus 9090, Registry 5001; tabla de puertos sin colisiones.
- Healthcheck de Compose a `/health` en los siete y `depends_on: service_healthy` en toda dependencia.
- Imágenes de infraestructura con versión fijada; volúmenes `db-data`, `jenkins-data`, `logs-data`,
  `metrics-data` o equivalentes.
- Jenkins, SonarQube y Registry dentro del `docker compose up --build` principal.
- Estructura de entrega: `openapi.yaml`, `Jenkinsfile` y `tests/` por servicio; `secrets.example/`;
  `scripts/setup-secrets.sh`, `scripts/seed-data` idempotente (vía API o SQL por servicio);
  `scripts/init-db.sql` o justificación de que las migraciones lo sustituyen.
- Arranque en frío, reinicio conservando datos, `down` vs `down -v` documentados.

**Cierre:** máquina limpia + setup de secretos + un comando deja los siete servicios `healthy`.

### Fase 15 — Documentación final (Ola 4)

**Frente:** D integra; A y B escriben los README de sus servicios. **Estándar:** E25.

- README raíz con las siete secciones mínimas del PDF: descripción y arquitectura; requisitos y
  versiones; ejecución (`cp .env.example .env`, `setup-secrets`, `docker compose up --build`, URLs);
  tabla servicio ↔ lenguaje ↔ BD ↔ puerto, broker y herramientas; endpoints del Gateway con `curl`;
  configuración, secretos y rotación; accesos por defecto y admin semilla (referenciando la variable o
  secreto, nunca el valor).
- Siete README de servicio con la plantilla C6.
- `docs/arquitectura.md` con diagramas de contexto, contenedores, redes, eventos, secuencia de
  onboarding/vacaciones/offboarding, CI/CD y despliegue.
- `docs/eventos.md` (de la Fase 4).
- `docs/guias/`: Jenkins, SonarQube, Registry, RabbitMQ, Grafana, Loki, Prometheus, Zipkin, Discord,
  balanceador y secretos.
- Equivalencia entre la estructura del repositorio y la de referencia del PDF.

**Cierre:** una persona externa instala, ejecuta, prueba y entiende el sistema sin ayuda oral.

### Fase 16 — Validación final, caos y paquete de entrega (Ola 4)

**Responsable:** todos.

- Clon limpio en otra ruta, sin caché ni `.env`: setup, `up --build`, todo `healthy`.
- Suites, cobertura, Quality Gates y BDD; los siete pipelines en verde.
- Demostración de onboarding, perfil, vacaciones y offboarding de extremo a extremo.
- Duplicados sin efecto; poison en DLQ.
- Caos: caída de base, broker, servicio y réplica; circuit breaker; recuperación.
- Dashboards, alerta en Discord, consulta Loki y traza completa en Zipkin.
- Auditoría final de secretos (árbol e historial) y de artefactos no deseados.
- Generar `.zip`/`.tar.gz` sin `.env`, `secrets/`, volúmenes, `node_modules`, `target`, `.venv` ni
  reportes; descomprimir en otra ruta y repetir el smoke test.
- Matriz por servicio sin celdas rojas ni vacías.

**Cierre:** paquete reproducible y todos los criterios obligatorios demostrados.

## Olas, dependencias y paralelización

```text
Ola 1:        F4 (A+B, cerrada)    F5 (A+B, cerrada)   F7 (C, cerrada)
Ola 2:        F6 (cerrada)   F8 (cerrada)   F11 (cerrada)   F14 (cerrada)   F9 plantilla (validada)
Ola 3:        F9 completa (C)   F10 (C)   F12 (C + A/B)   F13 (D)
Ola 4:        F15 (D + A/B)   F16 (todos)
```

- F6 depende de F5 (contrato de error 503). F8 puede empezar en Ola 1 en servicios ya estables.
- F9 necesita F7 (Dockerfiles) y avanza junto con F8; el Quality Gate fallará hasta que F8 cierre cada
  servicio, y eso es esperado.
- F12 necesita F14 (sin puertos ni nombres fijos) y F4 (consumidores idempotentes).
- F13 aplica desde ya en cada PR; la fase es la auditoría de cierre.
- Durante cada ola solo el Frente C edita `docker-compose.yml`.

## Matriz de evaluación → fases

| Criterio del PDF | Peso | Fases |
|---|---:|---|
| Funcionalidad | 35 % | 3A, 3B, 4, 5, 8, 16 |
| Calidad técnica | 25 % | 5, 7, 8, 9 |
| Observabilidad | 15 % | 11, 16 |
| Seguridad y configuración | 10 % | 2, 13, 14, 16 |
| Escalabilidad y resiliencia | 10 % | 4, 6, 12, 16 |
| DevOps | 5 % | 9, 10, 14, 16 |
| Documentación | 5 % | 15, 16 |

> Los pesos publicados suman 105 %. Se atienden todos tal como aparecen y se consulta con el docente.

## Bonificaciones (después de cerrar lo obligatorio)

1. Identity Provider Keycloak/Authentik con OAuth2/OIDC y JWKS (puntaje extra en Seguridad).
2. `GET /empleados/{id}/completo` en el Gateway con manejo de fallo parcial de Perfiles.
3. `/health/services` agregado, separado del health propio del Gateway.
4. Probes `/health/live`, `/health/ready`, `/health/startup`.
5. Pruebas de carga comparativas con 1 vs 2 réplicas.

## Evidencias mínimas obligatorias

- `docker compose config` sin secretos y `docker compose ps` con los siete servicios `healthy`.
- Siete Swagger UI y siete `openapi.yaml`; Swagger agregado en el Gateway.
- Siete reportes de cobertura ≥70 % y JUnit.
- Siete pipelines en Jenkins (Stage View), Quality Gates y un build fallido por cada tipo de fallo.
- Imagen en el Registry con tag inmutable y digest; despliegue y rollback.
- BDD de los cuatro flujos de negocio, seguridad y duplicados.
- Health `UP` y `DOWN` por dependencia en cada servicio.
- Circuit breaker CLOSED → OPEN → HALF_OPEN → CLOSED en Gateway y Empleados.
- Mensaje poison en DLQ y duplicado sin efecto.
- Dos réplicas, reparto de tráfico y continuidad tras la caída de una.
- Dashboard, consulta Loki, traza completa en Zipkin y alerta en Discord.
- Escaneo de secretos del árbol y del historial; escaneo de dependencias e imágenes.
- Arranque desde el paquete descomprimido.

## Riesgos y controles

1. **Cumplir solo en el servicio de ejemplo:** la matriz por servicio no admite celdas vacías.
2. **Conflictos en `docker-compose.yml`:** un dueño por ola.
3. **Cobertura engañosa:** se mide sobre toda la lógica de negocio de cada servicio, con exclusiones escritas.
4. **Falso health:** probar con la dependencia caída y timeout interno del check.
5. **Scheduler duplicado al escalar:** lock en BD antes de escalar Vacaciones.
6. **Pipeline decorativo:** los fallos reales deben detener package/push/deploy.
7. **Puertos en conflicto:** tabla de puertos única en el README.
8. **Evidencia no reproducible:** cada captura con comando, fecha, commit y resultado esperado.

## Definición de terminado de una fase

1. Código y configuración versionados en `proyecto-final` mediante PR revisado.
2. Las celdas del estándar que la fase cierra están en verde en los siete servicios (o `N/A` justificado).
3. Las pruebas automáticas pasan y no baja la cobertura ni se rompe la BDD.
4. Existe evidencia reproducible en `EVIDENCIAS.md`.
5. `STATUS.md` y la matriz se actualizaron en el mismo PR.
6. No se introdujeron secretos ni artefactos locales.
