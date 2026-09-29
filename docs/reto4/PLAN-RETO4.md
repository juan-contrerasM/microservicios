# Plan de implementación — Reto 4: Comunicación Asincrónica y Eventos

Basado en [`docs/reto4/reto4.pdf`](reto4.pdf). Este documento traza el plan en etapas para que el
equipo pueda repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](STATUS.md). El Reto 3 queda cerrado; no reabrir etapas de
[`docs/reto3/PLAN-RETO3.md`](../reto3/PLAN-RETO3.md) salvo que un cambio de este reto lo exija
(el `docker-compose.yml` raíz y las rutas nuevas del Gateway).

El PDF dice en el contexto «en este tercer reto». El título del documento es **Reto 4**. Se
implementa el título: broker, publicación de eventos, baja lógica y los tres servicios nuevos.

## Qué hay que resolver (y qué no)

El sistema de Reto 3 entra por un solo puerto y sobrevive a la caída de departamentos. Sigue
siendo síncrono de punta a punta: quien llama espera a quien responde. Este reto cambia eso
para el onboarding.

1. **Un alta tiene que disparar trabajo en otros dominios sin que empleados los conozca.**
   Crear un empleado persiste la fila y, después, publica `empleado.creado`. Perfiles y
   notificaciones reaccionan solos. Eso es el fan-out: un evento, varias colas.
2. **Retirar no es borrar.** `DELETE /empleados/{id}` pasa el estado a `RETIRADO`, guarda
   `fechaRetiro` y publica `empleado.retirado`. La fila se queda para la auditoría.
3. **Vacaciones origina su propio evento.** Programar un período publica
   `vacaciones.programadas`. Notificaciones confirma las fechas. Vacaciones no sabe nada de
   correos. La desactivación de la cuenta el día que empiezan las vacaciones es el evento
   `vacaciones.iniciadas` y **no entra en este reto** (la arma el scheduler del Reto 5).

## Decisiones de arquitectura ya tomadas

Estas decisiones fijan las reglas del juego para todas las etapas. Si alguien quiere cambiarlas,
que lo discuta con el equipo antes de tocar el compose raíz.

