# CLAUDE.md

Guía técnica de referencia para el equipo: estructura del repositorio, convenciones por servicio
y flujo de trabajo entre varias personas.

## Qué es este repositorio

Monorepo del proyecto de microservicios del curso. Es un solo repositorio con un **módulo por
servicio** bajo `services/`, cada uno en su propio lenguaje, con su propia base de datos y su propio
Dockerfile. El objetivo final del curso exige al menos 4 lenguajes distintos — cada reto nuevo añade
un servicio en un lenguaje diferente. No junten servicios de distintos lenguajes en una sola carpeta
ni compartan base de datos entre servicios.

Documentos de referencia que SIEMPRE hay que leer antes de tocar algo de Reto 2:

- [`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md) — plan de implementación de Reto 2, en etapas.
- [`STATUS.md`](STATUS.md) — qué parte del plan ya está implementada y qué falta. **Actualízalo
  en el mismo commit/PR que cierra o avanza una etapa.** Somos varias personas trabajando en
  paralelo; este archivo es la única fuente de verdad sobre el avance real (no el enunciado, no la
  memoria de nadie).
- [`docs/reto2.pdf`](docs/reto2.pdf) — enunciado original del Reto 2 (Orquestación de Servicios y
  Persistencia de Datos).

## Estructura del repositorio

```
micro/
├── CLAUDE.md                    # este archivo
├── STATUS.md                    # progreso por etapas (mantenerlo actualizado)
├── docker-compose.yml           # orquestación raíz: todos los servicios + BDs
├── .env.example                 # variables de entorno de referencia (nunca commitear .env real)
├── docs/
│   ├── PLAN-RETO2.md            # plan de implementación en etapas
│   └── reto2.pdf                # enunciado original
└── services/
    ├── empleados-service/       # Reto 1, Java 21 + Spring Boot + PostgreSQL
    └── departamentos-service/   # Reto 2, Go + chi + MySQL
```

Cada carpeta en `services/` es autocontenida: su propio `Dockerfile`, su propia configuración,
su propio README con sus endpoints. `docker-compose.yml` en la raíz es lo único que los conecta,
y solo por red HTTP — nunca por base de datos compartida.

## Convenciones para nuevos servicios

- Un servicio = una carpeta bajo `services/<nombre>-service/` con su propio Dockerfile.
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
- Cada servicio expone su propio Swagger/OpenAPI (Springdoc en Java, `swaggo` u OpenAPI estático
  en Go) y un endpoint de health check real que verifica su conexión a base de datos.
- Health check de infraestructura (Docker `healthcheck` + `depends_on: condition: service_healthy`)
  y reintentos con backoff en el cliente HTTP resuelven problemas distintos — implementar ambos,
  no solo uno (ver §2.4 y §6 de `docs/reto2.pdf`).

## Flujo de trabajo entre varias personas

1. Antes de empezar una etapa, revisa `STATUS.md` para ver qué sigue disponible y qué ya está
   tomado/hecho.
2. Trabaja dentro de la carpeta del servicio que te corresponde; evita tocar otro módulo salvo que
   la etapa lo requiera explícitamente (p. ej. `docker-compose.yml` raíz sí lo tocan varias etapas).
3. Al terminar una etapa, actualiza `STATUS.md` (marca la etapa, agrega notas de decisiones
   tomadas) en el mismo commit.
4. Las tres decisiones técnicas que pide el enunciado del Reto 2 (motor de BD por servicio,
   estrategia de creación de esquema, garantía de unicidad) se documentan en el README de cada
   servicio afectado, con el "por qué", no solo el "qué".

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
