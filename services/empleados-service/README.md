# Reto 1 — Servicio de Empleados

API REST en Spring Boot para registrar y consultar empleados. Usa **PostgreSQL** y se despliega con **Docker**.

## Requisitos

- Java 21
- Maven (incluido wrapper `mvnw`)
- Docker y Docker Compose
- PostgreSQL (solo si ejecutas la app fuera de Docker Compose)

## Modelo de empleado

```json
{
  "id": "E001",
  "nombre": "Juan",
  "apellido": "Pérez",
  "email": "juan.perez@empresa.com",
  "numeroEmpleado": "EMP-2026-001",
  "cargo": "Desarrollador Senior",
  "area": "Tecnología",
  "departamentoId": "IT",
  "fechaIngreso": "2026-02-10",
  "estado": "ACTIVO",
  "fechaRetiro": null
}
```

Estados: `ACTIVO`, `PENDIENTE_VALIDACION` (fallback del Circuit Breaker, Reto 3), `EN_VACACIONES`
(queda para el Reto 5) y `RETIRADO` (baja lógica del Reto 4). `fechaRetiro` es nulo hasta el
`DELETE`: entonces guarda el instante UTC del retiro y la fila no se borra.

## Endpoints

| Método | Ruta | Descripción | Código |
|--------|------|-------------|--------|
| `POST` | `/empleados` | Registrar empleado y validar su departamento (vía Circuit Breaker). Después del commit publica `empleado.creado` | `201` / `400` / `409` |
| `PUT` | `/empleados/{id}` | Actualización parcial. Si cambia `departamentoId`, lo vuelve a validar. Publica `empleado.actualizado` | `200` / `400` / `404` / `409` |
| `DELETE` | `/empleados/{id}` | Baja lógica: `RETIRADO` + `fechaRetiro`. Publica `empleado.retirado`. Un segundo DELETE no publica | `200` / `400` / `404` |
| `GET` | `/empleados/{id}` | Consultar por id, también si está retirado | `200` / `404` |
| `GET` | `/empleados` | Listar todos. `?estado=RETIRADO` filtra bajas. `desde` y `hasta` acotan la fecha UTC de `fechaRetiro`, ambos inclusive | `200` / `400` |
| `GET` | `/empleados/circuit-breaker` | Estado del Circuit Breaker (`CLOSED`/`OPEN`/`HALF_OPEN`) | `200` |
| `POST` | `/empleados/reconciliar` | Revalida contra departamentos a los `PENDIENTE_VALIDACION` | `200` |
| `GET` | `/health` | Contrato C2: PostgreSQL y RabbitMQ, responde en < 3 s | `200` / `503` |
| Otros | ruta no definida / método no soportado | Recurso no encontrado / método no permitido | `404` / `405` |