| Decisión | Elección | Por qué |
|---|---|---|
| Message broker | **RabbitMQ 3** (`rabbitmq:3-management`) | El PDF obliga a comparar al menos tres opciones y a poder republicar un mensaje desde una UI de administración (paso 9 del §6). RabbitMQ trae esa UI, exchanges y colas durables, y el fan-out es un exchange topic con una cola por consumidor. Kafka exige otra UI y un modelo de particiones que este volumen no necesita. Redis Streams y NATS no traen una consola para publicar el mismo envelope dos veces, que es la evidencia de deduplicación que puntúa el criterio 3. |
| Topología | Exchange topic `onboarding.eventos`. Routing key = `type` del evento. Una cola durable por consumidor | Un solo `basic.publish` llega a todas las colas ligadas a esa key. Cada servicio confirma su propia cola: si notificaciones se cae, perfiles sigue consumiendo. |
| Colas y bindings | `q.notificaciones` ← `empleado.creado`, `empleado.retirado`, `vacaciones.programadas`. `q.perfiles` ← `empleado.creado`, `empleado.actualizado`, `empleado.retirado`. `q.vacaciones` ← `empleado.creado`, `empleado.retirado` | Es exactamente la lista de eventos que el PDF asigna a cada servicio, más la réplica de vacaciones (decisión de abajo). |
| Puertos del broker | `5672` y `15672` publicados al host | Son puertos de infraestructura, no una segunda puerta de negocio. El §1.4 del Reto 3 sigue vigente para las APIs: el único puerto de negocio es `8080`. La UI `:15672` es la que pide el paso 9. AMQP en `:5672` permite inspeccionar desde el host; los servicios hablan por `message-broker:5672` dentro de la red. |
| Credenciales | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` por entorno. URL `amqp://...@message-broker:5672/` en cada servicio | Nada hardcodeado. El compose las inyecta. |
| Publicar vs. transacción | Commit de la BD **primero**. Si el publish falla, se registra el error y **no** se revierte la fila | Lo dice el PDF en las consideraciones del §2. El outbox transaccional no es de este reto (el mecanismo formal de reentrega, DLQ y backoff es el Reto 29). |
| Ack del consumidor | Manual. Ack después de persistir el efecto, o después de descartar un `id` ya visto. Si el procesamiento falla, no se confirma: el broker reentrega | Sin DLQ en este reto. Un mensaje venenoso puede reentregarse; se documenta como límite conocido. |
| Deduplicación | Tabla `eventos_procesados(id, procesado_en)` en **cada** consumidor, clave primaria el `id` del envelope | El PDF lo exige en notificaciones y el mismo riesgo existe en perfiles y en la réplica de vacaciones. Mismo `id` → se descarta y se confirma, sin segundo efecto. |
| Lenguaje de `notificaciones-service` | **Python 3.12 + FastAPI + pika** | Distinto de Java y de Go, y distinto de los otros dos servicios nuevos. Es el consumidor puro: REST de consulta + un hilo que consume la cola. OpenAPI sale de FastAPI. |
| Lenguaje de `perfiles-service` | **Java 21 + Spring Boot + Spring AMQP** | El mismo lenguaje que empleados, en otra carpeta y con su propia base. REST y consumidor van en el mismo proceso (`@RabbitListener`). OpenAPI con Springdoc. No hace falta un quinto lenguaje. |
| Lenguaje de `vacaciones-service` | **Node.js 22 + Express + amqplib** | El mismo lenguaje que el Gateway, en otra carpeta. Express sirve la API y el mismo proceso consume la réplica. OpenAPI estático. No hace falta PHP. |
| Cuatro lenguajes, no seis | **Java, Go, Node y Python** | El curso pide al menos cuatro. Java se repite en perfiles, Node en vacaciones y el Gateway se queda sin reglas de dominio. Notificaciones aporta el cuarto: Python. C# y PHP no entran. |
| Bases de datos nuevas | Un contenedor **PostgreSQL 16** propio por servicio, con su volumen | El PDF pide base propia, no un motor distinto. La persistencia poliglota ya quedó cerrada en el Reto 2 (Postgres + MySQL). Compartir el contenedor de `database-empleados` estaría prohibido. |
| Esquema | Changelogs con rollback, nunca el auto-DDL del framework como mecanismo definitivo | Python → **Alembic**. Perfiles (Java) → **Liquibase**, changelog propio, no el de empleados. Vacaciones (Node) → **node-pg-migrate** (`up`/`down`). En empleados, un changeset Liquibase nuevo para `fecha_retiro` (la columna `estado` ya es `VARCHAR`). |
| Puertos internos | perfiles `8083`, notificaciones `8084`, vacaciones `8085`. Solo `expose:` | Son los del diagrama del PDF. Ninguno publica puerto al host. Se alcanzan por `http://localhost:8080/perfiles`, `/notificaciones` y `/vacaciones`. |
| Validar que el empleado existe (vacaciones) | **Réplica local por eventos** (opción b del PDF) | La opción (a) repite el cliente HTTP del Reto 2 y tumba el alta de vacaciones si empleados está caído. La (b) mantiene el servicio autónomo: consume `empleado.creado` y `empleado.retirado` y guarda una tabla mínima. El costo es consistencia eventual: hay que esperar a que el alta del empleado se consuma antes de programar vacaciones. Es el intercambio que el criterio 5 pide justificar. |
| Empleado retirado en la réplica | Sigue en la tabla, con estado `RETIRADO`. Programar vacaciones para ese id responde **400** | La validación 4 del PDF es «no existe». Un retirado sí existió. Rechazarlo es regla de dominio (no se programan vacaciones de quien ya salió) y no borra la fila, así la auditoría no se confunde con un id desconocido. |
| `PENDIENTE_VALIDACION` | También publica `empleado.creado` | El PDF dice publicar después de persistir con éxito. Un 201 con fallback del Reto 3 es una fila real. El campo `estado` viaja en `data` para que los consumidores no asuman `ACTIVO`. |
| `EN_VACACIONES` | No se transiciona en este reto | El enum ya existe en Java. Las transiciones por fecha y `vacaciones.iniciadas` son del Reto 5. |
| Mailhog / SMTP | Fuera del camino crítico | El PDF lo marca como bonus. El entregable es el log `[NOTIFICACIÓN]` y la fila en la base de notificaciones. |

## Catálogo de eventos (contrato de trabajo)

El catálogo oficial está en [`catalogo-de-eventos.pdf`](catalogo-de-eventos.pdf). El envelope
no cambia. Las cargas `data` de abajo son las de ese catálogo. No se publican otros tipos de
evento en este reto: `usuario.*`, `cuenta.*` y el scheduler de vacaciones son de un reto
posterior.

