# Plan de implementación — Reto 2: Orquestación de Servicios y Persistencia de Datos

Basado en `docs/reto2.pdf`. Este documento traza el plan en etapas para que el equipo pueda
repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](../STATUS.md).

## Decisiones de arquitectura ya tomadas

Estas decisiones fijan las reglas del juego para todas las etapas; si alguien quiere cambiarlas,
que lo discuta con el equipo antes de tocar el compose raíz.

| Decisión | Elección | Por qué |
|---|---|---|
| Estructura del repo | Monorepo con un módulo por servicio bajo `services/` | El proyecto final exige ≥4 lenguajes; un monorepo con módulos aislados evita reescrituras futuras y facilita el `docker-compose.yml` único. |
| Lenguaje de `empleados-service` | Java 21 / Spring Boot (heredado de Reto 1) | Ya existía y funciona; migrarlo de lenguaje no aporta nada en Reto 2. |
| Lenguaje de `departamentos-service` | Go | Cumple el requisito de diversidad tecnológica del Reto 2 (debe ser distinto al de empleados) y dejar Go instalado ahora facilita retos futuros (rendimiento, gRPC, etc. en retos posteriores del curso). |
| Motor de BD de `empleados-service` | PostgreSQL (heredado) | Ya en uso desde Reto 1. |
| Motor de BD de `departamentos-service` | MySQL | Persistencia poliglota real (uno de los puntos de discusión que pide el enunciado, §Decisión técnica): distintos motores por servicio. Costo: el equipo debe operar dos motores distintos; se documenta en el README de cada servicio. |
| Versionamiento de esquema | Changelogs controlados con rollback, nunca auto-DDL de ORM | El enunciado (§5) desaconseja explícitamente `hibernate.ddl-auto` / `synchronize: true` como mecanismo definitivo. Java → **Liquibase**. Go → **golang-migrate** (`.up.sql` / `.down.sql` por versión). |
| Comunicación entre servicios | HTTP REST síncrono, con timeout + reintentos con backoff | Es lo que pide el enunciado para este reto (§4, §6). Circuit breaker llega en Reto 3 con el API Gateway — no implementarlo todavía. |

## Etapas

Cada etapa indica **qué entrega**, **de qué depende** y **a qué criterio de evaluación del PDF
corresponde** (los 5 puntos del reto). Varias etapas pueden avanzar en paralelo si no dependen
entre sí — se marca explícitamente cuándo sí hay dependencia dura.

### Etapa 0 — Reorganización del monorepo (infraestructura, sin puntaje directo)

- Convertir el repo en monorepo: `services/empleados-service/` (contenido de `reto1/`,
  historial preservado vía `git subtree`) + `services/departamentos-service/` (nuevo).
- `.gitignore` raíz cubriendo Java, Go y artefactos de Docker.
- `CLAUDE.md`, este plan y `STATUS.md`.

No depende de nada. Bloquea a todas las demás etapas (da la estructura de carpetas sobre la que
trabajan).

### Etapa 1 — Scaffold de `departamentos-service` (Go) — criterio 2 del PDF

- Estructura del módulo: `cmd/api`, `internal/{http,department,db}`, `db/migrations/`.
- Conexión a MySQL vía `database/sql` + driver `go-sql-driver/mysql` (sin ORM).
- `golang-migrate`: changelog inicial (`0001_create_departamentos.up.sql` /
  `.down.sql`) con la restricción `UNIQUE` que exige la unicidad de datos.
- Router `chi` con los 3 endpoints requeridos:
  - `POST /departamentos` → 201 / 400 si el `id` ya existe.
  - `GET /departamentos/{id}` → 200 / 404.
  - `GET /departamentos` → 200.
- `GET /health` — endpoint real que hace `db.Ping()` (no solo "responde 200"), para usarlo como
  healthcheck de Docker del propio servicio y para retos futuros (Reto 8 lo formaliza más).
- `Dockerfile` multi-stage (build → imagen mínima con el binario).
- README del servicio: endpoints, variables de entorno, cómo correr migraciones a mano.

Depende de Etapa 0. Puede avanzar en paralelo con Etapa 3.

### Etapa 2 — `docker-compose.yml` raíz y arranque ordenado — criterio 1 del PDF

- Definir los 4 servicios: `empleados-service`, `departamentos-service`, `database-empleados`
  (Postgres), `database-departamentos` (MySQL).
- Red interna (`microservices-network`), un volumen por base de datos.
- `healthcheck` en cada base de datos (`pg_isready` / `mysqladmin ping`) y en cada servicio de
  negocio (contra su propio `/health` o `/departamentos`).
- `depends_on: condition: service_healthy` encadenando: servicio de negocio → su BD, y
  `empleados-service` → `departamentos-service` (además de su propia BD), ya que valida
  departamento contra ese servicio al registrar un empleado.
