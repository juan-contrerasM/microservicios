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

Estados previstos: `ACTIVO`, `EN_VACACIONES`, `RETIRADO`. En este reto solo se maneja `ACTIVO`.

## Endpoints

| Método | Ruta | Descripción | Código |
|--------|------|-------------|--------|
| `POST` | `/empleados` | Registrar empleado y validar su departamento | `201` / `400` / `503` |
| `GET` | `/empleados/{id}` | Consultar por id | `200` / `404` |
| `GET` | `/health` | Verificar la conexión con PostgreSQL | `200` / `503` |
| Otros | cualquier ruta o método no definido | Recurso no encontrado | `404` |

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

Springdoc OpenAPI documenta los endpoints con el contrato de la Etapa 3: `POST /empleados`
(`201` / `400` / `503`), `GET /empleados/{id}` (`200` / `404`) y `GET /health` (`200` / `503`).

| Recurso | URL (con Compose en la raíz) |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Spec JSON | http://localhost:8080/v3/api-docs |

En el monorepo el arranque correcto es el `docker-compose.yml` de la **raíz**, no el de esta carpeta (ese quedó del Reto 1).

## Ejecutar con Docker Compose (recomendado)

Levanta la API y PostgreSQL juntos:

```bash
docker compose up --build
```

La API queda en: `http://localhost:8080`

Detener:

```bash
docker compose down
```

### Comandos del reto (imagen de la app)

```bash
docker build -t servidor-empleados .
docker run -p 8080:8080 -e DB_HOST=host.docker.internal -e DB_PORT=5433 -e DB_NAME=hr_management -e DB_USER=empleados -e DB_PASSWORD=empleados servidor-empleados
```

> Nota: con `docker run` necesitas PostgreSQL corriendo en tu máquina (por ejemplo con `docker compose up db`). Con `docker compose up --build` no hace falta nada extra.

> Para probar el registro integrado con departamentos, utiliza el `docker-compose.yml` de la raíz del monorepo. El Compose local de esta carpeta solo levanta empleados y PostgreSQL, por lo que requiere que `departamentos-service` esté disponible externamente.

## Ejecutar en local

1. Arranca solo la base de datos:

```bash
docker compose up db -d
```

2. Ejecuta la aplicación:

```bash
./mvnw spring-boot:run
```

En Windows:

```bash
.\mvnw.cmd spring-boot:run
```

En contenedor:

```bash
docker compose up --build
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
6. El `departamentoId` debe existir en `departamentos-service`.
7. Si departamentos no está disponible, se realizan hasta cuatro intentos con backoff de 1, 2 y 4 segundos.
8. El estado se establece siempre como `ACTIVO`, independientemente del valor recibido.

### Códigos de respuesta

| Código | Situación |
|--------|-----------|
| `200 OK` | El empleado fue consultado correctamente o el health check está `UP` |
| `201 Created` | El empleado fue registrado correctamente |
| `400 Bad Request` | Faltan datos, un campo es inválido, hay un duplicado o el departamento no existe |
| `404 Not Found` | El empleado, la ruta o el método solicitado no existe |
| `503 Service Unavailable` | PostgreSQL o `departamentos-service` no está disponible |

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
| `DEPARTAMENTOS_SERVICE_TIMEOUT` | `3s` | `3s` | Timeout por intento HTTP |
| `DEPARTAMENTOS_SERVICE_MAX_ATTEMPTS` | `4` | `4` | Intento inicial más tres reintentos |
| `DEPARTAMENTOS_SERVICE_INITIAL_BACKOFF` | `1s` | `1s` | Espera inicial; se duplica en cada reintento |

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

Antes de guardar, el servicio consulta `GET /departamentos/{departamentoId}`. Un `404` se traduce inmediatamente a `400` sin reintentos. Los errores de red, timeouts y respuestas `5xx` se reintentan con backoff exponencial. Si se agotan los intentos, el registro se rechaza con `503`; no se guarda un empleado pendiente de validación porque ese estado no forma parte del modelo del Reto 2.

### Ejecutar las pruebas automatizadas

La suite cubre:

- Registro y consulta desde el controlador.
- Asignación automática del estado `ACTIVO`.
- Conflictos por `id`, `email` y `numeroEmpleado` duplicados.
- Validación del departamento y política de reintentos.
- Health check real contra PostgreSQL.
- Traducción de indisponibilidad a `503`.
- Consulta de empleados inexistentes.
- Validaciones de todos los campos del modelo.
- Manejo global de respuestas `400`, `404` y `503`.
- Carga del contexto de Spring Boot.

Como la prueba de contexto inicializa JPA, primero debe estar disponible PostgreSQL:

```bash
docker compose up db -d
```

En Windows:

```bash
.\mvnw.cmd test
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

El archivo `Reto1.postman_collection.json`, ubicado en la raíz del proyecto, puede importarse directamente en Postman. La colección incluye solicitudes preparadas para registrar empleados y consultar los resultados de la API.

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