Envelope, igual para los cuatro tipos:

```json
{
  "id": "6f1c0e2a-7b3d-4e1a-9c2f-0a1b2c3d4e5f",
  "type": "empleado.creado",
  "version": 1,
  "occurredAt": "2026-09-26T14:00:00Z",
  "producer": "empleados-service",
  "data": {}
}
```

| Campo | Regla |
|---|---|
| `id` | UUID. Es la clave de deduplicación. Republicar el mismo `id` no puede repetir el efecto. |
| `type` | `empleado.creado`, `empleado.actualizado`, `empleado.retirado` o `vacaciones.programadas`. |
| `version` | Entero `1`. |
| `occurredAt` | ISO-8601 en UTC, momento del hecho de negocio. |
| `producer` | `empleados-service` o `vacaciones-service`. |
| `data` | Carga de abajo. Un consumidor ignora campos que no usa. |

`empleado.creado` (`producer`: `empleados-service`). Incluye `estado`, que puede ser
`PENDIENTE_VALIDACION` cuando el alta se guardó con el circuito abierto:

```json
{
  "empleadoId": "E001",
  "nombre": "Juan",
  "apellido": "Pérez",
  "email": "juan.perez@empresa.com",
  "numeroEmpleado": "EMP-2026-001",
  "cargo": "Desarrollador Senior",
  "area": "Tecnología",
  "departamentoId": "IT",
  "fechaIngreso": "2026-03-01",
  "estado": "ACTIVO"
}
```

`empleado.actualizado`. Solo los campos que el catálogo replica. No viajan `numeroEmpleado`,
`fechaIngreso` ni `estado`:

```json
{
  "empleadoId": "E001",
  "nombre": "Juan",
  "apellido": "Pérez Gómez",
  "email": "juan.perez@empresa.com",
  "cargo": "Tech Lead",
  "area": "Tecnología",
  "departamentoId": "IT"
}
```

`empleado.retirado`. El `DELETE` sin cuerpo publica `motivo` `RENUNCIA`, el ejemplo del
catálogo. Un cuerpo `{"motivo":"..."}` reemplaza ese valor.

```json
{
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "fechaRetiro": "2026-11-30T16:45:00Z",
  "motivo": "RENUNCIA"
}
```

`vacaciones.programadas` (`producer`: `vacaciones-service`). El id del período es
`vacacionesId` y el evento trae `email` y `diasHabiles`. No desactiva ninguna cuenta.

```json
{
  "vacacionesId": "V-2026-0042",
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "fechaInicio": "2026-03-15",
  "fechaFin": "2026-03-30",
  "diasHabiles": 12
}
```

Perfiles, al recibir `empleado.creado`, copia `nombre` y `email` al perfil. Al recibir
`empleado.actualizado`, vuelve a copiar esos dos campos y no pisa teléfono, dirección, ciudad
ni biografía. Notificaciones no consume `empleado.actualizado`. El aviso de vacaciones usa el
`email` del propio evento; si no viene, el destinatario guardado en el alta.

## Arquitectura objetivo

```
Cliente HTTP (curl / Postman)
        │
        │  única URL de negocio: http://localhost:8080
        ▼
┌───────────────────┐   ports 8080
│    api-gateway    │   /empleados  /departamentos
│  Node.js/Express  │   /perfiles   /notificaciones  /vacaciones
└─────────┬─────────┘
          │  expose, nunca ports
   ┌──────┼──────────┬──────────────────┐
   ▼      ▼          ▼                  ▼
empleados  departamentos  perfiles     notificaciones    vacaciones
 :8080      :8081         :8083         :8084             :8085
 Java        Go            Java          Python            Node.js
   │ publica                │ consume      │ consume         │ publica
   │ empleado.*             │ empleado.*   │ tres eventos    │ vacaciones.programadas
   └────────────┬───────────┴──────────────┴─────────────────┘
                ▼
        RabbitMQ  (5672 AMQP, 15672 management)
                │
                ▼
 db-empleados   db-departamentos   db-perfiles   db-notificaciones   db-vacaciones
 Postgres       MySQL              Postgres      Postgres            Postgres
```

`localhost:8083`, `:8084` y `:8085` en el host deben rechazar la conexión, igual que `:8081`.

## Etapas

