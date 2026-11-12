# Eventos del ecosistema

Referencia operativa de la mensajería (Proyecto final, Fase 4, estándar E11/E12). La fuente
normativa de nombres, productores, consumidores y cargas sigue siendo
[`docs/reto4/catalogo-de-eventos.pdf`](reto4/catalogo-de-eventos.pdf); si algo aquí la
contradice, prevalece el catálogo. El contrato del envelope es C1 de
[`PLAN-PROYECTO-FINAL.md`](proyecto-final/PLAN-PROYECTO-FINAL.md).

`departamentos-service` y `api-gateway` no producen ni consumen eventos (N/A).

## Topología

| Pieza | Nombre | Tipo / argumentos |
|---|---|---|
| Exchange de eventos | `onboarding.eventos` (`BROKER_EXCHANGE`) | `topic`, durable. Routing key = `type` del envelope |
| Exchange de dead-letter | `onboarding.dlx` | `direct`, durable. Routing key = nombre de la cola de origen |
| Cola de un consumidor | `q.<servicio>` | durable, `x-dead-letter-exchange: onboarding.dlx`, `x-dead-letter-routing-key: q.<servicio>` |
| Cola de reintento | `q.<servicio>.retry` | durable, `x-message-ttl: 5000`, `x-dead-letter-exchange: ""` (exchange por defecto), `x-dead-letter-routing-key: q.<servicio>`. **Sin bindings** |
| Cola de mensajes muertos | `q.<servicio>.dlq` | durable, enlazada a `onboarding.dlx` con routing key `q.<servicio>` |

Las cinco colas de consumidor son `q.auth`, `q.perfiles`, `q.notificaciones`, `q.vacaciones` y `q.empleados` (proyecto final: estado `EN_VACACIONES` del empleado).
Cada servicio declara sus tres colas al arrancar (idempotente); los productores solo declaran el
exchange `onboarding.eventos`.

```text
productor ──type──▶ onboarding.eventos (topic) ──binding──▶ q.X ──▶ consumidor X
                                                             ▲ │
                       (TTL 5 s, DLX "" → q.X) q.X.retry ────┘ │ fallo: publica a "" / q.X.retry
                                                               │ poison o reintentos agotados: reject
                                         onboarding.dlx ◀──────┘
                                               │ q.X
                                               ▼
                                            q.X.dlq
```

## Envelope (C1)

```json
{ "id": "3f9b2c10-8e4a-4d6b-9f21-7c0a5d8e1234", "type": "empleado.creado", "version": 1,
  "occurredAt": "2026-03-01T14:32:05Z", "producer": "empleados-service", "data": {} }
```

- Los seis campos son obligatorios. `id` es un UUID del **mensaje** (no de la entidad) y es la
  clave de deduplicación. `version` es un entero ≥ 1 (hoy todos los eventos son versión `1`).
  `occurredAt` es ISO-8601 en UTC.
- Propiedades AMQP: `content_type: application/json`, entrega persistente (`delivery_mode 2`) y
  `message_id` = `id` del envelope.
- Headers AMQP: contexto W3C (`traceparent`, `tracestate`, `baggage`). Lo inyecta la
  instrumentación OpenTelemetry de cada productor (agente Java en Empleados, `PikaInstrumentor`
  en Auth, auto-instrumentación de amqplib en Vacaciones) y lo extrae la del consumidor.
- Nunca viajan contraseñas, hashes, secretos ni JWT de acceso. Los tokens de activación y
  recuperación solo en `usuario.creado` y `usuario.recuperacion`, y nunca se escriben al log.

## Catálogo

