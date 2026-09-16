# departamentos-service

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

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/departamentos` | Registra un departamento | `201` creado · `400` datos inválidos o id duplicado |
| `GET` | `/departamentos/{id}` | Consulta un departamento por id | `200` · `404` no existe |
| `GET` | `/departamentos` | Lista todos los departamentos | `200` |
| `GET` | `/health` | Health check real (hace `PING` a MySQL) | `200` `UP` · `503` `DOWN` |

Modelo:

```json
{ "id": "IT", "nombre": "Tecnología", "descripcion": "Departamento de TI" }
```

Los errores siguen el mismo formato que `empleados-service`: `{"mensaje": "..."}`.

## OpenAPI / Swagger

La especificación es un `openapi.yaml` estático embebido en el binario (sin codegen de `swaggo`). Cada endpoint documenta descripción, códigos `200`/`201`/`400`/`404`/`500` y esquemas de entrada/salida.

| Recurso | URL (con Compose en la raíz) |
|---|---|
| Swagger UI | http://localhost:8081/swagger/index.html |
| Spec YAML | http://localhost:8081/openapi.yaml |

`/swagger` y `/swagger/` redirigen a la UI.

## Qué se implementó en la Etapa 1

Scaffold del segundo microservicio, en un lenguaje distinto a Java:

- Módulo Go con `cmd/api` + `internal/{httpapi,department,db,config}`.
- Acceso a MySQL con `database/sql` (sin ORM).
- Esquema versionado con `golang-migrate` (`db/migrations/0001_*.up.sql` / `.down.sql`), aplicado al arrancar.
- Endpoints de negocio: `POST /departamentos` (201 / 400), `GET /departamentos/{id}` (200 / 404), `GET /departamentos` (200).
- `GET /health` real: hace `PING` a MySQL (`200 UP` / `503 DOWN`).
- Dockerfile multi-stage (build → imagen Alpine con el binario).
- Unicidad de `id` garantizada por `PRIMARY KEY`; el error 1062 de MySQL se traduce a `400`.

## Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8081` | Puerto HTTP del servicio |
| `DB_HOST` | `localhost` | Host de MySQL |
| `DB_PORT` | `3306` | Puerto de MySQL |
| `DB_NAME` | `departamentos_db` | Base de datos |
| `DB_USER` | `departamentos` | Usuario |
| `DB_PASSWORD` | `departamentos` | Contraseña |

En Docker Compose, `DB_HOST` debe ser el nombre del servicio de la base de datos
(`database-departamentos`), no `localhost` — ver `docker-compose.yml` raíz.

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
(entrada duplicada) y lo traduce a `ErrAlreadyExists` → `400 Bad Request`. Esto evita la ventana
de condición de carrera entre "consultar si existe" e "insertar" que tendría una comprobación
solo en aplicación.

## Correr en local (sin Docker)

```bash
go run ./cmd/api
```

Necesitas MySQL corriendo y accesible con las variables de entorno de arriba (por defecto
apunta a `localhost:3306`).

## Build de la imagen

```bash
docker build -t departamentos-service .
```

Ver `docker-compose.yml` en la raíz del monorepo para levantarlo junto con su base de datos y
con `empleados-service`.