Cada etapa indica qué entrega, de qué depende y qué criterio del PDF cierra. Las etapas 2, 3, 4
y 5 pueden avanzar en paralelo: no comparten carpeta. El compose raíz y el Gateway se tocan en
la Etapa 1 (broker) y en la Etapa 6 (rutas). No mezclar esos dos cambios en el mismo PR si hay
dos personas.

### Etapa 0 — Plan, STATUS y colección de pruebas

- Este archivo, [`STATUS.md`](STATUS.md) y
  [`Reto4.postman_collection.json`](Reto4.postman_collection.json).
- La colección usa `{{gateway_url}}` = `http://localhost:8080`. Las carpetas 0–7 se corren en
  orden, con el sistema sano. La 8 es manual (republicar desde la UI de RabbitMQ). La 9
  comprueba que los puertos nuevos no están publicados.
- Las fechas de vacaciones de la colección son de **2027**. Los `curl` del PDF usan junio de
  2026; con el reloj del curso ya en septiembre de 2026 esa `fechaInicio` cae en el pasado y la
  validación 2 respondería 400 en el camino feliz.

No depende de nada. Es el contrato: no inventar rutas, códigos ni nombres de evento distintos
a los de aquí y del PDF.

### Etapa 1 — Message broker en el compose — criterio 1 (0.5 pts)

Solo infraestructura. Todavía no hay productores ni consumidores de negocio.

- Servicio `message-broker` con `rabbitmq:3-management`.
- Usuario y contraseña por entorno. Volumen para `/var/lib/rabbitmq` de modo que las colas
  durables sobrevivan a `docker compose down` (sin `-v`).
- `healthcheck` con `rabbitmq-diagnostics -q ping`.
- Puertos `5672` y `15672` al host. Documentar en `.env.example`:
  `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_PORT`, `RABBITMQ_MANAGEMENT_PORT`.
- Al levantar, la UI responde en `http://localhost:15672`. El exchange y las colas pueden
  declararlos los propios servicios al arrancar (idempotente). No hace falta crearlos a mano
  para que el broker se considere sano.
- En el README raíz, la comparación corta RabbitMQ / Kafka / Redis Streams / NATS y por qué
  gana RabbitMQ. El texto largo puede vivir aquí; el README tiene que llevar la justificación
  porque el criterio 1 la puntúa.

Depende de la Etapa 0. No bloquea el código de los servicios, sí el `up` integrado.

### Etapa 2 — Empleados publica eventos y la baja es lógica — criterio 2 (1.0 pts)

Solo `services/empleados-service/`, más las variables de broker que el compose le inyecte en
la Etapa 6. Se puede desarrollar con un RabbitMQ local.

- Dependencia `spring-boot-starter-amqp`. URL, usuario, exchange y routing por entorno.
- Changeset Liquibase `fecha_retiro` (`TIMESTAMP`, nullable) con rollback que elimina la
  columna. El JSON sigue exponiendo `fechaRetiro`. `estado` no cambia de tipo.
- `POST /empleados` exitoso (201, sea `ACTIVO` o `PENDIENTE_VALIDACION`): después del commit,
  publica `empleado.creado`. Si el publish falla, la fila queda y el error queda en el log.
- `PUT /empleados/{id}` (nuevo). Actualización parcial de `nombre`, `apellido`, `email`,
  `numeroEmpleado`, `cargo`, `area`, `departamentoId`, `fechaIngreso`. El `id` no se cambia.
  Siguen las unicidades de email y `numeroEmpleado` (400). Si cambia `departamentoId`, se
  vuelve a validar contra departamentos con el cliente y el Circuit Breaker que ya existen.
  No se usa este PUT para pasar a `RETIRADO`. Respuesta 200 con el empleado. Después del
  commit, `empleado.actualizado`. 404 si el id no existe, con el mensaje que ya usa el
  servicio: `El empleado con id {id} no existe`.
- `DELETE /empleados/{id}` (nuevo). No borra la fila. Pone `estado = RETIRADO` y
  `fechaRetiro = now` (UTC). Respuesta **200** con el empleado. Publica `empleado.retirado`.
  Si ya estaba `RETIRADO`, responde **400** `{"mensaje":"El empleado con id {id} ya está retirado"}`
  y **no** publica otro evento. 404 si no existe.