- Variables de entorno para credenciales y URLs (usar nombre de servicio Compose como host,
  puerto **interno**, no el publicado).
- `.env.example` en la raíz documentando cada variable.

Depende de Etapa 1 (necesita que `departamentos-service` exista y tenga Dockerfile) y de que
`empleados-service` ya tenga Dockerfile (ya lo tiene, heredado de Reto 1).

### Etapa 3 — Evolución de `empleados-service` — criterios 3 y 4 del PDF

- Ajustar códigos de respuesta al contrato del PDF (§4): verificado en la práctica que hoy
  `POST /empleados` responde `200` (debe ser `201 Created`) y los conflictos de unicidad
  responden `409` (el PDF pide `400 Bad Request` para email/numeroEmpleado duplicados).
- Migrar de `spring.jpa.hibernate.ddl-auto=update` a **Liquibase**: changelog inicial que
  reproduzca el esquema actual (incluida la columna `estado`, aunque en este reto siempre valga
  `ACTIVO`) + restricciones `UNIQUE` en `email` y `numeroEmpleado` a nivel de esquema (no solo
  consulta previa desde el código — documentar en el README por qué se usan ambas).
  - **Cuidado:** ya hay datos de prueba locales creados con `ddl-auto=update`. El changelog debe
    quedar en estado consistente para quien levante el sistema desde cero (`docker compose down
    -v` primero al migrar).
- Cliente HTTP hacia `departamentos-service` para validar `departamentoId` al registrar un
  empleado (`GET http://departamentos-service:<puerto-interno>/departamentos/{id}`):
  - Timeout explícito (p. ej. 2–3s).
  - Reintentos con backoff creciente (1s → 2s → 4s), número máximo definido.
  - Decidir y documentar en el README qué pasa si se agotan los reintentos (¿rechazar el
    registro con 400/503, o aceptar como "pendiente de validación"?). Recomendación: rechazar
    con `503 Service Unavailable` y mensaje descriptivo — "pendiente de validación" implica un
    estado adicional en el modelo que el reto no pide todavía.
- Endpoint `/health` real (verifica conexión a su propia BD) para el healthcheck de Docker.

Depende de Etapa 1 (necesita saber el contrato/endpoints reales de `departamentos-service`) y de
Etapa 0.

### Etapa 4 — OpenAPI / Swagger en ambos servicios — criterios 2 y 3 del PDF

- `empleados-service`: Springdoc OpenAPI, expuesto en `/swagger-ui.html` (o `/docs`).
- `departamentos-service`: especificación OpenAPI (anotaciones `swaggo/swag` generando
  `/swagger/index.html`, o un `openapi.yaml` estático servido si se prefiere no añadir otra
  dependencia de codegen).
- Cada endpoint documentado con descripción, códigos de respuesta (200/201/400/404/500) y
  esquemas de entrada/salida.

Puede avanzar en paralelo con Etapas 2 y 3 una vez que los endpoints de cada servicio estén
definidos (no hace falta esperar a que compose esté listo).

### Etapa 5 — Pruebas del sistema, evidencia y documentación final — criterio 5 del PDF (+ transversal)

- Ejecutar el flujo de prueba del PDF (§8): crear departamento → crear empleado → verificar los
  10 campos incluido `estado` → las tres validaciones (`email` duplicado, `numeroEmpleado`
  duplicado, departamento inexistente) responden `400`.
- Evidencia de arranque ordenado: salida de `docker compose ps` mostrando ambas BDs `(healthy)`
  antes que los servicios, sin errores de conexión en logs.
- Evidencia de persistencia: contraste `docker compose down` (los datos sobreviven) vs.
  `docker compose down -v` (se pierden) — capturar ambas salidas.
- Actualizar `Reto1.postman_collection.json` (renombrar/ampliar) con los endpoints de
  `departamentos-service` y los casos de error.
- README raíz: tabla servicio ↔ lenguaje ↔ motor de BD ↔ puerto, instrucciones de arranque desde
  cero, y las tres decisiones técnicas del enunciado con su justificación (ya resumidas arriba,
  pero deben quedar también en el README raíz porque así lo pide el PDF como entregable).

Depende de que Etapas 1–4 estén terminadas.

## Fuera de alcance de Reto 2 (a propósito)

- Circuit breaker y API Gateway → Reto 3.
- Endpoints `PUT`/`DELETE` y eventos → Reto 4.
- Transiciones de `estado` (`EN_VACACIONES`, `RETIRADO`) → Retos 4 y 5.
- Métricas/trazas por stack, SonarQube multi-lenguaje → Reto 7 y 8. No instrumentar nada de esto
  todavía; solo tenerlo presente en las decisiones (p. ej. por qué Go y no otro lenguaje) para no
  bloquear esos retos después.