| Evento | Versión | Productor | Consumidores (cola) | Cuándo se publica |
|---|---|---|---|---|
| `empleado.creado` | 1 | empleados-service | Auth (`q.auth`), Perfiles (`q.perfiles`), Notificaciones (`q.notificaciones`), Vacaciones (`q.vacaciones`, réplica local) | `POST /empleados` 201, después del commit |
| `empleado.actualizado` | 1 | empleados-service | Perfiles (`q.perfiles`) | `PUT /empleados/{id}` 200, después del commit |
| `empleado.retirado` | 1 | empleados-service | Auth, Perfiles, Notificaciones, Vacaciones (réplica local) | Primer `DELETE /empleados/{id}`, después del commit |
| `usuario.creado` | 1 | auth-service | Notificaciones | Al consumir `empleado.creado` y crear la cuenta, después del commit |
| `usuario.recuperacion` | 1 | auth-service | Notificaciones | `POST /auth/recover-password` de una cuenta pendiente o activa |
| `cuenta.activada` | 1 | auth-service | Notificaciones | Primer `reset-password` o `vacaciones.finalizadas` sobre una suspendida, después del commit |
| `cuenta.desactivada` | 1 | auth-service | Notificaciones | `vacaciones.iniciadas` sobre una activa o `empleado.retirado`, después del commit |
| `vacaciones.programadas` | 1 | vacaciones-service | Notificaciones | `POST /vacaciones` 201, después del commit |
| `vacaciones.iniciadas` | 1 | vacaciones-service (scheduler) | Auth, Notificaciones, Empleados (`ACTIVO → EN_VACACIONES`) | `PROGRAMADA → EN_CURSO`, después del commit |
| `vacaciones.finalizadas` | 1 | vacaciones-service (scheduler) | Auth, Notificaciones, Empleados (`EN_VACACIONES → ACTIVO`) | `EN_CURSO → FINALIZADA`, después del commit |

Exchange de todos: `onboarding.eventos`. Routing key de todos: el propio `type`.

### Bindings por cola

| Cola | Routing keys |
|---|---|
| `q.auth` | `empleado.creado`, `empleado.retirado`, `vacaciones.iniciadas`, `vacaciones.finalizadas` |
| `q.perfiles` | `empleado.creado`, `empleado.actualizado`, `empleado.retirado` |
| `q.empleados` | `vacaciones.iniciadas`, `vacaciones.finalizadas` |
| `q.notificaciones` | `empleado.creado`, `empleado.retirado`, `usuario.creado`, `usuario.recuperacion`, `cuenta.activada`, `cuenta.desactivada`, `vacaciones.programadas`, `vacaciones.iniciadas`, `vacaciones.finalizadas` |
| `q.vacaciones` | `empleado.creado`, `empleado.retirado` |

### Cargas (`data`) de ejemplo

`empleado.creado`

```json
{ "empleadoId": "E001", "nombre": "Juan", "apellido": "Pérez", "email": "juan.perez@empresa.com",
  "numeroEmpleado": "EMP-2026-001", "cargo": "Desarrollador Senior", "area": "Tecnología",
  "departamentoId": "IT", "fechaIngreso": "2026-02-10", "estado": "ACTIVO" }
```

`empleado.actualizado`

```json
{ "empleadoId": "E001", "nombre": "Juan", "apellido": "Pérez Gómez", "email": "juan.perez@empresa.com",
  "cargo": "Tech Lead", "area": "Tecnología", "departamentoId": "IT" }
```

`empleado.retirado`

```json
{ "empleadoId": "E001", "email": "juan.perez@empresa.com", "fechaRetiro": "2026-11-30T16:45:00Z", "motivo": "RENUNCIA" }
```

`usuario.creado`

```json
{ "empleadoId": "E001", "email": "juan.perez@empresa.com", "tokenActivacion": "<jwt de un solo uso>", "expiraEn": "2026-02-10T15:30:00Z" }
```

`usuario.recuperacion`

```json
{ "email": "juan.perez@empresa.com", "tokenRecuperacion": "<jwt de un solo uso>", "expiraEn": "2026-05-14T11:00:00Z" }
```

`cuenta.activada` (`motivo`: `ACTIVACION_INICIAL` | `FIN_VACACIONES`)

```json
{ "empleadoId": "E001", "email": "juan.perez@empresa.com", "motivo": "ACTIVACION_INICIAL" }
```

`cuenta.desactivada` (`VACACIONES` con `permanente: false` | `RETIRO` con `permanente: true`)

```json
{ "empleadoId": "E001", "email": "juan.perez@empresa.com", "motivo": "VACACIONES", "permanente": false }
```

`vacaciones.programadas`

