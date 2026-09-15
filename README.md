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
reorganización a monorepo completa; `departamentos-service` (Go) scaffolded con sus endpoints,
migraciones y Dockerfile; `docker-compose.yml` raíz conecta ambos servicios y sus bases de datos
con health checks. La evolución de `empleados-service` (Liquibase, cliente HTTP con
timeout/retry hacia departamentos, OpenAPI) sigue pendiente.