- Auditoría, de solo lectura, sobre el `GET /empleados` que ya existe:
  - `GET /empleados?estado=RETIRADO` → solo retirados, cada uno con `fechaRetiro`.
  - `GET /empleados?estado=RETIRADO&desde=AAAA-MM-DD&hasta=AAAA-MM-DD` → `fechaRetiro`
    dentro del rango, ambos extremos inclusive, comparando la fecha (no la hora).
  - Sin query params, el listado sigue devolviendo todos, como en el Reto 2.
- `GET /empleados/{id}` de un retirado responde 200 con `estado: RETIRADO` y `fechaRetiro`.
  No desaparece.
- Tests unitarios del publicador: se invoca después de guardar; un fallo del broker no lanza
  hacia el cliente que ya recibió el 201/200; un segundo DELETE no publica.

Depende de la Etapa 0. Puede ir en paralelo con las Etapas 3, 4 y 5.

### Etapa 3 — `notificaciones-service` (Python) — criterio 3 (1.0 pts)

Carpeta `services/notificaciones-service/`. No lo llama nadie por REST. Solo consume y deja
consultar el historial.

- Consumidor de `q.notificaciones`, ack manual, prefetch 1.
- Antes de efecto: si `id` está en `eventos_procesados`, ack y return.
- Efectos:
  - `empleado.creado` → fila `BIENVENIDA` y log
    `[NOTIFICACIÓN] Tipo: BIENVENIDA | Para: {email} | Mensaje: "Bienvenido {nombre} {apellido}"`.
    Guarda el destinatario (`empleadoId`, email, nombre, apellido) para los eventos que no
    traen email.
  - `empleado.retirado` → `DESVINCULACION` y log
    `[NOTIFICACIÓN] Tipo: DESVINCULACION | Para: {email} | Mensaje: "Su cuenta ha sido desvinculada"`.
  - `vacaciones.programadas` → usa el `email` del evento. Si no viene, el destinatario guardado
    en el alta. Si tampoco está, registra el error, confirma el mensaje y no inventa
    destinatario: el `id` queda procesado para no bloquear la cola. Si hay email, fila
    `VACACIONES` y log
    `[NOTIFICACIÓN] Tipo: VACACIONES | Para: {email} | Mensaje: "Sus vacaciones del {fechaInicio} al {fechaFin} han sido programadas"`.
- El `tipo` persistido es `BIENVENIDA`, `DESVINCULACION` o `VACACIONES` (sin acento: así está
  el esquema del PDF).
- REST, detrás del Gateway:

  | Método | Ruta | Respuesta |
  |---|---|---|
  | `GET` | `/notificaciones` | 200, arreglo (vacío si no hay) |
  | `GET` | `/notificaciones/{empleadoId}` | 200, arreglo de ese empleado (vacío si no hay). No es 404 |
  | `GET` | `/health` | 200 solo si la BD responde. Lo usa el healthcheck de Docker, no el Gateway |

- Cuerpo de una notificación, tal como el PDF:

  ```json
  {
    "id": "uuid",
    "tipo": "BIENVENIDA",
    "destinatario": "juan.perez@empresa.com",
    "mensaje": "Bienvenido Juan Pérez",
    "fechaEnvio": "2026-09-26T14:00:01Z",
    "empleadoId": "E001"
  }
  ```

- Alembic, Dockerfile, README con variables (`PORT`, `DATABASE_URL`, `BROKER_URL`).
- OpenAPI en `/docs` (FastAPI).
- Prueba de deduplicación automatizable en el módulo: el mismo envelope dos veces deja una
  sola fila. La evidencia de la UI queda para la Etapa 7.

Depende de la Etapa 0. En paralelo con 2, 4 y 5.

### Etapa 4 — `perfiles-service` (Java 21 / Spring Boot) — criterio 4 (1.0 pts)

Carpeta `services/perfiles-service/`. Consume y expone REST. Repite Java; no comparte carpeta,
runtime ni base con `empleados-service`.

- Consumidor de `q.perfiles` dentro del mismo proceso (`@RabbitListener`), mismas reglas de ack
  y de `eventos_procesados`.
