# Estado de implementación — Reto 4

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`PLAN-RETO4.md`](PLAN-RETO4.md). Enunciado: [`reto4.pdf`](reto4.pdf).

El Reto 3 está cerrado; su estado histórico vive en [`docs/reto3/STATUS.md`](../reto3/STATUS.md).
No reabrir esas etapas salvo el `docker-compose.yml` raíz y las rutas nuevas del Gateway.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS y colección Postman en `docs/reto4/` (URL de negocio `http://localhost:8080`) | ✅ | Contrato listo. Importar [`Reto4.postman_collection.json`](Reto4.postman_collection.json). Las carpetas automáticas fallarán hasta que existan broker, eventos y los tres servicios. |
| 1 | RabbitMQ en `docker-compose.yml` (AMQP + management UI), healthcheck, credenciales por entorno, justificación frente a Kafka / Redis Streams / NATS | ⬜ | Criterio 1. |
| 2 | `empleados-service`: publish de `empleado.creado`, `empleado.actualizado` y `empleado.retirado`. `PUT`, `DELETE` lógico con `fechaRetiro`, auditoría `?estado=RETIRADO` | ⬜ | Criterio 2. Liquibase solo agrega `fecha_retiro`. El fallo del publish no revierte la fila. |
| 3 | `notificaciones-service` (Python): consume los tres eventos, log `[NOTIFICACIÓN]`, historial, deduplicación por `id`, OpenAPI | ⬜ | Criterio 3. |
| 4 | `perfiles-service` (Java 21 / Spring Boot): perfil por defecto, sincroniza nombre/email, archiva al retirar, REST y OpenAPI | ⬜ | Criterio 4. Mismo lenguaje que empleados, carpeta y base propias. |
| 5 | `vacaciones-service` (Node.js 22 / Express): CRUD, cuatro validaciones, publica `vacaciones.programadas`, réplica local de empleados | ⬜ | Criterio 5. Mismo lenguaje que el Gateway, carpeta propia. |
| 6 | Gateway: `/perfiles`, `/notificaciones`, `/vacaciones` con `expose:` y 503 JSON. Compose de los tres servicios y sus Postgres | ⬜ | Sin puntaje propio. Sin esto el flujo del PDF no entra por `:8080`. |
| 7 | README raíz (broker, tabla de lenguajes, eventos, réplica), evidencias del §6 incluida la deduplicación, Newman de la colección | ⬜ | Criterio 6. No se cierra solo con código: hace falta la evidencia de la UI del broker. |

## Estado de entrega

Solo la **Etapa 0** está cerrada. No hay broker ni servicios nuevos en el compose. El tráfico
de negocio sigue entrando por `http://localhost:8080` hacia empleados y departamentos, como
quedó en el Reto 3.

Cuando las etapas 1 a 6 cierren, el flujo se prueba importando la colección y ejecutando las
carpetas en orden. La carpeta 8 (deduplicación) y la 9 (puertos no publicados) son manuales.

## Decisiones técnicas del enunciado

Registrar aquí la versión corta. El "por qué" largo está en `PLAN-RETO4.md` y, al cerrar la
etapa, debe copiarse al README que el PDF pide. Mientras la etapa no cierre, queda como
"propuesta".

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Message broker | Propuesta | RabbitMQ 3 management. Exchange topic `onboarding.eventos`, routing key = `type`, una cola durable por consumidor. UI en `:15672`. |
| Lenguajes nuevos | Propuesta | Cuatro en total: Java (empleados y perfiles), Go (departamentos), Node (Gateway y vacaciones), Python (notificaciones). Perfiles → Java 21 + Spring Boot. Vacaciones → Node.js 22 + Express. Notificaciones → Python 3.12 + FastAPI. Sin C# ni PHP. |
| Bases nuevas | Propuesta | Un Postgres 16 por servicio (`database-perfiles`, `database-notificaciones`, `database-vacaciones`), cada uno con su volumen. Migraciones: Liquibase (perfiles), Alembic (notificaciones), node-pg-migrate (vacaciones). |
| Validar empleado en vacaciones | Propuesta | Réplica por `empleado.creado` / `empleado.retirado` (opción b del PDF). Un retirado no admite período nuevo (400), un id desconocido tampoco (400). |
| Baja de empleado | Propuesta | `DELETE` → `RETIRADO` + `fechaRetiro`. Segundo DELETE → 400 y sin segundo evento. |
| Catálogo de eventos | Propuesta, con hueco | El envelope (`id`, `type`, `version`, `occurredAt`, `producer`, `data`) lo fija el PDF. El archivo `catalogo-de-eventos.md` no está en el repo; las cargas `data` son las del plan. Si llega el catálogo oficial y un campo no coincide, se corrige el plan y la colección antes de cerrar la Etapa 2. |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `PLAN-RETO4.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada (falta el catálogo oficial, no se pudo capturar la
   deduplicación), anótalo en "Notas" en vez de dejarla en 🔄 silenciosamente.
4. La deduplicación no se marca ✅ solo porque la tabla exista: el PDF pide ver el mismo
   mensaje publicado dos veces desde la UI y un solo efecto.
