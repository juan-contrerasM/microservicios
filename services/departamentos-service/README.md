# departamentos-service

## Ficha del servicio (plantilla C6)

1. **Propósito:** catálogo de departamentos (CRUD). Lo consulta Empleados para validar el alta.
2. **Tecnología:** Go 1.25 + chi + MySQL 8.4. Go aporta un binario pequeño y rápido para un servicio
   de lectura intensiva; MySQL demuestra un motor de base distinto al resto.
3. **Endpoints** (Swagger: `/swagger/index.html`, contrato: [`openapi.yaml`](openapi.yaml)):

   | Método y ruta | Descripción |
   |---|---|
   | `POST /departamentos` | Alta (409 si el id existe) |
   | `GET /departamentos` · `GET /departamentos/{id}` | Listado y consulta |
   | `PUT /departamentos/{id}` · `DELETE /departamentos/{id}` | Actualización y borrado (204) |
   | `GET /health` · `GET /metrics` | Salud C2 y métricas |
4. **Eventos:** no publica ni consume (`N/A`).
5. **Configuración:** `PORT`, `DB_HOST/PORT/NAME/USER`, `DB_PASSWORD_FILE`, `OTEL_*`,
   `CHAOS_LATENCY_MS` (latencia artificial para pruebas de caos; 0 = desactivada).
6. **Migraciones:** golang-migrate (`db/migrations/NNNN_*.up.sql` / `.down.sql`) al arrancar;
   `PRIMARY KEY` como garantía de unicidad.
7. **Pruebas:** `sh scripts/test.sh` (gotestsum): JUnit en `reports/junit.xml`, `coverage.out`,
   umbral 70 % (`COBERTURA_MINIMA`). Cobertura actual 74,2 %, sin excluir paquetes. Integración:
   `internal/integration` (build tag `integration`) contra MySQL real.
8. **Observabilidad:** `client_golang` (`/metrics`), `slog` JSON con el contrato C5, OpenTelemetry
   (otelhttp + Zipkin). Negocio: `departamentos_creados_total`, `departamentos_consultas_total`.
9. **Resiliencia:** no llama a otros servicios (`N/A` en E10); `/health` responde 503 en < 3 s si
   MySQL no contesta.
10. **Decisiones:** `chi` por su compatibilidad con `net/http` estándar; la unicidad la garantiza el
    esquema (la comprobación previa solo mejora el mensaje 409).


