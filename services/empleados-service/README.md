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
  "estado": "ACTIVO"
}
```

Estados: `ACTIVO`, `PENDIENTE_VALIDACION` (fallback del Circuit Breaker, Reto 3), `EN_VACACIONES`,
`RETIRADO`. `EN_VACACIONES` y `RETIRADO` siguen sin usarse (Reto 4/5).

## Endpoints

| Método | Ruta | Descripción | Código |
|--------|------|-------------|--------|
| `POST` | `/empleados` | Registrar empleado y validar su departamento (vía Circuit Breaker) | `201` / `400` |
| `GET` | `/empleados/{id}` | Consultar por id | `200` / `404` |
| `GET` | `/empleados` | Listar todos los empleados registrados | `200` |
| `GET` | `/empleados/circuit-breaker` | Estado del Circuit Breaker (`CLOSED`/`OPEN`/`HALF_OPEN`) | `200` |
| `POST` | `/empleados/reconciliar` | Revalida contra departamentos a los `PENDIENTE_VALIDACION` | `200` |
| `GET` | `/health` | Verificar la conexión con PostgreSQL | `200` / `503` |
| Otros | cualquier ruta o método no definido | Recurso no encontrado | `404` |

Nota: antes del Circuit Breaker, el registro respondía `503` si departamentos no estaba
disponible tras agotar reintentos. Desde la Etapa 3, ese caso ya no rechaza el alta: persiste el
empleado como `PENDIENTE_VALIDACION` y responde `201` (ver más abajo).

Validaciones al registrar (`400 Bad Request`):

- ID ya registrado
- Email ya registrado
- `numeroEmpleado` ya registrado
- `departamentoId` inexistente en `departamentos-service`

Los errores se responden en JSON, por ejemplo:

```json
{ "mensaje": "Ya existe un empleado registrado con ese email" }
```

Sin incluir el valor del email en el mensaje.

## OpenAPI / Swagger (Etapa 4)

Springdoc OpenAPI documenta los endpoints con el contrato vigente: `POST /empleados`
(`201` / `400`), `GET /empleados/{id}` (`200` / `404`), `GET /empleados` (`200`) y
`GET /health` (`200` / `503`).

| Recurso | URL (con Compose en la raíz) |
|---|---|
| Swagger UI | `/swagger-ui.html` dentro de `empleados-service:8080` |
| Spec JSON | `/v3/api-docs` dentro de `empleados-service:8080` |

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
{ "mensaje": "El empleado con id E999 no existe" }
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
| `400 Bad Request` | Faltan datos, un campo es inválido, hay un duplicado o el departamento no existe |
| `404 Not Found` | El empleado, la ruta o el método solicitado no existe |
| `503 Service Unavailable` | PostgreSQL no está disponible (`departamentos-service` caído ya no produce `503`: ver Circuit Breaker) |

Todas las respuestas de error utilizan el mismo formato:

```json
{
  "mensaje": "Descripción del error"
}
```

Los errores generales mantienen el atributo `mensaje`. Cuando la validación corresponde a campos de la solicitud, la respuesta agrega el objeto `errores`, asociando cada campo con su mensaje:

```json
{
  "mensaje": "La solicitud contiene campos inválidos",
  "errores": {
    "nombre": "El nombre es obligatorio"
  }
}
```

Cuando varios campos son inválidos o están ausentes, la respuesta los informa todos en una sola solicitud:

```json
{
  "mensaje": "La solicitud contiene campos inválidos",
  "errores": {
    "id": "El id es obligatorio",
    "nombre": "El nombre es obligatorio",
    "apellido": "El apellido es obligatorio",
    "email": "El email es obligatorio",
    "numeroEmpleado": "El numeroEmpleado es obligatorio",
    "cargo": "El cargo es obligatorio",
    "area": "El area es obligatoria",
    "departamentoId": "El departamentoId es obligatorio",
    "fechaIngreso": "La fechaIngreso es obligatoria"
  }
}
```

Ejemplo de un cuerpo JSON inválido:

```json
{
  "mensaje": "El cuerpo de la solicitud es inválido o está mal formado"
}
```

Ejemplo de un identificador duplicado:

```json
{
  "mensaje": "Ya existe un empleado con ese id"
}
```

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
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
```

La primera construcción descarga las dependencias del proyecto. Mientras `pom.xml`, Maven Wrapper y la imagen base no cambien, Docker reutiliza esa capa y solo recompila el código modificado. Esto reduce el tiempo de las construcciones posteriores.

La caché no se reutiliza si se ejecuta la construcción con `--no-cache`, se limpia la caché de Docker o cambia alguno de los archivos copiados antes de `dependency:go-offline`.

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

Las credenciales incluidas están pensadas exclusivamente para desarrollo local. En otros ambientes deben proporcionarse mediante variables protegidas o secretos.

### Persistencia y creación del esquema

El esquema se versiona con Liquibase. El changelog maestro se encuentra en `src/main/resources/db/changelog/db.changelog-master.xml` y cada cambio tiene rollback. Hibernate usa `spring.jpa.hibernate.ddl-auto=validate`: verifica que el esquema coincida con la entidad, pero nunca lo crea ni lo modifica.

La migración inicial crea `empleados`, su clave primaria y restricciones `UNIQUE` para `email` y `numero_empleado`. Se mantienen también las consultas previas del servicio para devolver mensajes descriptivos; las restricciones de PostgreSQL son la garantía definitiva frente a condiciones de carrera.

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

### Ejecutar las pruebas automatizadas

La suite cubre:

- Registro y consulta desde el controlador.
- Asignación de `ACTIVO` o `PENDIENTE_VALIDACION` según la disponibilidad del departamento.
- Conflictos por `id`, `email` y `numeroEmpleado` duplicados.
- Validación del departamento y política de reintentos.
- Health check real contra PostgreSQL.
- Estados `CLOSED`, `OPEN`, `HALF_OPEN`, fallback y reconciliación.
- Consulta de empleados inexistentes.
- Validaciones de todos los campos del modelo.
- Manejo global de respuestas `400`, `404` y `503`.
- Carga del contexto de Spring Boot.

Como la prueba de contexto inicializa JPA, debe estar disponible PostgreSQL en el puerto local
por defecto de pruebas (`5433`). Una forma aislada es:

```bash
docker run --rm --name empleados-test-db -d -p 5433:5432 \
  -e POSTGRES_DB=hr_management \
  -e POSTGRES_USER=empleados \
  -e POSTGRES_PASSWORD=empleados postgres:16-alpine
```

En Windows:

```bash
.\mvnw.cmd test
docker stop empleados-test-db
```

En Linux o macOS:

```bash
./mvnw test
```

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
