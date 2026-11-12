# perfiles-service

Réplica del perfil de cada empleado. Java 21, Spring Boot, PostgreSQL propio y Spring AMQP.
No comparte carpeta ni base con `empleados-service`. No publica eventos: el `PUT` solo edita
los campos de RRHH.

## Por qué estos eventos

| Evento | Efecto |
|---|---|
| `empleado.creado` | Inserta el perfil. `archivado` nace en `false`. Teléfono, dirección, ciudad y biografía nacen en `""`. `nombre` y `email` salen del evento |
| `empleado.actualizado` | Copia `nombre` y `email`. No pisa teléfono, dirección, ciudad, biografía ni `archivado` |
| `empleado.retirado` | `archivado = true`. La fila se queda, así el `GET` sigue respondiendo 200 |

La cola es `q.perfiles`, durable, con ack manual y prefetch 1. Antes de cualquier efecto se busca
el `id` del envelope en `eventos_procesados`. El mismo evento no crea un segundo perfil. Si el
alta ya dejó un perfil y llega otro `empleado.creado` con otro `id`, tampoco se inserta otra fila.

Si `empleado.actualizado` o `empleado.retirado` llegan antes del alta, se registra el error y se
confirma el mensaje. No se inventa un perfil y no se reencola: un reintento no traería el alta.

### Eventos y resiliencia (Proyecto final, Fase 4)

La política común de los cuatro consumidores está en [`docs/eventos.md`](../../docs/eventos.md).
En este servicio:

- **Envelope validado antes de tocar la base** (`EnvelopeEntrante`). Si no es JSON, falta alguno
  de `id`, `type`, `version`, `occurredAt`, `producer`, `data`, o el `type` no es `empleado.*`,
  se hace `basicReject` sin requeue y el mensaje termina en `q.perfiles.dlq`. Por qué: un
  poison nunca se va a procesar bien y con prefetch 1 bloquearía la cola.
- **Deduplicación atómica.** `PerfilEventoService.procesar` es `@Transactional`: la consulta y
  la inserción en `eventos_procesados` y el cambio del perfil se confirman juntos. Si dos
  réplicas reciben el mismo `id`, la PK hace fallar a la segunda, que reintenta y lo ve como
  duplicado.
- **Reintento solo a esta cola.** Un fallo publica el mensaje en `q.perfiles.retry` por el
  exchange por defecto con `x-retry-count + 1` y los headers originales (`traceparent`
  incluido; `x-death` lo gestiona el broker y no se copia), y confirma el original. La cola
  retry tiene TTL 5 s y dead-letter a `""` con routing key `q.perfiles`. Antes hacía
  dead-letter al topic con la routing key del evento, y el reintento de Perfiles volvía a
  llegarle a Auth, Notificaciones y Vacaciones. Con `x-retry-count` = 3 el siguiente fallo va a
  la DLQ. Si ni siquiera se puede publicar el reintento, el mensaje va a la DLQ: nunca se pierde.
- **Topología como beans.** Exchange, `onboarding.dlx`, las tres colas y sus bindings son
  `Declarable`: `RabbitAdmin` los vuelve a declarar en cada reconexión. La reconexión y el
  `prefetch` 1 los da el contenedor de Spring AMQP.
- **Arranque con colas antiguas** (`MigradorColas`). Si `q.perfiles` o `q.perfiles.retry`
  existen con otros argumentos (`PRECONDITION_FAILED`), se borran y recrean solo si están
  vacías y luego se redeclaran los bindings. Con mensajes dentro se registra el error y se deja
  la decisión a una persona (sección Migración de `docs/eventos.md`).

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| `GET` | `/perfiles/{empleadoId}` | 200 con el perfil (también si está archivado), o 404 |
| `PUT` | `/perfiles/{empleadoId}` | 200. Editables: `telefono`, `direccion`, `ciudad`, `codigoPostal`, `biografia`, `fotoUrl`, `redesSociales`. Un campo ausente no se borra. 400 si el JSON está mal formado, 404 si no hay perfil |
| `GET` | `/perfiles` | 200, arreglo |
| `GET` | `/health` | Contrato C2: 200 si PostgreSQL y RabbitMQ están `UP`, 503 si no. Público, lo usa Docker |
| `GET` | `/swagger-ui.html`, `/v3/api-docs`, `/v3/api-docs.yaml` | OpenAPI de Springdoc |