- `empleado.creado`: inserta el perfil por defecto. `id` del perfil lo genera el servicio.
  `archivado` nace en `false`. Teléfono, dirección, ciudad y biografía nacen en `""`.

  ```json
  {
    "id": "uuid",
    "empleadoId": "E001",
    "nombre": "Juan",
    "email": "juan.perez@empresa.com",
    "telefono": "",
    "direccion": "",
    "ciudad": "",
    "biografia": "",
    "fechaCreacion": "2026-09-26T14:00:01Z",
    "archivado": false
  }
  ```

  `archivado` no está en el ejemplo del PDF y hace falta para el paso 10 (`GET` debe mostrar
  el perfil archivado, no un 404). Nace en false y pasa a true con `empleado.retirado`.
- `empleado.actualizado`: actualiza `nombre` y `email`. No toca los campos que edita RRHH por
  REST ni `archivado`.
- `empleado.retirado`: `archivado = true`. No se borra la fila.
- REST:

  | Método | Ruta | Respuesta |
  |---|---|---|
  | `GET` | `/perfiles/{empleadoId}` | 200 con el perfil, o 404 `{"mensaje":"El perfil del empleado {id} no existe"}` |
  | `PUT` | `/perfiles/{empleadoId}` | 200 con el perfil ya actualizado. Campos editables: `telefono`, `direccion`, `ciudad`, `biografia`. Un campo ausente no se borra. 404 si no hay perfil |
  | `GET` | `/perfiles` | 200, arreglo |
  | `GET` | `/health` | 200 si la BD responde |

- El PUT no publica eventos. Perfiles no es productor en este reto.
- Liquibase propio, Dockerfile, README, Swagger con Springdoc en `/swagger-ui.html`.
- Tests: creado inserta uno; el mismo `id` de evento no inserta dos; actualizado no pisa el
  teléfono; retirado archiva.

Depende de la Etapa 0. En paralelo con 2, 3 y 5.

### Etapa 5 — `vacaciones-service` (Node.js 22 / Express) — criterio 5 (1.0 pts)

Carpeta `services/vacaciones-service/`. Es productor. La réplica de empleados es el consumidor
de `q.vacaciones`. Repite Node; no comparte carpeta ni proceso con `api-gateway`.

- `POST /vacaciones` con `{ "empleadoId", "fechaInicio", "fechaFin" }`. El id lo genera el
  servicio con la forma `V-{año}-{secuencia de 4 dígitos}` (el ejemplo del PDF es
  `V-2026-0042`). Estado inicial `PROGRAMADA`. `fechaCreacion` en UTC. Respuesta **201** con
  el período. Después del commit, publica `vacaciones.programadas`.
- Validaciones, todas **400** con `{"mensaje":"..."}` salvo el solapamiento, que además trae
  el período:

  | Caso | Mensaje |
  |---|---|
  | `fechaFin` no es posterior a `fechaInicio` | `La fechaFin debe ser posterior a la fechaInicio` |
  | `fechaInicio` anterior a hoy (fecha del servidor, UTC) | `La fechaInicio no puede ser anterior a la fecha actual` |
  | Cruce con otro período `PROGRAMADA` o `EN_CURSO` del mismo empleado | `El empleado ya tiene un período que se solapa con las fechas solicitadas` y el objeto `periodoEnConflicto` |
  | `empleadoId` ausente de la réplica | `El empleado con id {id} no existe` |
  | `empleadoId` en la réplica con estado `RETIRADO` | `El empleado con id {id} está retirado` |

  Un período `CANCELADA` o `FINALIZADA` no cuenta como solapamiento.
- `GET /vacaciones/{id}` → 200, o 404 `{"mensaje":"El período de vacaciones con id {id} no existe"}`.
- `GET /vacaciones?empleadoId={id}` → 200, arreglo (vacío si no hay).
- `GET /vacaciones` → 200, arreglo.
- `DELETE /vacaciones/{id}` → transición a `CANCELADA`, la fila se queda. 200 con el período.
  Solo si `estado` es `PROGRAMADA` y `fechaInicio` es posterior a hoy. Si no, 400
  `{"mensaje":"Solo se puede cancelar un período PROGRAMADA que aún no ha iniciado"}`.
  No publica evento: el PDF solo pide `vacaciones.programadas`.
- Réplica: `empleado.creado` inserta (`empleadoId`, `estado`); `empleado.retirado` actualiza
  el estado. Deduplicación por `id` del envelope. Misma regla de no revertir si el publish
  del período falla.
- Un solo proceso: Express en `8085` y el consumidor de la réplica arrancan juntos. Si el
  consumidor se cae, `/health` deja de responder UP.