```json
{ "vacacionesId": "V-2026-0042", "empleadoId": "E001", "email": "juan.perez@empresa.com",
  "fechaInicio": "2026-03-15", "fechaFin": "2026-03-30", "diasHabiles": 12 }
```

`vacaciones.iniciadas`

```json
{ "vacacionesId": "V-2026-0042", "empleadoId": "E001", "email": "juan.perez@empresa.com",
  "fechaInicio": "2026-03-15", "fechaFin": "2026-03-30" }
```

`vacaciones.finalizadas`

```json
{ "vacacionesId": "V-2026-0042", "empleadoId": "E001", "email": "juan.perez@empresa.com", "fechaFin": "2026-03-30" }
```

## Política de productores (E11)

1. **Publicar después del commit.** Empleados registra la publicación con
   `TransactionSynchronization.afterCommit()` (equivalente a
   `@TransactionalEventListener(AFTER_COMMIT)`); Auth publica tras `sesion.commit()`; Vacaciones
   tras `COMMIT` en el alta, en el scheduler y en los POST de desarrollo. Un rollback nunca
   anuncia un hecho que no ocurrió.
2. **Un fallo de publicación se registra y no revierte el negocio.** El período, la cuenta o el
   empleado ya quedaron; el error queda en el log. Vacaciones usa canal de confirmaciones, así un
   `nack` del broker también se ve como error.
3. **Limitación conocida (doble escritura).** Si el proceso muere entre el commit y la
   publicación, el evento se pierde. La solución completa sería un *transactional outbox*; queda
   fuera del alcance de la Fase 4.

## Política común de consumidores (E12)

Los cuatro consumidores (Auth y Notificaciones en Python/pika, Perfiles en Java/Spring AMQP,
Vacaciones en Node/amqplib) aplican exactamente lo mismo:

| Situación | Acción | Dónde termina |
|---|---|---|
| Cuerpo no JSON, falta alguno de los seis campos, `version` no es entero ≥ 1, `occurredAt` no es ISO-8601, `data` no es objeto, o `type` no es uno que esa cola consume (**poison**) | `reject` sin requeue, sin tocar la base | `q.X.dlq` (vía `onboarding.dlx`) |
| `id` ya está en `eventos_procesados` (**duplicado**) | Ningún efecto; `ack` | — |
| Procesado bien | Efecto + marca del `id` en la **misma transacción**; `commit`; `ack` | — |
| Fallo (base caída, commit fallido, excepción) con `x-retry-count` < 3 | `rollback`; publica una copia en el exchange por defecto con routing key `q.X.retry`, `x-retry-count + 1` y los headers originales; `ack` del original | `q.X.retry` → 5 s → `q.X` |
| Fallo con `x-retry-count` = 3 (reintentos agotados) | `reject` sin requeue | `q.X.dlq` |
| No se pudo publicar la copia en `q.X.retry` | `reject` sin requeue (nunca se pierde) | `q.X.dlq` |

Detalles:

- **Por qué el reintento vuelve solo a la cola de origen.** Si `q.X.retry` hiciera dead-letter al
  topic `onboarding.eventos` con la routing key del evento (diseño anterior), un fallo en Perfiles
  volvería a entregarse a Auth, Notificaciones y Vacaciones: más carga, y si alguno no deduplicara
  bien, efectos repetidos. Con el exchange por defecto (`""`) y `x-dead-letter-routing-key: q.X`,
  RabbitMQ entrega el mensaje vencido solo a `q.X`.
- **Headers al reintentar.** Se copian todos los del mensaje (`traceparent`, `tracestate`,
  `baggage`, …) salvo `x-death`, `x-first-death-*` y `x-last-death-*`, que gestiona el broker.
  Se añaden `x-retry-count` y, la primera vez, `x-original-routing-key` (la routing key con la
  que llegó; tras un reintento la routing key de entrega es `q.X`). Se conservan `message_id`,
  `content_type` y la entrega persistente.
- **Espera.** Fija de 5 s por reintento (TTL de la cola retry), tres reintentos: un mensaje que
  falla siempre llega a la DLQ en unos 15 s sin bloquear la cola.