Las operaciones de `/perfiles` exigen `bearerAuth`. El Gateway responde `401` sin token o con token
inválido y `403` cuando un `USER` hace `PUT` sobre un perfil ajeno (compara `empleadoId` con el `sub`
del token; un `ADMIN` edita cualquiera). El servicio además puede responder `500` y `503`
(PostgreSQL caído). Una ruta inexistente responde `404` y un método no soportado `405`.

El cuerpo incluye `id`, `empleadoId`, `nombre`, `apellido`, `email`, `telefono`, `direccion`,
`ciudad`, `codigoPostal`, `cargo`, `area`, `numeroEmpleado`, `fotoUrl`, `redesSociales`,
`biografia`, `fechaCreacion` y `archivado`.

## Contrato HTTP (Fase 5)

### Error uniforme (contrato C3)

Todas las respuestas de error usan el mismo cuerpo que los otros seis servicios
(`GlobalExceptionHandler` + `dto/ApiError`), en lugar del anterior `{"mensaje": "..."}`:

```json
{ "timestamp": "2026-11-09T12:00:00Z", "status": 404, "error": "Not Found",
  "message": "El perfil del empleado E999 no existe", "path": "/perfiles/E999",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

`message` nunca lleva trazas, SQL ni hosts; un error inesperado responde `Error interno del
servidor` y el detalle va al log. `traceId` sale del MDC del agente Java de OpenTelemetry
(`trace_id`) o, sin agente, del header `traceparent`; si no hay traza se omite. PostgreSQL caído
durante una operación responde `503`; Hikari espera como máximo `DB_CONNECTION_TIMEOUT_MS` (5 s).

### Health (contrato C2)

```json
{ "status": "UP", "service": "perfiles-service", "version": "0.0.1-SNAPSHOT",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

`messageBroker` se revisa porque el servicio consume `empleado.*` de RabbitMQ: sin broker el perfil
deja de replicarse aunque el API responda. Cada check corre en un hilo de un pool acotado y el
controlador espera como máximo `HEALTH_CHECK_TIMEOUT_MS` (2 s) en total, así que `/health` responde
`503` en menos de 3 s aunque PostgreSQL o RabbitMQ cuelguen (el mismo hallazgo de la Fase 1 en
empleados-service: Hikari espera hasta 30 s por una conexión). `version` es la del pom; se puede
sobrescribir con `SERVICE_VERSION`.

### OpenAPI exportado

[`openapi.yaml`](openapi.yaml) se genera desde Springdoc y no se edita a mano. Documenta las tres
operaciones con ejemplos, `bearerAuth` (HTTP bearer, JWT) en las de negocio y no en `/health`, y
todos los códigos: los del controlador (`400`, `403` por propiedad, `404`) y los comunes que añade
`OpenApiConfig.respuestasComunesDeError()` (`401`, `500`, `503`). `OpenApiContratoTest` falla si el
archivo difiere de lo que genera Springdoc o si una ruta registrada no está documentada:

```bash
./mvnw test -Dtest=OpenApiContratoTest -Dopenapi.actualizar=true   # regenera openapi.yaml
```

### Migraciones (E05)

Los dos changesets (`001-create-perfiles`, `002-completar-perfil`) tienen `<rollback>` explícito;
`perfiles.empleado_id` es `UNIQUE` (un perfil por empleado) y `eventos_procesados.id` es la clave
primaria que deduplica eventos. No hizo falta una migración nueva. `MigracionesTest` lo verifica
sin base de datos y falla si un changeset nuevo no declara `<rollback>`.

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8083` | Puerto interno. No se publica al host |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `database-perfiles` | Base propia `perfiles_db` |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic |
| `BROKER_QUEUE` | `q.perfiles` | Bindings: `empleado.creado`, `empleado.actualizado`, `empleado.retirado` |
| `DB_CONNECTION_TIMEOUT_MS` | `5000` | Espera máxima por una conexión del pool; después la petición responde `503` |
| `HEALTH_CHECK_TIMEOUT_MS` | `2000` | Plazo total de los checks de `/health` (respuesta en < 3 s) |
| `SERVICE_VERSION` | opcional | `version` de `/health`; por defecto la del pom |

El esquema lo crea Liquibase al arrancar (`001-create-perfiles` y `002-completar-perfil`), cada uno con su rollback (ver Migraciones).

## Pruebas

```bash
./mvnw test      # unitarias rápidas
./mvnw verify    # unitarias + JaCoCo + umbral de cobertura
```

| Clase | Qué prueba |
|---|---|
| `PerfilEventoServiceTest` | Efecto de cada evento y deduplicación |
| `HealthControllerTest` | Health UP, base o broker caídos, y dependencias colgadas: `503` en < 3 s |
| `contrato/ContratoHttpTest` | Slice `@WebMvcTest`: errores C3 en 400/404/405/500/503, `traceId`, health 200/503 |
| `contrato/OpenApiContratoTest` | Drift de `openapi.yaml`, rutas documentadas, `bearerAuth` y códigos de error |
| `contrato/MigracionesTest` | `<rollback>` en cada changeset y restricciones de unicidad |
| `exception/GlobalExceptionHandlerTest` | Formato C3, `traceId` desde el MDC y ausencia de detalles internos |

`verify` falla si la cobertura de líneas de la lógica ejecutable baja de 70 %. Genera JUnit XML
en `target/surefire-reports/` y JaCoCo XML/HTML en `target/site/jacoco/`. Se excluyen la clase de
arranque, DTO, entidades JPA, interfaces de repositorio y configuración declarativa; no contienen
reglas de negocio. Medición del 2026-11-11: 54 pruebas, 80,78 % de líneas y 57,99 % de ramas.

## Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `build` | `eclipse-temurin:21.0.9_10-jdk-alpine` | Maven Wrapper, `dependency:go-offline` y `package -DskipTests` |
| `runtime` | `eclipse-temurin:21.0.9_10-jre-alpine` | JRE, `curl`, `app.jar`, agente OpenTelemetry `2.33.1` y `docker-entrypoint.sh` |

- **Usuario:** `app` de sistema con UID/GID fijos `10001:10001`, sin home ni shell de login
  (`USER 10001:10001`). `app.jar`, el agente y el entrypoint quedan con dueño `root`, de solo
  lectura (el entrypoint, 0755). Tomcat y la JVM solo escriben en `/tmp` (1777 en la imagen
  base). La foto es una URL (`foto_url`) guardada en PostgreSQL; no hay subida de archivos a disco.
- **Secretos:** `docker-entrypoint.sh` lee `DB_PASSWORD_FILE` y `RABBITMQ_PASSWORD_FILE` y falla
  con mensaje claro si no puede leerlos. Como el proceso ya no es root, `scripts/setup-secrets.*`
  deja los archivos 0644 dentro de `secrets/` 0700 (ver
  `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Por qué multi-stage y JRE:** el JDK, Maven, `~/.m2` y el código fuente se quedan en `build`;
  el JRE basta para ejecutar el fat jar y pesa bastante menos que el JDK.
- **Por qué no root:** el consumidor de eventos no puede reescribir su jar, el agente ni instalar
  paquetes.
- **Base fijada:** versión exacta de Temurin (`21.0.9_10`) en ambas etapas y del agente
  OpenTelemetry (`ARG OTEL_JAVA_AGENT_VERSION=2.33.1`).
- `curl` se mantiene porque el healthcheck de Compose ejecuta `curl -f http://localhost:8083/health`.
- `.dockerignore` deja fuera `target`, `src/test`, IDE, `.env` y `secrets`.