- node-pg-migrate, Dockerfile, README con la justificación disponibilidad (réplica) frente a
  consistencia inmediata (REST a empleados). OpenAPI estático servido por Express.
- Tests de las cuatro validaciones y de que un `CANCELADA` no bloquea un rango nuevo.

Depende de la Etapa 0. En paralelo con 2, 3 y 4. El flujo completo necesita la Etapa 2
porque la réplica se llena con eventos reales de empleados; los tests de unidad pueden
sembrar la réplica a mano.

### Etapa 6 — Gateway y compose: los tres servicios detrás del borde

No tiene fila propia en la rúbrica. Sin esta etapa el criterio de «punto de entrada único»
del Reto 3 se rompe y el §6 del PDF no se puede ejecutar.

- Rutas nuevas en `api-gateway`, sin strip, mismo 503 JSON de hoy
  (`status`, `mensaje`, `servicio`):

  | Ruta externa | Destino |
  |---|---|
  | `/perfiles` y `/perfiles/*` | `http://perfiles-service:8083` |
  | `/notificaciones` y `/notificaciones/*` | `http://notificaciones-service:8084` |
  | `/vacaciones` y `/vacaciones/*` | `http://vacaciones-service:8085` |

- `GET /health` del Gateway sigue sin consultar backends.
- `depends_on` del Gateway hacia los tres: `service_started`, no `service_healthy`, igual que
  con empleados y departamentos.
- Cada servicio nuevo: `expose` del puerto interno, `depends_on` su Postgres
  (`service_healthy`) y el broker (`service_healthy`). Healthcheck contra su `/health`.
- Variables de broker y de BD solo por entorno. Actualizar `.env.example`.
- Los tres servicios de negocio nuevos **no** llevan `ports:`.

Depende de las Etapas 1 y de los Dockerfile de las Etapas 3, 4 y 5. La de empleados (Etapa 2)
tiene que estar cableada aquí también (`BROKER_URL` en `empleados-service`).

### Etapa 7 — Pruebas, evidencias y README — criterio 6 (0.5 pts)

Depende de las Etapas 1 a 6.

- Recorrer el §6 del PDF con la colección de este directorio (Newman en las carpetas
  automáticas). El orden está en la descripción de
  [`Reto4.postman_collection.json`](Reto4.postman_collection.json).
- README raíz, que es entregable del PDF:
  1. Por qué RabbitMQ y no Kafka, Redis Streams o NATS.
  2. Tabla servicio ↔ lenguaje ↔ motor de BD ↔ puerto interno. Mínimo cuatro lenguajes; este
     reto deja cuatro: Java (empleados y perfiles), Go (departamentos), Node (Gateway y
     vacaciones) y Python (notificaciones).
  3. Cómo levantar todo con `docker compose up --build` y dónde está la UI del broker.
  4. Eventos implementados, con el envelope y las cargas de este plan, citados como contrato
     del catálogo.
  5. Por qué vacaciones valida al empleado con réplica y no con REST, y qué pasa en la
     ventana en que el evento todavía no se consumió.
  6. Evidencia de deduplicación: el mismo `empleado.creado` (mismo `id`) publicado dos veces
     desde la UI deja **una** notificación y **un** perfil.
  7. Pasos para repetir el flujo asíncrono, con fechas futuras (no las de junio 2026 del PDF
     si esa fecha ya pasó).
- READMEs de empleados, perfiles, notificaciones y vacaciones: endpoints, variables, y el
  «por qué» de la decisión que les toca.
- Swagger de cada servicio nuevo responde dentro de la red. Desde el host se llega al de
  negocio solo si más adelante se proxea; no publicar puertos para ver la UI.
- Evidencias que hay que guardar (captura o nota en un `EVIDENCIAS.md` de esta carpeta, al
  estilo del Reto 3):

  | # | Qué | Cómo se ve |
  |---|---|---|
  | 1 | Fan-out del alta | `POST /empleados` y, sin otro POST, `GET /perfiles/E001` y `GET /notificaciones/E001` con `BIENVENIDA`. Log `[NOTIFICACIÓN]` en el contenedor. |
  | 2 | Validaciones de vacaciones | Los cuatro 400 de la carpeta 5, y el solapamiento con `periodoEnConflicto`. |
  | 3 | Deduplicación | Dos publishes manuales del mismo envelope; un solo perfil y una sola bienvenida. |
  | 4 | Baja lógica | `DELETE /empleados/E001` deja la fila `RETIRADO` con `fechaRetiro`, el filtro por fechas la encuentra, la notificación es `DESVINCULACION` y el perfil tiene `archivado: true`. |
  | 5 | Persistencia | `docker compose down` y `up` de nuevo: perfiles, notificaciones y períodos siguen. |

