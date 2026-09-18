# CLAUDE.md

Guía técnica de referencia para el equipo: estructura del repositorio, convenciones por servicio
y flujo de trabajo entre varias personas.

## Qué es este repositorio

Monorepo del proyecto de microservicios del curso. Es un solo repositorio con un **módulo por
servicio** bajo `services/`, cada uno en su propio lenguaje, con su propio Dockerfile. Los
servicios de negocio tienen su propia base de datos; el API Gateway no. El objetivo final del
curso exige al menos 4 lenguajes distintos — cada reto nuevo añade un servicio en un lenguaje
diferente. No junten servicios de distintos lenguajes en una sola carpeta ni compartan base de
datos entre servicios.

Documentos de referencia que SIEMPRE hay que leer antes de tocar código:

- Reto 3 (activo): [`docs/reto3/PLAN-RETO3.md`](docs/reto3/PLAN-RETO3.md),
  [`docs/reto3/STATUS.md`](docs/reto3/STATUS.md), [`docs/reto3/reto3.pdf`](docs/reto3/reto3.pdf).
  **Actualiza `docs/reto3/STATUS.md` en el mismo commit/PR que cierra o avanza una etapa.**
- Reto 2 (cerrado): [`docs/reto2/PLAN-RETO2.md`](docs/reto2/PLAN-RETO2.md),
  [`docs/reto2/STATUS.md`](docs/reto2/STATUS.md), [`docs/reto2/reto2.pdf`](docs/reto2/reto2.pdf).

## Estructura del repositorio

```
micro/
├── CLAUDE.md                    # este archivo
├── docker-compose.yml           # orquestación raíz: todos los servicios + BDs
├── .env.example                 # variables de entorno de referencia (nunca commitear .env real)
├── docs/
│   ├── reto2/                   # plan, STATUS, colección y PDF del Reto 2 (cerrado)
│   └── reto3/                   # plan, STATUS, colección y PDF del Reto 3 (activo)
└── services/
    ├── empleados-service/       # Reto 1, Java 21 + Spring Boot + PostgreSQL
    ├── departamentos-service/   # Reto 2, Go + chi + MySQL
    └── api-gateway/             # Reto 3, Node.js 22 + Express (sin BD)
```

Cada carpeta en `services/` es autocontenida: su propio `Dockerfile`, su propia configuración,
su propio README con sus endpoints. `docker-compose.yml` en la raíz es lo único que los conecta,
y solo por red HTTP — nunca por base de datos compartida.

## Convenciones para nuevos servicios

- Un servicio = una carpeta bajo `services/<nombre>-service/` (o `services/api-gateway/` para el
  borde) con su propio Dockerfile. El Gateway es un microservicio más: no comparte runtime ni
  carpeta con Java/Go, y no lleva base de datos.
- Nunca acceder directamente a la base de datos de otro servicio. Comunicación entre servicios
  solo por HTTP (o el mecanismo que definan retos futuros), nunca por SQL cruzado.
- Toda credencial de base de datos y toda URL de otro servicio se configura por variable de
  entorno, nunca hardcodeada. `docker-compose.yml` es quien las inyecta.
- Versionamiento de esquema de base de datos siempre mediante changelogs controlados con
  rollback (ver detalle de la herramienta elegida por servicio en su propio README):
  - `empleados-service` (Java): Liquibase — changelogs en
    `services/empleados-service/src/main/resources/db/changelog/`.
  - `departamentos-service` (Go): `golang-migrate` — archivos `NNNN_descripcion.up.sql` /
    `.down.sql` en `services/departamentos-service/db/migrations/`.
  - Nunca usar auto-DDL de un ORM (`hibernate.ddl-auto=update`, etc.) como mecanismo definitivo
    de esquema — sirve para prototipar, no es lo que se entrega.
- Cada servicio de negocio expone su propio Swagger/OpenAPI (Springdoc en Java, OpenAPI estático
  en Go) y un `/health` real que verifica su conexión a base de datos. El `api-gateway` expone
  `/health` propio **sin** ping a los backends (si un destino está caído el borde debe seguir UP).
- Health check de infraestructura (Docker `healthcheck` + `depends_on: condition: service_healthy`)
  y reintentos con backoff en el cliente HTTP resuelven problemas distintos — implementar ambos,
  no solo uno (ver §2.4 y §6 de `docs/reto2.pdf`).

## Flujo de trabajo entre varias personas

1. Antes de empezar una etapa, revisa `docs/reto3/STATUS.md` para ver qué sigue disponible y qué
   ya está tomado/hecho.
2. Trabaja dentro de la carpeta del servicio que te corresponde; evita tocar otro módulo salvo que
   la etapa lo requiera explícitamente (p. ej. `docker-compose.yml` raíz sí lo tocan varias etapas).
3. Al terminar una etapa, actualiza `docs/reto3/STATUS.md` (marca la etapa, agrega notas de
   decisiones tomadas) en el mismo commit.
4. Las decisiones técnicas del enunciado vigente se documentan en el README de cada servicio
   afectado, con el "por qué", no solo el "qué". El Gateway **no** se implementa con Spring Cloud
   Gateway: repetiría Java/Spring Boot.

## Comandos de referencia

```bash
# Levantar todo el sistema desde cero
docker compose up --build

# Ver salud de los contenedores
docker compose ps

# Apagar conservando datos
docker compose down

# Apagar y borrar también los volúmenes (reinicio total, pierde datos)
docker compose down -v
```

## Historial de `empleados-service`

`services/empleados-service/` se incorporó al monorepo mediante `git subtree`, preservando el
historial completo del repositorio original de Reto 1
(`github.com/juan-contrerasM/reto1-microservicios`). No reescribas ese historial ni hagas squash
de esos commits.