Servicio de gestión de departamentos — Reto 2. Go + [chi](https://github.com/go-chi/chi) +
MySQL, sin ORM (acceso a datos con `database/sql` directo).

## Por qué Go y por qué MySQL

- **Go**: el enunciado exige que el segundo servicio esté en un lenguaje distinto al de
  `empleados-service` (Java). Usar Go también deja el terreno preparado para retos futuros del
  curso que piden diversidad de al menos 4 lenguajes.
- **MySQL** (en vez de repetir PostgreSQL): persistencia poliglota real — cada servicio con el
  motor que mejor le sirve, en vez de compartir uno "porque ya lo tenemos". El costo: el equipo
  debe operar y respaldar dos motores distintos en vez de uno; se acepta ese costo porque cada
  base de datos vive dentro de su propio servicio y nadie fuera de `departamentos-service` la
  toca directamente.

## Endpoints

Con el Compose del monorepo, todas las rutas públicas se consumen mediante el Gateway en
`http://localhost:8080`; el puerto propio del servicio solo existe dentro de la red Docker.

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/departamentos` | Registra un departamento | `201` creado · `400` datos inválidos · `409` id duplicado |
| `GET` | `/departamentos/{id}` | Consulta un departamento por id | `200` · `404` no existe |
| `GET` | `/departamentos` | Lista todos los departamentos | `200` |
| `PUT` | `/departamentos/{id}` | Reemplaza `nombre` y `descripcion` (el id de la ruta manda) | `200` · `400` falta `nombre` · `404` |
| `DELETE` | `/departamentos/{id}` | Elimina el departamento | `204` · `404` |
| `GET` | `/health` | Health check C2 (PING a MySQL con plazo de 2 s) | `200` `UP` · `503` `DOWN` |

Todas las operaciones de `/departamentos` exigen `bearerAuth`: el Gateway responde `401` sin
token o con token inválido y `403` si un `USER` intenta `POST`, `PUT` o `DELETE` (un `USER` solo
lee). Todas pueden responder `500` (inesperado) y `503` (MySQL caído). Una ruta inexistente
responde `404` y un método no soportado `405`, también con el formato C3.

Modelo:

```json
{ "id": "IT", "nombre": "Tecnología", "descripcion": "Departamento de TI" }
```

## Contrato HTTP (Fase 5)

### Error uniforme (contrato C3)

Todos los errores, incluidos los de rutas inexistentes (`404`), métodos no soportados (`405`) y
panics (`500`), usan el mismo cuerpo que los otros seis servicios (helper `writeError` en
`internal/httpapi/respond.go`), en lugar del anterior `{"mensaje": "..."}`:

```json
{ "timestamp": "2026-11-09T12:00:00Z", "status": 409, "error": "Conflict",
  "message": "ya existe un departamento registrado con ese id", "path": "/departamentos",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

- Un id duplicado pasa de `400` a `409 Conflict`: el contrato C3 reserva `409` para unicidad y así
  el cliente distingue "datos mal enviados" de "choca con lo existente".
- Un error de conexión con MySQL (conexión rechazada, host inexistente, conexión rota o plazo
  vencido) responde `503` con `la base de datos no está disponible`; cualquier otro error responde
  `500` con `error interno del servidor`. El detalle (SQL, host, error del driver) va solo al log.
- `traceId` sale del span que abre `otelhttp` (`observability.Handler`), el mismo que se exporta a
  Zipkin; si la petición no tiene traza se omite.
- `middleware.Recoverer` de chi se reemplazó por `recoverer`, que responde el panic como `500` C3.

### Health (contrato C2)

```json
{ "status": "UP", "service": "departamentos-service", "version": "1.0.0",
  "checks": { "database": "UP" } }
```

Solo reporta `database`: el servicio no usa RabbitMQ (no produce ni consume eventos). El `PING`
corre en su propia goroutine con un plazo de 2 s; si MySQL no contesta a tiempo el handler deja de
esperar y responde `503` en menos de 3 s, aunque el driver no respetara el contexto (por ejemplo,
un servidor que acepta TCP y nunca contesta). `version` es `httpapi.Version` (igual a
`info.version` del OpenAPI, lo verifica una prueba), fijable con
`-ldflags "-X departamentos-service/internal/httpapi.Version=x.y.z"` y sobrescribible con
`SERVICE_VERSION`.

## OpenAPI / Swagger

[`openapi.yaml`](openapi.yaml), en la raíz de esta carpeta, es el contrato exportado y la fuente
que se edita. Documenta las seis operaciones con schemas de entrada y salida, ejemplos, todos los
códigos que realmente pueden devolver (`200`/`201`/`204`, `400`, `401`, `403`, `404`, `409`,
`500`, `503`), el esquema de seguridad `bearerAuth` (HTTP bearer, JWT) aplicado a las operaciones
de negocio y `security: []` en `/health`.

El binario sirve una copia idéntica embebida desde `internal/httpapi/swagger/openapi.yaml`:
`go:embed` no puede leer archivos fuera del paquete y el Dockerfile solo copia `cmd/` e
`internal/`. Al cambiar el contrato se edita `openapi.yaml` y se copia a
`internal/httpapi/swagger/`. Las pruebas de `internal/httpapi/openapi_test.go` (sin MySQL ni
Docker) fallan si:

- las dos copias difieren (drift);
- el YAML no parsea, `info.version` no coincide con `httpapi.Version` o falta `bearerAuth` o algún
  campo de `ApiError`;
- alguna ruta registrada en chi (recorrida con `chi.Walk`) no está documentada; solo se excluyen
  las técnicas (`/metrics`, `/openapi.yaml`, `/swagger*`);
- una operación de negocio no exige `bearerAuth` o no documenta `401`, `500`, `503` (y `403` si no
  es `GET`), o `/health` no es público.

| Recurso | URL interna del contenedor |
|---|---|
| Swagger UI | `http://departamentos-service:8081/swagger/index.html` |
| Spec YAML | `http://departamentos-service:8081/openapi.yaml` |

`/swagger` y `/swagger/` redirigen a la UI. El servidor del spec es relativo (`/`): Swagger UI
prueba contra el mismo origen que la sirve.

Estas URLs no son públicas cuando se usa el Compose raíz: el contrato público se valida por
`http://localhost:8080/departamentos` y con la colección de
[`docs/reto3/Reto3.postman_collection.json`](../../docs/reto3/Reto3.postman_collection.json).
Si se ejecuta este servicio de forma aislada y se publica manualmente su puerto, Swagger puede
consultarse en `http://localhost:8081/swagger/index.html`; ese acceso es solo de desarrollo.

## Qué se implementó en la Etapa 1

Scaffold del segundo microservicio, en un lenguaje distinto a Java:

- Módulo Go con `cmd/api` + `internal/{httpapi,department,db,config}`.
- Acceso a MySQL con `database/sql` (sin ORM).
- Esquema versionado con `golang-migrate` (`db/migrations/0001_*.up.sql` / `.down.sql`), aplicado al arrancar.
- Endpoints de negocio: `POST /departamentos` (201 / 400), `GET /departamentos/{id}` (200 / 404), `GET /departamentos` (200).
- `GET /health` real: hace `PING` a MySQL (`200 UP` / `503 DOWN`).
- Dockerfile multi-stage (build → imagen Alpine con el binario).
- Unicidad de `id` garantizada por `PRIMARY KEY`; el error 1062 de MySQL se traduce a `400` (desde la Fase 5, `409`).

## Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8081` | Puerto HTTP del servicio |
| `DB_HOST` | `localhost` | Host de MySQL |
| `DB_PORT` | `3306` | Puerto de MySQL |
| `DB_NAME` | `departamentos_db` | Base de datos |
| `DB_USER` | `departamentos` | Usuario |
| `DB_PASSWORD` | `departamentos` | Contraseña |
| `CHAOS_LATENCY_MS` | `0` | Solo Reto 8 (caos controlado): retardo fijo en ms para `/departamentos`. `0` o inválido = desactivado |
| `SERVICE_VERSION` | `httpapi.Version` (`1.0.0`) | `version` que publica `/health` (contrato C2), por ejemplo el tag de la imagen |

En Docker Compose, `DB_HOST` debe ser el nombre del servicio de la base de datos
(`database-departamentos`), no `localhost` — ver `docker-compose.yml` raíz.

`CHAOS_LATENCY_MS` existe para demostrar en el Reto 8 cómo una dependencia lenta se ve en el panel
de latencia, en la alerta p95 y en Zipkin. Por qué así: es determinista (no aleatorio), se monta solo
en las rutas de negocio para que `/health` y `/metrics` sigan respondiendo a Docker y Prometheus, y
Compose lo deja en `0`. Lo activa y revierte `observability/caos.ps1`.

## Esquema de base de datos: golang-migrate con rollback

El esquema **no** se crea con auto-DDL de ningún framework (aquí no hay ORM) ni con un
`init.sql` de una sola vez: se versiona con
[`golang-migrate`](https://github.com/golang-migrate/migrate), embebido como librería en
`internal/db/migrate.go`. Al arrancar, el servicio aplica automáticamente cualquier changelog
pendiente contra MySQL (`docker compose up --build` deja el esquema listo sin pasos manuales).

Los changelogs viven en `db/migrations/`, versionados y en pares `up`/`down`:

```
db/migrations/
├── 0001_create_departamentos.up.sql    # crea la tabla, con id como PRIMARY KEY (garantiza UNIQUE)
└── 0001_create_departamentos.down.sql  # rollback: DROP TABLE
```

Para agregar un cambio de esquema futuro: crear un nuevo par `0002_algo.up.sql` /
`0002_algo.down.sql` — nunca editar un changelog ya aplicado en algún ambiente compartido.

### Ejecutar migraciones a mano (fuera de Docker, para depurar)

Requiere la [CLI de golang-migrate](https://github.com/golang-migrate/migrate/tree/master/cmd/migrate):

```bash
migrate -path db/migrations -database "mysql://departamentos:departamentos@tcp(localhost:3306)/departamentos_db" up

# Rollback de la última migración aplicada:
migrate -path db/migrations -database "mysql://departamentos:departamentos@tcp(localhost:3306)/departamentos_db" down 1
```

## Garantía de unicidad

`id` es la `PRIMARY KEY` de la tabla (restricción real en el esquema, no solo una consulta
previa). El repositorio (`internal/department/repository.go`) intercepta el error 1062 de MySQL
(entrada duplicada) y lo traduce a `ErrAlreadyExists` → `409 Conflict` (antes de la Fase 5, `400`). Esto evita la ventana
de condición de carrera entre "consultar si existe" e "insertar" que tendría una comprobación
solo en aplicación.

`nombre` no lleva `UNIQUE` (revisión E05 de la Fase 5): ni el enunciado ni el servicio definen que
dos departamentos no puedan llamarse igual, y una restricción nueva podría romper el arranque
sobre volúmenes con nombres repetidos. Si el equipo decide que el nombre debe ser único, se agrega
como una migración nueva `0002_*` (con su `.down.sql`) y el handler debe responder `409`.

`internal/db/migrate_test.go` (sin MySQL) verifica que cada `.up.sql` tenga su `.down.sql` no vacío,
que los nombres sigan `NNNN_descripcion.(up|down).sql` sin versiones repetidas y que `id` sea
`PRIMARY KEY`.

## Correr en local (sin Docker)

Requiere Go 1.25 o superior; versiones antiguas del toolchain no reconocen la versión declarada
en `go.mod`.

```bash
go run ./cmd/api
```

Necesitas MySQL corriendo y accesible con las variables de entorno de arriba (por defecto
apunta a `localhost:3306`).

## Ejecutar el sistema integrado

Desde la raíz del monorepo:

```bash
cp .env.example .env
docker compose up --build -d
```

La API pública queda disponible en `http://localhost:8080/departamentos`. Para detener los
servicios sin borrar los datos: `docker compose down`.

## Pruebas y cobertura

### Fase 8 — cobertura, integración y mutación (proyecto final)

| Aspecto | Valor |
|---|---|
| Comando | `sh scripts/test.sh` (JUnit `reports/junit.xml`, `coverage.out`, falla bajo 70 %) |
| Cobertura | 43 pruebas · 74,2 % global, sin excluir paquetes |
| Integración (E14) | `internal/integration` (build tag `integration`): migraciones up/down/up y CRUD HTTP contra MySQL real (`bash scripts/test-integration.sh departamentos`) |
| Mutación (E17) | `service.go`: quitar la validación de `nombre` en el alta. El mutante sobrevivió la primera vez y reveló un caso sin prueba; se añadió y ahora falla |

```bash
sh scripts/test.sh
```

El script ejecuta todos los paquetes, genera `coverage.out` y `reports/junit.xml`, y termina con
error si la cobertura global baja de 70 %. No se excluyen paquetes. Medición del 2026-11-11:
74,3 % global; configuración 100 %, dominio/repositorio 86,9 %, HTTP 85,6 % y observabilidad 74 %.
La integración real contra MySQL se completa mediante la regresión BDD del stack.

## Build de la imagen

```bash
docker build -t departamentos-service .
```

Ver `docker-compose.yml` en la raíz del monorepo para levantarlo junto con su base de datos y
con `empleados-service`.

### Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `build` | `golang:1.25.11-alpine` | `go mod download` y `CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" ./cmd/api` |
| `runtime` | `alpine:3.22.2` | Binario estático, `db/migrations/` y `curl` |

- **Usuario:** `app` de sistema con UID/GID fijos `10001:10001`, sin home ni shell de login
  (`USER 10001:10001`). Binario y migraciones con dueño `root`, de solo lectura; el servicio no
  escribe en disco (golang-migrate guarda su versión en la tabla `schema_migrations` de MySQL).
- **Por qué alpine y no distroless:** el healthcheck de Compose ejecuta
  `curl -f http://localhost:8081/health` y distroless no trae `curl` ni shell. Alpine + `curl`
  mantiene ese contrato sin añadir un subcomando de healthcheck al binario ni cambiar Compose.
- **Por qué multi-stage:** el toolchain de Go (cientos de MB), la caché de módulos y el código
  fuente se quedan en `build`; a `runtime` solo llega un binario estático.
- **Por qué no root:** el proceso no puede reescribir su binario ni sus migraciones. El UID fijo
  debe poder leer `/run/secrets/departamentos_db_password` (ver
  `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Base fijada:** Go `1.25.11`, igual que la directiva `go` de `go.mod`; runtime en Alpine
  `3.22.2`. La etapa `build` usa el sufijo `-alpine` sin versión de Alpine porque no afecta a la
  imagen final (el binario es estático).
- `.dockerignore` deja fuera `*_test.go`, `coverage.out`, `.env` y `secrets`; no excluye
  `internal/httpapi/swagger/`, que se embebe con `go:embed`.