Todas las operaciones de `/empleados` exigen `bearerAuth` (lo valida el Gateway, que responde
`401` sin token y `403` si un `USER` intenta escribir) y pueden responder `500` y `503` (PostgreSQL
caído). Todas usan el error uniforme C3 descrito en [Códigos de respuesta](#códigos-de-respuesta).

Nota: antes del Circuit Breaker, el registro respondía `503` si departamentos no estaba
disponible tras agotar reintentos. Desde la Etapa 3, ese caso ya no rechaza el alta: persiste el
empleado como `PENDIENTE_VALIDACION` y responde `201` (ver más abajo).

Validaciones al registrar:

- `400 Bad Request`: campo obligatorio ausente o inválido, JSON mal formado, o `departamentoId`
  inexistente en `departamentos-service` (el enunciado exige `400` en este caso).
- `409 Conflict` (Fase 5, contrato C3): ID, email o `numeroEmpleado` ya registrados. Antes era
  `400`; se separó para que el cliente distinga "datos mal enviados" de "choca con lo existente".

Los errores se responden con el formato uniforme C3, por ejemplo:

```json
{ "timestamp": "2026-11-09T12:00:00Z", "status": 409, "error": "Conflict",
  "message": "Ya existe un empleado registrado con ese email", "path": "/empleados",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

Sin incluir el valor del email en el mensaje.

El `PUT` repite esas unicidades (`409`) y, si el `departamentoId` cambia, la misma validación contra
departamentos (`400`). No sirve para pasar a `RETIRADO`. Un `DELETE` de alguien que ya está retirado
responde `400` con `message` = `El empleado con id {id} ya está retirado`.

## Eventos (Reto 4)

Después de confirmar la fila, el servicio publica en el exchange topic `onboarding.eventos`.
La routing key es el `type`. El commit va primero: si RabbitMQ no responde, la fila queda y el
error queda en el log. No se revierte el alta, la actualización ni el retiro.

| Hecho | Evento | Cuándo |
|---|---|---|
| `POST` 201, sea `ACTIVO` o `PENDIENTE_VALIDACION` | `empleado.creado` | Después del commit |
| `PUT` 200 | `empleado.actualizado` | Después del commit. El catálogo solo lleva `empleadoId`, `nombre`, `apellido`, `email`, `cargo`, `area` y `departamentoId` |
| `DELETE` 200 la primera vez | `empleado.retirado` | Después del commit. Lleva `empleadoId`, `email`, `fechaRetiro` y `motivo` (`RENUNCIA` si el cuerpo no lo envía) |
| `DELETE` de un retirado | ninguno | Responde 400 antes de publicar |

El envelope es `id` (UUID), `type`, `version` 1, `occurredAt` (UTC), `producer`
`empleados-service` y `data`. Al arrancar declara el exchange, de forma idempotente. Las colas
las crean los consumidores.

**Productor E11 (Proyecto final, Fase 4).** La publicación se registra con
`TransactionSynchronization.afterCommit()` desde `EmpleadoService` (equivalente a
`@TransactionalEventListener(phase = AFTER_COMMIT)`): un rollback nunca anuncia una fila que no
existe. Por qué no dentro de la transacción: si el broker fallara a mitad, revertir el alta por un
problema de mensajería dejaría a RRHH sin el empleado. El costo conocido es el doble write: si el
proceso muere entre el commit y el envío, el evento se pierde (lo resolvería un outbox). Cada
mensaje lleva `messageId` = `id` del envelope y modo persistente; `traceparent` lo inyecta el
agente OpenTelemetry en los headers AMQP. Los consumidores, colas, reintento y DLQ están en
[`docs/eventos.md`](../../docs/eventos.md).

## OpenAPI / Swagger (Etapa 4, completado en la Fase 5)

Springdoc OpenAPI documenta las ocho operaciones con schemas de entrada y salida, ejemplos y todos
los códigos que realmente pueden devolver: los propios del controlador (`400`, `404`, `409`) y los
comunes que añade `OpenApiConfig.respuestasComunesDeError()` a cada operación de negocio: `401`
(Gateway, sin token o token inválido), `403` (Gateway, solo en escrituras: un `USER` solo lee),
`500` y `503` (PostgreSQL caído; no aplica a `GET /empleados/circuit-breaker`, que no toca la base).
El esquema de seguridad es `bearerAuth` (HTTP bearer, JWT) y se aplica a todas las operaciones de
`/empleados`; `GET /health` es público.

| Recurso | URL (con Compose en la raíz) |
|---|---|
| Swagger UI | `/swagger-ui.html` dentro de `empleados-service:8080` |
| Spec JSON | `/v3/api-docs` dentro de `empleados-service:8080` |
| Spec YAML | `/v3/api-docs.yaml` dentro de `empleados-service:8080` |
| Spec versionada | [`openapi.yaml`](openapi.yaml) en esta carpeta |

`openapi.yaml` se genera desde Springdoc, no se edita a mano. `OpenApiContratoTest` (slice
`@WebMvcTest`, sin base ni Docker) pide `/v3/api-docs.yaml`, falla si difiere del archivo
versionado (drift), verifica que cada ruta registrada en Spring MVC esté documentada, que las
operaciones de negocio exijan `bearerAuth` con `401`/`403`/`500` y que todos los errores apunten al
esquema `ApiError`. Tras cambiar un endpoint:

```bash
./mvnw test -Dtest=OpenApiContratoTest -Dopenapi.actualizar=true   # regenera openapi.yaml
```

Por qué así: el YAML se deriva del código (una sola fuente de verdad) y la prueba convierte un
olvido de regenerarlo en un build rojo, sin añadir un plugin que arranque la aplicación con base
de datos. `springdoc.writer-with-order-by-keys=true` hace la salida determinista y el servidor es
relativo (`/`), así el archivo no depende del host. `info.version` sale de la versión del pom.

En el monorepo el arranque correcto es el `docker-compose.yml` de la **raíz**. Swagger queda
interno porque el único puerto público es el Gateway; el contrato público se prueba con la
colección del Reto 3.

## Ejecutar con Docker Compose (recomendado)

Desde la raíz del monorepo levanta el Gateway, ambas APIs y ambas bases:

```bash
cd ../..
cp .env.example .env
docker compose up --build -d
```

La API se consume a través del Gateway: `http://localhost:8080/empleados`.

Detener:

```bash
docker compose down
```

### Comandos del reto (imagen de la app)

```bash
docker build -t servidor-empleados .
docker run -p 8080:8080 -e DB_HOST=host.docker.internal -e DB_PORT=5433 -e DB_NAME=hr_management -e DB_USER=empleados -e DB_PASSWORD=empleados servidor-empleados
```

> Nota: con `docker run` necesitas PostgreSQL en `localhost:5433` y departamentos en
> `localhost:8081`. Con el Compose raíz no hace falta preparar dependencias manualmente.

> Para probar el registro integrado utiliza siempre el `docker-compose.yml` de la raíz. Este
> servicio ya no mantiene un Compose aislado porque el contrato del Reto 3 exige un único puerto
> público en el Gateway.

## Ejecutar en local

1. Arranca una base de datos de desarrollo en el puerto esperado:

```bash
docker run --rm --name empleados-dev-db -d -p 5433:5432 \
  -e POSTGRES_DB=hr_management \
  -e POSTGRES_USER=empleados \
  -e POSTGRES_PASSWORD=empleados postgres:16-alpine
```

2. Asegúrate de tener departamentos disponible en `http://localhost:8081` y ejecuta la aplicación:

```bash
./mvnw spring-boot:run
```

En Windows:

```bash
.\mvnw.cmd spring-boot:run
```

Al terminar:

```bash
docker stop empleados-dev-db
```

Configuración por defecto:

| Variable | Valor |
|----------|-------|
| Host | `localhost` |
| Puerto | `5433` (contenedor Docker; evita choque con Postgres local) |
| Base de datos | `hr_management` |
| Usuario | `empleados` |
| Contraseña | `empleados` |

## Pruebas con curl / Postman / Bruno

### Registrar empleado

```bash
curl -X POST http://localhost:8080/empleados ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":\"E001\",\"nombre\":\"Juan\",\"apellido\":\"Pérez\",\"email\":\"juan.perez@empresa.com\",\"numeroEmpleado\":\"EMP-2026-001\",\"cargo\":\"Desarrollador Senior\",\"area\":\"Tecnología\",\"departamentoId\":\"IT\",\"fechaIngreso\":\"2026-02-10\",\"estado\":\"ACTIVO\"}"
```

### Consultar empleado

```bash
curl http://localhost:8080/empleados/E001
```

### Empleado inexistente

```bash
curl http://localhost:8080/empleados/E999
```

Respuesta esperada: `404`

```json
{ "timestamp": "2026-11-09T12:00:00Z", "status": 404, "error": "Not Found",
  "message": "El empleado con id E999 no existe", "path": "/empleados/E999",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

## Estructura del proyecto

```
src/main/java/com/microservicios/Reto1/
├── controller/     # Endpoints HTTP
├── service/        # Reglas de negocio
├── repository/     # Acceso a PostgreSQL (JPA)
├── model/          # Entidad Empleado y estados
└── exception/      # Manejo de 400 / 404
```

## Tecnologías

- Java 21
- Spring Boot 4
- Spring Web / Spring Data JPA / Validation
- PostgreSQL 16
- Docker / Docker Compose

---

## Documentación adicional

### Objetivo del servicio

Este microservicio centraliza el registro y la consulta de la información básica de los empleados. La lógica de negocio garantiza que no existan registros duplicados por identificador, correo electrónico o número de empleado y que todo nuevo registro quede en estado `ACTIVO`.

### Arquitectura por capas

```text
Cliente HTTP
    │
    ▼
EmpleadoController       Recibe solicitudes y valida el cuerpo JSON
    │
    ▼
EmpleadoService          Aplica las reglas de negocio
    │
    ├──────────► DepartamentoClient ──HTTP──► departamentos-service
    │
    ▼
EmpleadoRepository       Gestiona la persistencia mediante JPA
    │
    ▼
PostgreSQL               Almacena la tabla empleados
```

| Capa | Responsabilidad |
|------|-----------------|
| `controller` | Expone los endpoints REST y delega las operaciones |
| `service` | Verifica duplicados, asigna el estado y consulta empleados |
| `client` | Valida el departamento por HTTP con timeout y reintentos |
| `repository` | Proporciona las operaciones de persistencia con Spring Data JPA |
| `model` | Define la entidad `Empleado` y sus estados posibles |
| `exception` | Convierte excepciones en respuestas HTTP homogéneas |
| `dto` | Define la estructura JSON utilizada para los errores |

### Detalle de los campos

| Campo | Tipo | Obligatorio | Descripción y reglas |
|-------|------|:-----------:|---------------------|
| `id` | `String` | Sí | Identificador único enviado por el cliente; no se autogenera |
| `nombre` | `String` | Sí | Nombre del empleado; no puede estar vacío |
| `apellido` | `String` | Sí | Apellido del empleado; no puede estar vacío |
| `email` | `String` | Sí | Debe tener formato de correo electrónico y ser único |
| `numeroEmpleado` | `String` | Sí | Código corporativo único; no se autogenera |
| `cargo` | `String` | Sí | Cargo actual del empleado |
| `area` | `String` | Sí | Área a la que pertenece |
| `departamentoId` | `String` | Sí | Identificador del departamento asociado |
| `fechaIngreso` | `LocalDate` | Sí | Fecha en formato ISO `AAAA-MM-DD` |
| `estado` | `EstadoEmpleado` | No | El servicio lo establece como `ACTIVO` durante el registro |

### Reglas de negocio

Al registrar un empleado se aplican las siguientes reglas:

1. Todos los campos obligatorios deben contener un valor válido.
2. El `email` debe tener un formato válido.
3. No puede existir otro empleado con el mismo `id`.
4. No puede existir otro empleado con el mismo `email`.
5. No puede existir otro empleado con el mismo `numeroEmpleado`.
6. El `departamentoId` debe existir en `departamentos-service` (validado a través del Circuit
   Breaker; un `404` de departamentos rechaza el alta con `400`).
7. Si departamentos no está disponible, se realizan hasta cuatro intentos con backoff de 1, 2 y
   4 segundos (mientras el circuito esté `CLOSED`).
8. El estado queda `ACTIVO` si departamentos confirmó el departamento; `PENDIENTE_VALIDACION` si
   el circuito está `OPEN` o la llamada agotó los reintentos (Reto 3, ver más abajo).

### Códigos de respuesta

| Código | Situación |
|--------|-----------|
| `200 OK` | El empleado fue consultado correctamente, el health check está `UP`, o se consultó el circuito/reconciliación |
| `201 Created` | El empleado fue registrado, en `ACTIVO` o en `PENDIENTE_VALIDACION` si departamentos no respondió |
| `400 Bad Request` | Faltan datos, un campo es inválido, el JSON está mal formado o el departamento no existe |
| `401 Unauthorized` | Sin token o token inválido (lo responde el API Gateway) |
| `403 Forbidden` | Un `USER` intenta crear, modificar, retirar o reconciliar (lo responde el API Gateway) |
| `404 Not Found` | El empleado o la ruta solicitada no existe |
| `405 Method Not Allowed` | La ruta existe pero no admite ese método |
| `409 Conflict` | ID, email o `numeroEmpleado` ya registrados (también si la restricción `UNIQUE` lo detecta en una carrera) |
| `500 Internal Server Error` | Error inesperado; el detalle solo queda en el log, con stack trace |
| `503 Service Unavailable` | PostgreSQL no está disponible (`departamentos-service` caído ya no produce `503`: ver Circuit Breaker) |

#### Formato de error uniforme (contrato C3, Fase 5)

Todas las respuestas de error usan el mismo cuerpo que los otros seis servicios
(`GlobalExceptionHandler` + `dto/ApiError`):

```json
{
  "timestamp": "2026-11-09T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "La solicitud contiene campos inválidos: nombre: El nombre es obligatorio; email: El email no tiene un formato válido",
  "path": "/empleados",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

- `message` nunca lleva trazas de pila, SQL, nombres de restricciones ni hosts. Un error
  inesperado responde `Error interno del servidor` y el detalle va al log.
- Los errores de validación de campos van todos en `message`, separados por `;`, en lugar del
  antiguo objeto `errores`: así el cuerpo no se sale de los seis campos del contrato.
- `traceId` sale del MDC que llena el agente Java de OpenTelemetry (`trace_id`); sin agente se
  toma del header `traceparent` que propaga el Gateway. Si no hay contexto de traza, se omite.
- Una caída de PostgreSQL durante una operación (`CannotCreateTransactionException`,
  `DataAccessResourceFailureException`) responde `503`, no `500`. Hikari espera como máximo
  `DB_CONNECTION_TIMEOUT_MS` (5 s por defecto, antes 30 s) por una conexión del pool.

Este formato reemplaza al anterior `{"mensaje": "..."}` (Retos 1 a 8).

#### Health check (contrato C2, Fase 5)

```json
{ "status": "UP", "service": "empleados-service", "version": "0.0.1-SNAPSHOT",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

`200` si PostgreSQL y RabbitMQ están `UP`; `503` si alguno está `DOWN`. Hallazgo de la Fase 1: con
PostgreSQL detenido el endpoint no respondía en 15 s, porque `dataSource.getConnection()` esperaba
a Hikari (hasta 30 s). Ahora cada check corre en un hilo de un pool acotado y el controlador espera
como máximo `HEALTH_CHECK_TIMEOUT_MS` (2 s) en total; lo que no conteste a tiempo cuenta como `DOWN`.
Así `/health` responde en menos de 3 s aunque la dependencia cuelgue, y el healthcheck de Compose
no se queda esperando. `version` es la del pom (filtrada por Maven) y se puede sobrescribir con
`SERVICE_VERSION` (por ejemplo, el tag de la imagen).

Pruebas sin Docker: `HealthControllerTest` (UP, rechazo, conexión inválida, PostgreSQL colgado,
ambas dependencias colgadas, RabbitMQ caído; las colgadas verifican `503` en < 3 s) y
`ContratoHttpTest` (slice `@WebMvcTest` que recorre el stack MVC: errores C3 en 400/404/405/409/
500/503, `traceId` y health 200/503).

### Respuesta de registro exitoso

El endpoint de registro responde con el empleado persistido y el estado asignado por el servicio:

```json
{
  "id": "E001",
  "nombre": "Juan",
  "apellido": "Pérez",
  "email": "juan.perez@empresa.com",
  "numeroEmpleado": "EMP-2026-001",
  "cargo": "Desarrollador Senior",
  "area": "Tecnología",
  "departamentoId": "IT",
  "fechaIngreso": "2026-02-10",
  "estado": "ACTIVO"
}
```

### Servicios de Docker Compose

| Servicio | Imagen | Puerto en el equipo | Función |
|----------|--------|----------------------|---------|
| `app` | `servidor-empleados` | `8080` | Ejecuta la API REST |
| `db` | `postgres:16-alpine` | `5433` | Ejecuta PostgreSQL |

PostgreSQL usa el volumen `pgdata`, por lo que los registros permanecen disponibles aunque se detengan o vuelvan a crear los contenedores.

Comandos útiles:

```bash
# Levantar en segundo plano
docker compose up --build -d

# Consultar el estado
docker compose ps

# Ver los logs de la aplicación
docker compose logs -f app

# Ver los logs de PostgreSQL
docker compose logs -f db

# Detener conservando los datos
docker compose down
```

Para eliminar también la información persistida:

```bash
docker compose down -v
```

> Advertencia: el parámetro `-v` elimina el volumen de PostgreSQL y los empleados almacenados.

### Caché de dependencias Maven en Docker

El `Dockerfile` descarga las dependencias antes de copiar el código fuente:

```dockerfile
COPY pom.xml .
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -q -B dependency:go-offline
COPY src src
```

La primera construcción descarga las dependencias del proyecto. Mientras `pom.xml`, Maven Wrapper y la imagen base no cambien, Docker reutiliza esa capa y solo recompila el código modificado. Esto reduce el tiempo de las construcciones posteriores.

La caché no se reutiliza si se ejecuta la construcción con `--no-cache`, se limpia la caché de Docker o cambia alguno de los archivos copiados antes de `dependency:go-offline`.

### Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `build` | `eclipse-temurin:21.0.9_10-jdk-alpine` | Maven Wrapper, `dependency:go-offline` y `package -DskipTests` |
| `runtime` | `eclipse-temurin:21.0.9_10-jre-alpine` | JRE, `curl`, `app.jar`, agente OpenTelemetry `2.33.1` y `docker-entrypoint.sh` |

- **Usuario:** `app` de sistema con UID/GID fijos `10001:10001`, sin home ni shell de login
  (`USER 10001:10001`). `app.jar`, el agente y el entrypoint quedan con dueño `root`, de solo
  lectura (el entrypoint, 0755). Tomcat y la JVM solo escriben en `/tmp`, que en la imagen base
  es 1777; el servicio no guarda archivos propios.
- **Secretos:** `docker-entrypoint.sh` sigue leyendo `DB_PASSWORD_FILE` y
  `RABBITMQ_PASSWORD_FILE` y falla con mensaje claro si no puede leerlos. Como el proceso ya no es
  root, los archivos de `secrets/` deben ser legibles por el UID 10001: `scripts/setup-secrets.*`
  los deja 0644 dentro de un directorio 0700 (ver `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Por qué multi-stage y JRE:** el JDK, Maven, `~/.m2` y el código fuente se quedan en `build`;
  el JRE basta para ejecutar el fat jar y pesa bastante menos que el JDK.
- **Por qué no root:** el proceso no puede reescribir su jar, el agente ni instalar paquetes.
- **Base fijada:** versión exacta de Temurin (`21.0.9_10`) en ambas etapas y del agente
  OpenTelemetry (`ARG OTEL_JAVA_AGENT_VERSION=2.33.1`).
- `curl` se mantiene porque el healthcheck de Compose ejecuta `curl -f http://localhost:8080/health`.
- `.dockerignore` deja fuera `target`, `src/test`, IDE, `.env` y `secrets`. Las pruebas corren en
  Jenkins con `mvn verify`, no dentro del build de la imagen.

### Variables de entorno

| Variable | Valor local | Valor en Compose | Descripción |
|----------|-------------|------------------|-------------|
| `DB_HOST` | `localhost` | `db` | Host de PostgreSQL |
| `DB_PORT` | `5433` | `5432` | Puerto de PostgreSQL |
| `DB_NAME` | `hr_management` | `hr_management` | Base de datos |
| `DB_USER` | `empleados` | `empleados` | Usuario |
| `DB_PASSWORD` | `empleados` | `empleados` | Contraseña |
| `DEPARTAMENTOS_SERVICE_URL` | `http://localhost:8081` | `http://departamentos-service:8081` | URL base del servicio de departamentos |
| `DEPARTAMENTOS_SERVICE_TIMEOUT` | `5s` | `5s` | Timeout por intento HTTP |
| `DEPARTAMENTOS_SERVICE_MAX_ATTEMPTS` | `4` | `4` | Intento inicial más tres reintentos |
| `DEPARTAMENTOS_SERVICE_INITIAL_BACKOFF` | `1s` | `1s` | Espera inicial; se duplica en cada reintento |
| `CB_SLIDING_WINDOW_SIZE` | `3` | `3` | Llamadas en la ventana del Circuit Breaker |
| `CB_MINIMUM_NUMBER_OF_CALLS` | `3` | `3` | Llamadas mínimas antes de evaluar el umbral |
| `CB_FAILURE_RATE_THRESHOLD` | `100` | `100` | % de fallos en la ventana que abre el circuito |
| `CB_WAIT_DURATION_OPEN` | `30s` | `30s` | Tiempo en `OPEN` antes de pasar a `HALF_OPEN` |
| `CB_PERMITTED_CALLS_HALF_OPEN` | `1` | `1` | Llamadas de prueba permitidas en `HALF_OPEN` |
| `BROKER_URL` | `amqp://localhost:5672` | `amqp://message-broker:5672` | AMQP del broker, sin credenciales en la URL |
| `RABBITMQ_USER` | `onboarding` | `onboarding` | Usuario del broker |
| `RABBITMQ_PASSWORD` | `onboarding` | `onboarding` | Contraseña del broker |
| `BROKER_EXCHANGE` | `onboarding.eventos` | `onboarding.eventos` | Exchange topic. La routing key es el `type` del evento |
| `DB_CONNECTION_TIMEOUT_MS` | `5000` | `5000` | Espera máxima por una conexión del pool de Hikari; después la petición responde `503` |
| `HEALTH_CHECK_TIMEOUT_MS` | `2000` | `2000` | Plazo total de los checks de `/health` (contrato C2: respuesta en < 3 s) |
| `SERVICE_VERSION` | versión del pom | tag de la imagen (opcional) | `version` que publica `/health`; por defecto la del pom |

Las credenciales incluidas están pensadas exclusivamente para desarrollo local. En otros ambientes deben proporcionarse mediante variables protegidas o secretos.

### Persistencia y creación del esquema

El esquema se versiona con Liquibase. El changelog maestro se encuentra en `src/main/resources/db/changelog/db.changelog-master.xml` y cada cambio tiene rollback. Hibernate usa `spring.jpa.hibernate.ddl-auto=validate`: verifica que el esquema coincida con la entidad, pero nunca lo crea ni lo modifica.

La migración inicial crea `empleados`, su clave primaria y restricciones `UNIQUE` para `email` y `numero_empleado`. Se mantienen también las consultas previas del servicio para devolver mensajes descriptivos; las restricciones de PostgreSQL son la garantía definitiva frente a condiciones de carrera.

Revisión de la Fase 5 (E05): los dos changesets (`001-create-empleados`, `002-add-fecha-retiro`) tienen `<rollback>` explícito y las restricciones `uk_empleados_email` y `uk_empleados_numero_empleado` ya existen, así que no hizo falta una migración nueva. `MigracionesTest` (sin base de datos) recorre los changelogs incluidos en el maestro y falla si un changeset nuevo no declara `<rollback>` o si desaparece alguna de las dos restricciones `UNIQUE`. Una violación de `UNIQUE` que llegue a la base (carrera entre dos altas) responde `409`.

Al migrar desde una instalación anterior creada mediante `ddl-auto=update`, reinicia el volumen local para que Liquibase pueda establecer su historial desde cero:

```bash
docker compose down -v
docker compose up --build
```

### Validación de departamentos y resiliencia

Antes de guardar, el servicio consulta `GET /departamentos/{departamentoId}` a través de un
**Circuit Breaker (Resilience4j)**. Un `404` se traduce inmediatamente a `400` sin reintentos y
**no** cuenta como fallo del circuito (departamentos respondió; es un error de negocio, no de
infraestructura). Los errores de red, timeouts y respuestas `5xx` sí se reintentan con backoff
exponencial (política del Reto 2, sin cambios) y, si se agotan, cuentan como **un** fallo lógico
del circuito.

#### Por qué Circuit Breaker (Reto 3, Etapa 3)

El backoff del Reto 2 protege un fallo corto, pero si `departamentos-service` lleva minutos
caído, cada alta de empleado se queda esperando reintentos condenados: el fallo se propaga en
cascada. El Circuit Breaker corta esa espera: tras unos pocos fallos deja de tocar la red y
responde de inmediato con el fallback, hasta que el proveedor se recupera.

| Parámetro | Valor | Por qué |
|---|---|---|
| Instancia | `departamentos` (única, protege la llamada `empleados → departamentos`) | El PDF exige que el CB viva en el consumidor, no en el proveedor |
| `slidingWindowType` | `COUNT_BASED` | Umbral por número de llamadas, no por tiempo |
| `slidingWindowSize` / `minimumNumberOfCalls` | `3` (`CB_SLIDING_WINDOW_SIZE` / `CB_MINIMUM_NUMBER_OF_CALLS`) | Tres fallos lógicos consecutivos abren el circuito (rango 3–5 del PDF) |
| `failureRateThreshold` | `100` (`CB_FAILURE_RATE_THRESHOLD`) | Con ventana de 3, equivale a exigir que los 3 fallen |
| `waitDurationInOpenState` | `30s` (`CB_WAIT_DURATION_OPEN`) | Tiempo en `OPEN` antes de probar de nuevo |
| `permittedNumberOfCallsInHalfOpenState` | `1` (`CB_PERMITTED_CALLS_HALF_OPEN`) | Una llamada de prueba en `HALF_OPEN`: éxito → `CLOSED`, fallo → `OPEN` |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `true` | Recuperación sin reiniciar el contenedor |
| Timeout de la llamada HTTP | `5s` (`DEPARTAMENTOS_SERVICE_TIMEOUT`) | Subido de 3s a 5s para alinear con el enunciado |
| `recordExceptions` | `ServiceUnavailableException` | Timeout, 5xx, conexión rechazada, reintentos agotados |
| Excepciones no registradas como fallo | `BadRequestException` | Un `404` cuenta como éxito del circuito: el proveedor respondió y el error es de negocio, no de infraestructura |

#### Fallback: disponibilidad sobre consistencia

Cuando el circuito está `OPEN`, o cuando la llamada falla y el circuito pasa a `OPEN`, el
empleado **sí se persiste** con `estado: PENDIENTE_VALIDACION` y la API responde `201` (no
`503`). Se elige disponibilidad — el alta de RRHH no se bloquea porque departamentos esté
caído — sobre consistencia inmediata. **Nunca** se asigna un departamento por defecto.

```json
{
  "id": "E010",
  "estado": "PENDIENTE_VALIDACION",
  "...": "resto de campos canónicos del empleado"
}
```

#### Reconciliación de pendientes

Los `PENDIENTE_VALIDACION` quedan persistidos y son consultables (`GET /empleados/{id}`,
`GET /empleados`). `POST /empleados/reconciliar` es el mecanismo mínimo exigido por el criterio
4: vuelve a llamar a `GET /departamentos/{id}` (a través del mismo Circuit Breaker) para cada
pendiente. Si el departamento existe, el empleado pasa a `ACTIVO`. Si no existe, o si
departamentos sigue sin disponibilidad, se deja `PENDIENTE_VALIDACION` para revisión de RRHH: no
se borra en silencio ni se asigna un departamento por defecto. No hay worker asíncrono (eso es
Reto 4); el barrido se dispara a mano.

#### Consultar el estado del circuito

```bash
curl http://localhost:8080/empleados/circuit-breaker
# { "name": "departamentos", "state": "CLOSED" }
```

### Pruebas (Reto 6)

Las pruebas viven en `src/test/java` y se separan por sufijo, para que «ninguna unitaria
requiere Docker» se pueda comprobar con un comando:

| Tipo | Sufijo | Plugin | Comando | Necesita Docker |
|---|---|---|---|---|
| Unitarias | `*Test.java` | Surefire | `./mvnw test` | No |
| Integración | `*IT.java` | Failsafe | `./mvnw verify` (corre también las unitarias) | Sí (Testcontainers) |

En Windows, `.\mvnw.cmd test` y `.\mvnw.cmd verify`. Ambos comandos devuelven un código
distinto de cero si una prueba falla.

**Unitarias.** Prueban las reglas de `EmpleadoService` con Mockito: `EmpleadoRepository`,
`DepartamentoClient` y `EmpleadoEventPublisher` son dobles, así que no se abre PostgreSQL, no se
llama a departamentos ni se publica nada. Cubren la unicidad de `id`, `email` y
`numeroEmpleado` en el alta y en la edición, que un conflicto no guarde ni publique, la
transición a `RETIRADO` con `fechaRetiro` en UTC, que un segundo retiro dé 400, el filtro
`estado` / `desde` / `hasta` de la auditoría, y el Circuit Breaker con su fallback. Las demás
clases unitarias cubren el controlador, el cliente HTTP con reintentos, el publicador, el
handler global y las validaciones del modelo.

**Integración.** `Reto1ApplicationIT` levanta el contexto completo y `EmpleadoRepositoryIT`
prueba la persistencia. Las dos corren contra `postgres:16-alpine` en Testcontainers
(`PostgresTestcontainersConfig`, conectado con `@ServiceConnection`). Liquibase aplica los
changelogs reales y Hibernate valida el esquema (`ddl-auto=validate`). Verifican que las
restricciones `uk_empleados_email` y `uk_empleados_numero_empleado` existen en el esquema y
tienen el nombre que busca `traducirViolacionDeUnicidad`. También verifican que un retirado
persiste su `fechaRetiro` y aparece en `findByEstado(RETIRADO)`.

**Por qué Testcontainers y no H2 ni una instancia dedicada.** Lo que esta prueba quiere atrapar
depende del motor: los nombres de las restricciones, el tipo `TIMESTAMP` de `fecha_retiro` y los
changelogs de Liquibase. H2 en modo PostgreSQL puede pasar y fallar en producción (por eso la
prueba de contexto anterior apagaba Liquibase y usaba `create-drop`). Una base dedicada en el
Compose obliga a limpiar estado entre corridas. Testcontainers usa el mismo motor que el Compose
y descarta el contenedor al terminar. El costo es más lentitud y depender de Docker. Spring
reutiliza el contexto entre clases IT, así que el contenedor arranca una sola vez. H2 se retiró
del `pom.xml`.

**Sin Docker.** Las clases IT llevan `@Testcontainers(disabledWithoutDocker = true)`. En una
máquina sin Docker, `mvn verify` las marca como *skipped* en lugar de fallar, y las unitarias y
el reporte de cobertura se generan igual. En Windows con Docker solo dentro de WSL, se corre
desde WSL:

```bash
cd /mnt/c/.../services/empleados-service && ./mvnw -B clean verify
```

**Reportes** (contrato con el pipeline del Reto 7):

| Reporte | Ruta |
|---|---|
| Resultados de unitarias (JUnit XML) | `target/surefire-reports/TEST-*.xml` |
| Resultados de integración (JUnit XML) | `target/failsafe-reports/TEST-*.xml` |
| Cobertura (JaCoCo XML, unitarias + integración) | `target/site/jacoco/jacoco.xml` |
| Cobertura navegable | `target/site/jacoco/index.html` |

JaCoCo instrumenta las dos fases (`jacoco.exec` y `jacoco-it.exec`), las une en
`jacoco-merged.exec` y genera el reporte en `verify`. `mvn test` deja `target/jacoco.exec`,
pero no genera el XML.

**Qué no se mide y por qué.** Están excluidos de la cobertura los DTOs (`dto/**`), las cargas
de evento (`EventEnvelope`, `Empleado*Data`), la entidad JPA `Empleado` (getters y setters),
`config/**` y `Reto1Application`. Probar getters infla el porcentaje sin proteger ninguna regla.
Las validaciones de la entidad se prueban igual en `EmpleadoValidationTest`. Las excepciones de
dominio sí cuentan, porque las ejercita `GlobalExceptionHandlerTest`.

**Que las pruebas fallen cuando deben.** Si se quita la comprobación de `existsByEmail` en
`registrar`, `./mvnw test` falla en
`EmpleadoServiceTest.registrarConEmailExistenteLanzaConflictException` con
`Expecting code to raise a throwable`. La cobertura sola no lo garantiza: cada prueba comprueba
la excepción, el mensaje y que no se guardó ni se publicó.

Para compilar el archivo JAR sin ejecutar las pruebas:

```bash
./mvnw -DskipTests package
```

El artefacto generado queda disponible en el directorio `target/`.

### Colección de Postman

La colección vigente es
[`docs/reto3/Reto3.postman_collection.json`](../../docs/reto3/Reto3.postman_collection.json).
Usa exclusivamente `http://localhost:8080` y cubre flujo sano, Gateway `503`, Circuit Breaker,
recuperación y reconciliación. Ver también las
[`evidencias del Reto 3`](../../docs/reto3/EVIDENCIAS.md).

### Solución de problemas

#### La aplicación no se conecta a PostgreSQL

Comprueba que la base de datos esté saludable y consulta sus logs:

```bash
docker compose ps
docker compose logs db
```

Si Spring Boot se ejecuta directamente en el equipo, la conexión predeterminada es `localhost:5433`. Dentro de Docker Compose, la aplicación se conecta a `db:5432`.

#### El puerto 8080 o 5433 está ocupado

Revisa los contenedores que están ejecutándose:

```bash
docker ps
docker compose ps
```

Detén la instancia anterior con `docker compose down` o cambia el puerto correspondiente en `docker-compose.yml`.

#### Reiniciar la base de datos desde cero

```bash
docker compose down -v
docker compose up --build
```

Este procedimiento elimina de forma permanente los registros almacenados en el volumen del proyecto.

#### Verificar el uso de la caché de Docker

```bash
docker compose build --progress=plain
```

La instrucción `dependency:go-offline` debe aparecer como `CACHED` cuando la capa puede reutilizarse.
