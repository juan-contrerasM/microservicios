# Estado de implementación — Reto 4

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`PLAN-RETO4.md`](PLAN-RETO4.md). Enunciado: [`reto4.pdf`](reto4.pdf).

El Reto 3 está cerrado; su estado histórico vive en [`docs/reto3/STATUS.md`](../reto3/STATUS.md).
No reabrir esas etapas salvo el `docker-compose.yml` raíz y las rutas nuevas del Gateway.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS y colección Postman en `docs/reto4/` (URL de negocio `http://localhost:8080`) | ✅ | Contrato listo. Importar [`Reto4.postman_collection.json`](Reto4.postman_collection.json). Las carpetas 0 a 7 ya se pueden correr. La 8 y la 9 siguen siendo manuales. |
| 1 | RabbitMQ en `docker-compose.yml` (AMQP + management UI), healthcheck, credenciales por entorno, justificación frente a Kafka / Redis Streams / NATS | ✅ | Criterio 1. `message-broker` (`rabbitmq:3-management`), volumen `vol-rabbitmq`, UI en `:15672`. Justificación en el README raíz. Exchange y colas los declaran los servicios en etapas posteriores. |
| 2 | `empleados-service`: publish de `empleado.creado`, `empleado.actualizado` y `empleado.retirado`. `PUT`, `DELETE` lógico con `fechaRetiro`, auditoría `?estado=RETIRADO` | ✅ | Criterio 2. Liquibase `002-add-fecha-retiro` (TIMESTAMP, con rollback). El publish va después del commit; si el broker falla, la fila queda y el error queda en el log. `BROKER_URL` ya se inyecta en el compose de empleados para que el publicador alcance `message-broker`. Las cargas quedaron alineadas al catálogo: `actualizado` sin `numeroEmpleado`/`fechaIngreso`/`estado`, `retirado` con `motivo` (`RENUNCIA` si el DELETE no trae cuerpo). |
| 3 | `notificaciones-service` (Python): consume los tres eventos, log `[NOTIFICACIÓN]`, historial, deduplicación por `id`, OpenAPI | ✅ | Criterio 3. Cola `q.notificaciones`, ack manual, prefetch 1. Si `vacaciones.programadas` no trae email ni hay alta previa, se confirma sin fila para no bloquear la cola. La republicación del mismo `id` quedó en la etapa 7. El compose y el Gateway ya exponen `/notificaciones` en `:8084` interno. |
| 4 | `perfiles-service` (Java 21 / Spring Boot): perfil por defecto, sincroniza nombre/email, archiva al retirar, REST y OpenAPI | ✅ | Criterio 4. Cola `q.perfiles` con `empleado.creado`, `empleado.actualizado` y `empleado.retirado`. Ack manual, prefetch 1, deduplicación por `id`. Liquibase propio. El compose y el Gateway ya exponen `/perfiles` en `:8083` interno. |
| 5 | `vacaciones-service` (Node.js 22 / Express): CRUD, cuatro validaciones, publica `vacaciones.programadas`, réplica local de empleados | ✅ | Criterio 5. Cola `q.vacaciones` con `empleado.creado` y `empleado.retirado`. El evento lleva `vacacionesId`, `email` y `diasHabiles` (lunes a viernes, inclusive). node-pg-migrate, revisión en `001_inicial.cjs` porque el migrador carga los archivos con `require`. Si el broker falla al publicar, el período queda. `/health` exige base y consumidor conectados. |
| 6 | Gateway: `/perfiles`, `/notificaciones`, `/vacaciones` con `expose:` y 503 JSON. Compose de los tres servicios y sus Postgres | ✅ | Sin puntaje propio. Los tres servicios quedan detrás de `:8080`, cada uno con `expose` y su Postgres. El `/health` del gateway no consulta backends. |
| 7 | README raíz (broker, tabla de lenguajes, eventos, réplica), evidencias del §6 incluida la deduplicación, Newman de la colección | ✅ | Criterio 6. README raíz y [`EVIDENCIAS.md`](EVIDENCIAS.md). El mismo `empleado.creado` se publicó dos veces y dejó una bienvenida y un perfil. `down`/`up` sin `-v` conservó perfil, aviso, período y empleado. Newman de las carpetas 0 a 7 pide bases vacías; aquí `E001` ya estaba `RETIRADO`, así que no se repitió ese POST. El comando está en el README. |

## Estado de entrega

Las etapas **0** a **7** están cerradas. El tráfico de negocio entra por `http://localhost:8080`:
empleados, departamentos, perfiles, notificaciones y vacaciones. La evidencia está en
[`EVIDENCIAS.md`](EVIDENCIAS.md). Las carpetas 8 y 9 de Postman se corren a mano.

## Decisiones técnicas del enunciado

Registrar aquí la versión corta. El "por qué" largo está en `PLAN-RETO4.md` y, al cerrar la
etapa, debe copiarse al README que el PDF pide. Mientras la etapa no cierre, queda como
"propuesta".

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Message broker | Tomada | RabbitMQ 3 management. Exchange topic `onboarding.eventos`, routing key = `type`, una cola durable por consumidor. UI en `:15672`. Credenciales `RABBITMQ_USER` / `RABBITMQ_PASSWORD`. |
| Lenguajes nuevos | Tomada | Cuatro en total: Java (empleados y perfiles), Go (departamentos), Node (Gateway y vacaciones), Python (notificaciones). Perfiles → Java 21 + Spring Boot. Vacaciones → Node.js 22 + Express. Notificaciones → Python 3.12 + FastAPI. Sin C# ni PHP. |
| Bases nuevas | Tomada | Un Postgres 16 por servicio (`database-perfiles`, `database-notificaciones`, `database-vacaciones`), cada uno con su volumen. Migraciones: Liquibase (perfiles), Alembic (notificaciones), node-pg-migrate (vacaciones). |
| Validar empleado en vacaciones | Tomada | Réplica por `empleado.creado` / `empleado.retirado` (opción b del PDF). Un retirado no admite período nuevo (400), un id desconocido tampoco (400). |
| Baja de empleado | Tomada | `DELETE` → `RETIRADO` + `fechaRetiro` UTC. Segundo DELETE → 400 y sin segundo evento. El PUT no cambia el estado a `RETIRADO`. |
| Catálogo de eventos | Tomada | [`catalogo-de-eventos.pdf`](catalogo-de-eventos.pdf). El envelope no cambió. `empleado.creado` se quedó igual. `empleado.actualizado` ya no lleva `numeroEmpleado`, `fechaIngreso` ni `estado`. `empleado.retirado` lleva `motivo` (por defecto `RENUNCIA`) y no lleva nombre ni `estado`. `vacaciones.programadas` usa `vacacionesId`, `email` y `diasHabiles`. |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `PLAN-RETO4.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada (falta el catálogo oficial, no se pudo capturar la
   deduplicación), anótalo en "Notas" en vez de dejarla en 🔄 silenciosamente.
4. La deduplicación no se marca ✅ solo porque la tabla exista: el PDF pide ver el mismo
   mensaje publicado dos veces desde la UI y un solo efecto.