- **Deduplicación atómica.** `eventos_procesados(id, procesado_en)` en la base de cada
  consumidor. La consulta, el efecto y la inserción del `id` comparten transacción. Si dos réplicas
  procesan el mismo `id` a la vez, la PK hace fallar el commit de la segunda, que reintenta y
  entonces lo ve como duplicado.
- **Correos (Notificaciones).** La fila y la marca del `id` se confirman primero; el correo sale
  después del commit. Un `id` repetido o un commit fallido nunca envían un segundo correo. Un fallo
  SMTP no reintenta el evento (la notificación ya quedó guardada).
- **Ack manual, `prefetch` 1 y reconexión.** Todos los caminos terminan en `ack` o `reject`. Los
  consumidores Python y Node reintentan la conexión cada 5 s; Spring AMQP recupera la suya. Auth,
  Notificaciones y Vacaciones publican el reintento en un canal con confirmaciones; Perfiles usa el
  canal del listener sin confirmaciones (si `basicPublish` lanza, el mensaje va a la DLQ).

### Operar la DLQ

- Ver: RabbitMQ UI (`http://localhost:15672`) → *Queues* → `q.X.dlq` → *Get messages*. El header
  `x-death` dice de qué cola salió y por qué (`rejected`).
- Reprocesar tras corregir la causa: *Move messages* (plugin shovel) de `q.X.dlq` a `q.X`, o
  republicar el cuerpo con `e2e-tests/scripts/republicar.js`. Si el `id` ya se había procesado,
  el consumidor lo descartará como duplicado.
- Descartar: *Purge* sobre `q.X.dlq`.

### Probarlo a mano

`e2e-tests/fixtures/` trae un `empleado.creado` y un `usuario.creado` con `id` fijo para
republicarlos dos veces, un envelope sin campos obligatorios y un poison no JSON. Ver
[`e2e-tests/README.md`](../e2e-tests/README.md#fixtures-de-la-fase-4-eventos-duplicados-e-inválidos).

## Migración: colas que ya existían con otros argumentos

RabbitMQ no permite cambiar los argumentos (`x-dead-letter-*`, `x-message-ttl`, …) de una cola
durable que ya existe: un `queue.declare` con argumentos distintos responde
`PRECONDITION_FAILED - inequivalent arg` (código 406) y cierra el canal. Afecta a quien tenga un
volumen de RabbitMQ creado antes de esta fase:

- `q.auth`, `q.perfiles`, `q.notificaciones` y `q.vacaciones` creadas antes del commit `ad01d03`
  no tienen `x-dead-letter-*`.
- `q.X.retry` creadas por `ad01d03` hacen dead-letter a `onboarding.eventos` (diseño corregido).
- Quedan huérfanos los exchanges `onboarding.retry.q.X` de `ad01d03`; no hacen daño y se pueden
  borrar desde la UI.

**Qué hace el arranque.** Cada consumidor intenta declarar sus colas; si recibe 406, abre otro
canal, borra la cola **solo si está vacía** (`if-empty`), la vuelve a declarar con los argumentos
vigentes y re-crea los bindings. Si la cola tiene mensajes no la toca (borrarla perdería
eventos): registra un error que remite a esta sección y sigue reintentando (Python y Node cada
5 s; en Perfiles el error queda en el log del arranque y el listener sigue consumiendo con la
cola vieja hasta que se migre).

**Si una cola con mensajes no se pudo migrar**, elegir una de estas opciones:

1. Dejar que el consumidor vacíe la cola vieja, o moverlos: UI → *Queues* → `q.X` → *Move
   messages* a una cola temporal; luego *Delete* `q.X` y `q.X.retry`, reiniciar el servicio
   (`docker compose restart <servicio>`) y devolver los mensajes a `q.X`.
2. Si los mensajes pendientes no importan (entorno de desarrollo): UI → *Queues* → `q.X` →
   *Delete* (y `q.X.retry`), y reiniciar el servicio.
3. Reinicio total del broker, **pierde todos los mensajes pendientes**:

   ```bash
   docker compose down
   docker volume rm microservicios_vol-rabbitmq
   docker compose up -d
   ```

   (`docker compose down -v` también sirve, pero borra además las bases de datos.)
