# Sistema de Onboarding/Offboarding de Empleados — Monorepo

Monorepo de microservicios del curso. Un módulo por servicio bajo `services/`, cada uno con su
propio lenguaje, base de datos y Dockerfile, orquestados desde un único `docker-compose.yml` en
la raíz.

> Antes de tocar código: lee [`CLAUDE.md`](CLAUDE.md) (convenciones del repo),
> [`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md) (plan en etapas) y [`STATUS.md`](STATUS.md)
> (qué está hecho y qué falta ahora mismo — este proyecto lo hacemos entre varias personas).

## Servicios

| Servicio | Lenguaje | Motor de BD | Puerto host | Carpeta |
|---|---|---|---|---|
| `empleados-service` | Java 21 / Spring Boot | PostgreSQL 16 | `8080` | [`services/empleados-service`](services/empleados-service) |
| `departamentos-service` | Go | MySQL 8.4 | `8081` | [`services/departamentos-service`](services/departamentos-service) |

## Arranque desde cero

```bash
cp .env.example .env      # ajustar credenciales si hace falta
docker compose up --build
```

Verificar que ambas bases de datos queden `(healthy)` antes que su servicio:

```bash
docker compose ps
```

Detener conservando datos:

```bash
docker compose down
```

Detener y borrar también los volúmenes (reinicio total, se pierden los datos — útil para
reproducir el esquema desde cero):

```bash
docker compose down -v
```

## Documentación OpenAPI / Swagger (Etapa 4)

Con el compose arriba, cada servicio expone su propia UI:

| Servicio | Swagger UI | Spec |
|---|---|---|
| `empleados-service` | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| `departamentos-service` | http://localhost:8081/swagger/index.html | http://localhost:8081/openapi.yaml |

Java usa **Springdoc** (anotaciones sobre los controllers). Go sirve un **`openapi.yaml` estático** embebido en el binario, sin codegen.

## Qué se implementó en las etapas 1 y 2

Detalle del plan: [`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md). Avance real: [`STATUS.md`](STATUS.md).

### Etapa 1 — `departamentos-service` (Go)

Segundo microservicio, en un lenguaje distinto al de empleados:

- Go + [chi](https://github.com/go-chi/chi) + MySQL 8.4, sin ORM (`database/sql`).
- Endpoints: `POST /departamentos` (201 / 400 si el id ya existe), `GET /departamentos/{id}` (200 / 404), `GET /departamentos` (200).
- `GET /health` real: hace `PING` a MySQL (`200 UP` / `503 DOWN`). Lo usa Docker como healthcheck del contenedor.
- Esquema versionado con `golang-migrate` (`db/migrations/0001_create_departamentos.up.sql` / `.down.sql`), aplicado al arrancar. `id` es `PRIMARY KEY` (unicidad en el esquema); el error 1062 se traduce a 400.
- Dockerfile multi-stage. Variables de entorno (nunca hosts/credenciales hardcodeados).

README del servicio: [`services/departamentos-service/README.md`](services/departamentos-service/README.md).

### Etapa 2 — `docker-compose.yml` raíz

Orquestación de los 4 contenedores con arranque ordenado:

- Servicios: `empleados-service`, `departamentos-service`, `database-empleados` (Postgres), `database-departamentos` (MySQL).
- Red interna `microservices-network`: los servicios se resuelven por nombre (p. ej. `http://departamentos-service:8081`). Nunca `localhost` entre contenedores.
- Un volumen por base (`vol-empleados`, `vol-departamentos`): `docker compose down` conserva datos; `down -v` los borra.
- Healthchecks reales: `pg_isready` en Postgres; `mysqladmin ping --protocol=tcp -h 127.0.0.1` en MySQL (el ping por socket Unix daba falso "healthy" durante el mysqld temporal de inicialización).
- `depends_on: condition: service_healthy`: cada API espera a su BD; `empleados-service` también espera a `departamentos-service`.
- Credenciales y URLs inyectadas desde `.env` (plantilla: `.env.example`).
- El healthcheck de `empleados-service` apunta a `GET /health` (Etapa 3).

### Etapa 3 — evolución de `empleados-service`

- Liquibase versiona el esquema (`UNIQUE` en `email` y `numeroEmpleado`); Hibernate en `validate`.
- `POST /empleados` responde **201**. Duplicados y departamento inexistente responden **400**.
- Cliente HTTP a `departamentos-service`: timeout 3s, 4 intentos, backoff 1s→2s→4s. Si se agotan, **503** y no persiste.
- `GET /health` hace PING real a PostgreSQL (`200 UP` / `503 DOWN`).

## Decisiones técnicas (Reto 2)

Resumen — el detalle y el "por qué" de cada una está en `docs/PLAN-RETO2.md` y en el README de
cada servicio afectado:

1. **Motor de BD por servicio**: PostgreSQL para empleados, MySQL para departamentos
   (persistencia poliglota deliberada).
2. **Creación de esquema**: changelogs versionados con rollback — Liquibase en `empleados-service`,
   `golang-migrate` en `departamentos-service`. Nunca auto-DDL de ORM como mecanismo definitivo.
3. **Garantía de unicidad**: restricción a nivel de esquema (`UNIQUE`/`PRIMARY KEY`) además de
   consulta previa desde el código, para no depender solo de una ventana de "consultar y luego
   insertar".

## Estado del proyecto

Ver [`STATUS.md`](STATUS.md) para el detalle etapa por etapa. Al momento de este commit:
etapas 0–4 hechas (monorepo, `departamentos-service`, compose con healthchecks, evolución de
empleados con Liquibase/cliente HTTP/`/health`, OpenAPI/Swagger en ambos servicios). Pendiente
cerrar la Etapa 5 (colección Postman y evidencia final).