- Actualizar [`STATUS.md`](STATUS.md) en el mismo commit que cierre cada etapa.

## Contrato de respuestas que no debe romperse

Lo del Reto 3 sigue igual (201 `ACTIVO`, 400 de duplicado, 400 de departamento inexistente,
201 `PENDIENTE_VALIDACION` con el circuito abierto, 503 del Gateway). Se suma:

| Situación | Código | Quién |
|---|---|---|
| Alta persistida | 201 y, después, evento `empleado.creado` | empleados |
| `PUT /empleados/{id}` | 200 y evento `empleado.actualizado` | empleados |
| `DELETE /empleados/{id}` la primera vez | 200, `RETIRADO`, `fechaRetiro`, evento `empleado.retirado` | empleados |
| `DELETE` de un empleado ya retirado | 400, sin segundo evento | empleados |
| `GET /empleados?estado=RETIRADO` | 200, arreglo | empleados |
| Perfil aún no consumido | 404 en `GET /perfiles/{id}` | perfiles |
| Perfil creado por el evento | 200, `archivado: false` | perfiles |
| `PUT /perfiles/{id}` | 200 | perfiles |
| Perfil tras `empleado.retirado` | 200, `archivado: true` | perfiles |
| `GET /notificaciones/{empleadoId}` sin filas | 200, `[]` | notificaciones |
| `POST /vacaciones` válido | 201, `PROGRAMADA`, y evento `vacaciones.programadas` | vacaciones |
| Las cuatro validaciones de vacaciones | 400 | vacaciones |
| `DELETE /vacaciones/{id}` de uno futuro y `PROGRAMADA` | 200, `CANCELADA` (la fila sigue) | vacaciones |
| Gateway sin un backend nuevo | 503 JSON | Gateway |
| `curl` a `:8083`, `:8084` o `:8085` en el host | conexión rechazada | Docker |

Los GET de perfil y de notificaciones son asíncronos: la colección reintenta unos segundos.
Un 404 inmediato después del `POST /empleados` no es un fallo; lo es si sigue el 404 al
agotar la espera.

## Criterios de evaluación ↔ etapas

| # | Elemento | Pts | Etapa |
|---|---|---|---|
| 1 | Broker en el compose, UI, justificación frente a las alternativas | 0.5 | 1 + 7 |
| 2 | `empleado.creado` / `actualizado` / `retirado` con el envelope. Baja lógica, `fechaRetiro`, auditoría por fechas | 1.0 | 2 |
| 3 | Notificaciones: tres eventos, historial, log, REST, OpenAPI, deduplicación demostrada | 1.0 | 3 + 7 |
| 4 | Perfiles: creado, sincronizado y archivado. REST y OpenAPI | 1.0 | 4 |
| 5 | Vacaciones: CRUD, cuatro validaciones, `vacaciones.programadas`, estrategia justificada, OpenAPI | 1.0 | 5 + 7 |
| 6 | Flujo completo, tabla de lenguajes (mínimo 4), README, Swagger | 0.5 | 7 |

La Etapa 6 no suma puntos por sí sola y sin ella no se puede demostrar el flujo por
`localhost:8080`.

## Fuera de alcance de Reto 4 (a propósito)

- `vacaciones.iniciadas`, scheduler y el paso automático `PROGRAMADA → EN_CURSO → FINALIZADA`
  → Reto 5. En este reto el período nace `PROGRAMADA` y solo sale de ahí si se cancela.
- Pasar al empleado a `EN_VACACIONES` → Reto 5.
- JWT en el Gateway, cuentas y auth-service → Reto 5. Por eso el envelope no se inventa a
  medias: ese servicio va a consumir estos mismos tipos.
- Inbox pattern, DLQ y backoff exponencial del consumidor → Reto 29.
- SMTP real (Mailhog) → bonus, no etapa.
- Outbox transaccional. Si el broker se cae después del commit, el evento se pierde y queda
  el log. Es lo que pide el PDF.
- Publicar los puertos 8083/8084/8085 «para ver Swagger».
- Lógica de negocio nueva dentro del Gateway. Solo enruta.
