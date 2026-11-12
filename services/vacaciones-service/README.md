# vacaciones-service

## Ficha del servicio (plantilla C6)

1. **Propósito:** períodos de vacaciones (programar, modificar, cancelar) y scheduler que los inicia
   y finaliza, publicando los eventos que suspenden y reactivan la cuenta.
2. **Tecnología:** Node.js 22 + Express + `pg` + PostgreSQL 16; `node-cron` para el scheduler.
3. **Endpoints** (Swagger: `/swagger/index.html`, contrato: [`openapi.yaml`](openapi.yaml)):

   | Método y ruta | Descripción |
   |---|---|
   | `GET /vacaciones?empleadoId=` · `GET /vacaciones/{id}` | Listado y consulta |
   | `POST /vacaciones` | Programar (fechas coherentes, futuras, sin solapamiento, empleado activo) |
   | `PUT /vacaciones/{id}` · `DELETE /vacaciones/{id}` | Modificar o cancelar un período futuro |
   | `POST /vacaciones/{id}/forzar-inicio` · `/forzar-fin` | Transiciones manuales (solo ADMIN) |
   | `GET /health` · `GET /metrics` | Salud C2 y métricas |
4. **Eventos:** publica `vacaciones.programadas`, `vacaciones.iniciadas`, `vacaciones.finalizadas`;
   consume `empleado.creado` y `empleado.retirado` (réplica local de empleados).
5. **Configuración:** `PORT`, `DB_*`, `DB_PASSWORD_FILE`, `BROKER_URL`, `RABBITMQ_*`,
   `BROKER_QUEUE` (`q.vacaciones`), `VACACIONES_CRON`, `HEALTH_TIMEOUT_MS`, `OTEL_*`.
6. **Migraciones:** node-pg-migrate (`migrations/`), cada una con `down`, aplicadas al arrancar.
7. **Pruebas:** `npm test` sobre todo `src/` salvo `index.js` y `telemetria.js` (arranque):
   JUnit y LCOV en `reports/`, umbral 70 %. Cobertura actual 82,67 %. Integración:
   `npm run test:integration` con PostgreSQL y RabbitMQ reales.
8. **Observabilidad:** prom-client, logs JSON C5, OpenTelemetry (http, pg, amqplib). Negocio:
   `vacaciones_programadas_total`, `vacaciones_iniciadas_total`, `vacaciones_finalizadas_total` y
   `eventos_*_total{tipo}`.
9. **Resiliencia y escalado:** consumidor con deduplicación, reintentos y DLQ; publicación tras el
   commit; el scheduler toma un `pg_try_advisory_lock` por tick (`src/lock.js`), así que con varias
   réplicas solo una ejecuta las transiciones.
10. **Decisiones:** días hábiles calculados al programar; el scheduler y los endpoints `forzar-*`
    comparten la misma transición para que la demostración use el código de producción.


Programa períodos de vacaciones y publica `vacaciones.programadas`. Desde el Reto 5 también
observa el paso del tiempo: un scheduler inicia y finaliza los períodos y publica
`vacaciones.iniciadas` y `vacaciones.finalizadas`. Node.js 22, Express y PostgreSQL propio.
No comparte proceso con el Gateway.

## Por qué una réplica y no un GET a empleados

Consultar empleados por HTTP en cada alta daría el estado del momento, pero el período no se
puede crear si empleados está caído. La réplica local (`empleado.creado` y `empleado.retirado`
en `q.vacaciones`) deja este servicio disponible aunque empleados no responda. El costo es que
el retiro puede tardar unos segundos en verse: hasta que llega el evento, un período nuevo
todavía pasa. Por eso el test de un retirado reintenta.

`empleado.actualizado` no se consume. El email del evento de vacaciones es el que llegó en el
alta. Si el correo cambia después, el aviso usa el de la réplica.

### Eventos y resiliencia (Proyecto final, Fase 4)

La política común de los cuatro consumidores está en [`docs/eventos.md`](../../docs/eventos.md).

**Como consumidor** (`q.vacaciones`, `src/consumidor.js`):

- **Envelope validado antes de abrir la base.** Si no es JSON, falta alguno de `id`, `type`,
  `version`, `occurredAt`, `producer`, `data`, o el `type` no es `empleado.creado` /
  `empleado.retirado`, se hace `reject` sin requeue y el mensaje queda en `q.vacaciones.dlq`.
- **Deduplicación atómica.** La consulta y la inserción en `eventos_procesados` y el cambio en
  `empleados_replica` van entre el mismo `BEGIN`/`COMMIT`.
- **Reintento solo a esta cola.** Un fallo (incluida una base que no acepta conexiones) hace
  `ROLLBACK`, republica en `q.vacaciones.retry` por el exchange por defecto con
  `x-retry-count + 1` y los headers originales (`traceparent` incluido), espera la confirmación
  del broker (canal de confirmaciones) y recién entonces confirma el original. A los 5 s vuelve
  a `q.vacaciones`. Antes volvía al topic con la routing key del evento y se re-difundía a
  Auth, Perfiles y Notificaciones. Con `x-retry-count` = 3 el siguiente fallo va a la DLQ.
- **Nunca un mensaje colgado.** Con prefetch 1, un mensaje sin ack ni reject bloquea la cola:
  todos los caminos terminan en `ack` o `reject`. Antes, un `pool.connect()` fallido dejaba el
  mensaje sin respuesta.
- **Arranque con colas antiguas.** Si una cola existe con otros argumentos
  (`PRECONDITION_FAILED`) se recrea solo si está vacía; si no, el consumidor sigue reintentando
  la conexión con un error que remite a la sección Migración de `docs/eventos.md`.

**Como productor** (`src/publicar.js`): `vacaciones.programadas` (alta) y
`vacaciones.iniciadas`/`vacaciones.finalizadas` (scheduler y POST de desarrollo) se publican
después del `COMMIT`, con el envelope C1 completo y `messageId` = `id`. El canal es de
confirmaciones: un `nack` o un canal cerrado se convierte en error que se registra sin revertir el
período. `traceparent` lo inyecta la instrumentación OTel de amqplib.

## Días hábiles

`diasHabiles` cuenta lunes a viernes entre `fechaInicio` y `fechaFin`, ambos inclusive. No resta
festivos. Del 15 al 30 de marzo de 2027 son 12.

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/vacaciones` | 201, estado `PROGRAMADA`. Después del commit publica el evento. Si el broker falla, el período queda |
| `GET` | `/vacaciones/{id}` | 200, o 404 con `message` `El período de vacaciones con id {id} no existe` |
| `GET` | `/vacaciones?empleadoId={id}` | 200, arreglo (vacío si no hay) |
| `GET` | `/vacaciones` | 200, arreglo |
| `DELETE` | `/vacaciones/{id}` | 200 y `CANCELADA` si está `PROGRAMADA` y `fechaInicio` es posterior a hoy (UTC). Si no, 400. No publica evento |
| `POST` | `/vacaciones/{id}/forzar-inicio` | **Desarrollo, solo ADMIN.** 200 y `EN_CURSO`, publica `vacaciones.iniciadas`. 400 `Solo se puede forzar el inicio de un período PROGRAMADA` si no está `PROGRAMADA`. 404 si no existe |
| `POST` | `/vacaciones/{id}/forzar-fin` | **Desarrollo, solo ADMIN.** 200 y `FINALIZADA`, publica `vacaciones.finalizadas`. 400 `Solo se puede forzar el fin de un período EN_CURSO` si no está `EN_CURSO`. 404 si no existe |
| `PUT` | `/vacaciones/{id}` | 200 con las fechas nuevas y `diasHabiles` recalculado si está `PROGRAMADA` y no ha iniciado. 400 por fechas, estado o solapamiento; 404 si no existe |
| `GET` | `/health` | 200 si PostgreSQL responde y el consumidor está conectado; 503 si no, en menos de 3 s |
| `GET` | `/openapi.json` | Especificación OpenAPI |
| `GET` | `/swagger/index.html` | Swagger UI. `/swagger` redirige ahí |

El cuerpo de un período es `id` (`V-{año}-{secuencia de 4 dígitos}`, el año es el de
`fechaInicio`), `empleadoId`, `fechaInicio`, `fechaFin`, `estado`, `fechaCreacion` y
`diasHabiles`.

Un 400 de solapamiento trae además `periodoEnConflicto`. Un `CANCELADA` o `FINALIZADA` no
bloquea otro período en las mismas fechas.

El evento, según el catálogo:

```json
{
  "vacacionesId": "V-2027-0001",
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "fechaInicio": "2027-03-15",
  "fechaFin": "2027-03-30",
  "diasHabiles": 12
}
```

`fechaFin` puede ser igual a `fechaInicio` (un período de un día, el paso 12 del PDF). Una
`fechaFin` anterior responde 400 con `message` `La fechaFin no puede ser anterior a la fechaInicio`.

## Contrato HTTP (Fase 5 del proyecto final)

**Health (C2).** `GET /health` responde `{"status","service","version","checks":{"database","messageBroker"}}`;
`version` sale de `package.json`. Cada check corre con su propio tiempo máximo
(`HEALTH_TIMEOUT_MS`, 2 s, `salud.js`) y el endpoint tiene una guarda externa de 2,5 s. Por qué:
`pg` no trae timeout de conexión por defecto, así que con PostgreSQL colgado `SELECT 1` podía no
resolver nunca y el healthcheck de Docker se quedaba esperando en vez de ver un 503.

**Errores (C3).** `errores.js` (`manejarError` y `rutaNoEncontrada`) responde todo error como
`{"timestamp","status","error","message","path","traceId"}`. El servicio sigue lanzando
`ErrorApi(status, {mensaje, ...})`; el middleware pasa `mensaje` a `message` y deja los demás campos
como extensión (el 400 de solapamiento conserva `periodoEnConflicto`). JSON mal formado → 400,
ruta inexistente → 404, PostgreSQL caído (`ECONNREFUSED`, SQLSTATE `08xxx`/`57P0x`, timeout del
pool) → 503, cualquier otra excepción → 500 `Error interno del servidor`. La traza de pila solo va
al log. `traceId` es el del span activo de OpenTelemetry o, si no hay, el del header `traceparent`.
Las respuestas 2xx no cambian.

**OpenAPI (E08).** `src/openapi.json` es la fuente que sirve la app (la imagen solo copia `src/`).
Cada operación documenta ejemplos y todos sus códigos: 400 con un ejemplo por validación (fechas,
fecha pasada, empleado inexistente, retirado, solapamiento con `periodoEnConflicto`, JSON inválido),
404, 500 y 503, más 401 y 403 que responde el Gateway. `BearerAuth` es global y `/health` lo anula.
[`openapi.yaml`](openapi.yaml) es la copia exportada: `npm run openapi:export` la regenera y
`tests/contrato.test.js` falla si difiere de `GET /openapi.json` o si el contrato no es un OpenAPI
válido (`@apidevtools/swagger-parser`). `yaml` y `swagger-parser` son `devDependencies`: la imagen
(`npm ci --omit=dev`) no las instala.

**Migraciones (E05).** `001_inicial.cjs` tiene `down`. La unicidad vive en las PK: período por `id`,
réplica por `empleado_id`, secuencia por año y evento procesado por `id` (deduplicación). No se
agrega `UNIQUE` al email de la réplica: la llenan eventos y un `UNIQUE` haría fallar el consumidor
con eventos desordenados. El solapamiento de períodos lo valida el servicio dentro de la transacción.
`tests/migraciones.test.js` ejecuta `up` y `down` contra un `pgm` falso, sin PostgreSQL.

## Scheduler (Reto 5)

En el Reto 4 un período nacía `PROGRAMADA` y solo salía de ahí si se cancelaba: nada miraba el
reloj, y la cuenta del empleado nunca se suspendía. Ahora `src/scheduler.js` programa con
[`node-cron`](https://www.npmjs.com/package/node-cron) la función `ejecutarCiclo` de
`src/transiciones.js`:

1. Los `PROGRAMADA` con `fechaInicio` ≤ hoy pasan a `EN_CURSO` y publican
   `vacaciones.iniciadas`.
2. Los `EN_CURSO` con `fechaFin` **anterior** a hoy pasan a `FINALIZADA` y publican
   `vacaciones.finalizadas`.

"Hoy" es la fecha UTC, igual que en el alta. El día de `fechaFin` todavía es de vacaciones: el
período termina al día siguiente. Si no fuera así, un período de un solo día nacería y moriría
en el mismo disparo. Un período atrasado (el servicio estuvo apagado) inicia y finaliza en el
mismo ciclo, en ese orden.

Por qué `node-cron`: el servicio ya es Node y el PDF lo propone para Node. Acepta la sintaxis
cron estándar, así que la frecuencia se cambia por entorno sin tocar código. La opción
`noOverlap` evita que un disparo empiece si el anterior no terminó.

| Variable | Default | Descripción |
|---|---|---|
| `VACACIONES_CRON` | `* * * * *` | Cada minuto, para desarrollo. En producción bastaría una vez al día, por ejemplo `5 0 * * *`. Una expresión inválida hace fallar el arranque |

Cada transición es un `UPDATE ... WHERE estado = ... RETURNING` dentro de una transacción. Si el
job corre dos veces, el segundo `UPDATE` ya no encuentra la fila y no publica otra vez. El evento
se publica **después** del commit: si el broker falla, el período ya cambió de estado y el error
queda en el log (misma regla del Reto 4 para `vacaciones.programadas`).

Las cargas son las del catálogo (secciones 3.9 y 3.10), sin `diasHabiles`:

```json
{ "vacacionesId": "V-2027-0001", "empleadoId": "E001", "email": "juan.perez@empresa.com", "fechaInicio": "2027-03-15", "fechaFin": "2027-03-30" }
```

```json
{ "vacacionesId": "V-2027-0001", "empleadoId": "E001", "email": "juan.perez@empresa.com", "fechaFin": "2027-03-30" }
```

### Cómo probarlo sin esperar días

El PDF ofrece tres estrategias. Se usan dos:

- **Cron cada minuto con fechas de hoy.** `POST /vacaciones` con `fechaInicio` = `fechaFin` = hoy:
  en menos de un minuto el período pasa a `EN_CURSO` y la cuenta se suspende. Con fechas de solo
  día, el fin llega mañana.
- **`POST /vacaciones/{id}/forzar-inicio` y `forzar-fin`.** Disparan la misma transición y el
  mismo evento que el cron, sin esperar. Están marcados como endpoints de desarrollo en el
  OpenAPI (tag `Desarrollo`) y el Gateway los permite solo al rol `ADMIN`. La colección del
  Reto 5 programa con fechas de **mañana** para que el cron no se adelante a estos POST.

### Limitación conocida: N instancias

`node-cron` corre dentro de cada proceso. Con una instancia, funciona. Si el servicio se escala a
N réplicas (Reto 11), las N ejecutan el job en cada disparo. En este diseño el `UPDATE ...
WHERE estado = 'PROGRAMADA' RETURNING` es atómico: si dos réplicas compiten por la misma fila,
PostgreSQL bloquea la segunda, vuelve a evaluar el `WHERE` y no la devuelve, así que la
transición y su evento salen una vez. Eso es suerte del diseño, no coordinación: las N réplicas
igual consultan la base cada minuto, y cualquier cambio que lea primero y actualice después (o
que publique antes de confirmar) volvería a disparar el evento N veces, con N correos y N
desactivaciones.

Las dos defensas son complementarias. La deduplicación por `id` en los consumidores
(`eventos_procesados` en auth y notificaciones) mitiga un evento repetido, pero no evita el
trabajo duplicado, y dos publicaciones del mismo período llevarían `id` distintos. La solución
de fondo es coordinar en el productor: un lock distribuido en base de datos para que una sola
instancia ejecute el job (ShedLock, Reto 31). Este reto acepta una sola instancia.

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8085` | Puerto interno. No se publica al host |
| `DATABASE_URL` | `postgresql://vacaciones:vacaciones@database-vacaciones:5432/vacaciones_db` | Base propia |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic |
| `BROKER_QUEUE` | `q.vacaciones` | Bindings: `empleado.creado`, `empleado.retirado` |
| `VACACIONES_CRON` | `* * * * *` | Frecuencia del scheduler (ver arriba) |
| `HEALTH_TIMEOUT_MS` | `2000` (default) | Tiempo máximo de cada check de `/health` |

El esquema lo aplica node-pg-migrate al arrancar (`migrations/001_inicial.cjs`). El archivo es CommonJS porque el migrador carga las revisiones con `require`. El `down` borra las cuatro tablas.

## Pruebas (Reto 6)

### Fase 8 — cobertura, integración y mutación (proyecto final)

| Aspecto | Valor |
|---|---|
| Comando | `npm test` (JUnit + LCOV en `reports/`, falla bajo 70 %) sobre **todo** `src/` salvo `index.js` y `telemetria.js` (arranque) |
| Cobertura | 115 pruebas · 82,27 % de líneas |
| Integración (E14) | `npm run test:integration` (`tests/integracion/`): node-pg-migrate up/down/up, consumidor real, CRUD HTTP, scheduler y eventos leídos de RabbitMQ (`bash scripts/test-integration.sh vacaciones`) |
| Mutación (E17) | `validar.js`: aceptar `fechaFin < fechaInicio` → `tests/validar.test.js` falla (`bash scripts/test-mutation.sh vacaciones`) |

Todas son unitarias: corren con el runner de Node 22 (`node --test`), sin Docker, sin Postgres
y sin RabbitMQ. El repositorio, el pool y el publicador se sustituyen por dobles en memoria
(`crearServicio` y `crearTransiciones` reciben `repo` por parámetro).

```bash
npm install   # una vez
npm test
```

| Reporte | Ruta | Formato |
|---|---|---|
| Resultados | `services/vacaciones-service/reports/junit.xml` | JUnit XML |
| Cobertura | `services/vacaciones-service/reports/lcov.info` | LCOV (lo lee SonarQube en el Reto 7) |

`npm test` crea `reports/`, imprime el resumen en consola y sale con código distinto de cero si
falla una prueba **o** si la cobertura de líneas baja del 70 % (`--test-coverage-lines=70`).

| Archivo | Qué cubre |
|---|---|
| `tests/validar.test.js` | Tabla de reglas de fechas de `evaluarAlta` (orden, pasado, hoy, mismo día), empleado inexistente o retirado, conflicto, cancelación |
| `tests/fechas.test.js` | Tabla de **solapamiento** (empieza dentro, termina dentro, contiene, contenido, idéntico, se toca por un día, adyacente), qué estados bloquean, tabla de días hábiles, `parseFecha`, `hoyUtc` |
| `tests/servicio.test.js` | `programar` y `cancelar` con dobles: el camino feliz publica; solapamiento, retirado, inexistente y fechas inválidas responden 400, hacen `ROLLBACK` o ni abren transacción, y **no publican** |
| `tests/transiciones.test.js` | Scheduler y `forzar-*`: inicia una sola vez aunque el job corra dos veces, no finaliza el día de `fechaFin`, no toca `CANCELADA`, 400/404 fuera de estado, broker caído no revierte |
| `tests/openapi.test.js` | El contrato declara `BearerAuth` y deja `/health` público |
| `tests/contrato.test.js` | Health 200/503 y < 3 s con la dependencia colgada, errores C3 (404, 400 con `periodoEnConflicto`, JSON inválido, 503, 500 sin pila), drift y validez del OpenAPI |
| `tests/migraciones.test.js` | Cada migración exporta `up`/`down`, `down` borra lo que crea `up`, PK donde hay unicidad |

**Qué se mide y qué no.** La cobertura se limita a la lógica de dominio: `validar.js`,
`fechas.js`, `servicio.js` y `transiciones.js`, más `errores.js` y `salud.js` (contrato HTTP). Quedan fuera, a propósito, `app.js` (cableado
de Express: rutas → servicio), `db.js`, `config.js`, `index.js`, `scheduler.js` (solo programa
`node-cron` y llama a `ejecutarCiclo`, que sí está probado), `consumidor.js`, `publicar.js` y
`repositorio.js`. Son código de integración: probarlos con dobles solo comprobaría que el doble
responde lo que se le dijo. Su lugar es una prueba de integración o la suite BDD.

**Cobertura alta no es la meta.** Cada prueba afirma el mensaje, el código o el evento exacto,
no solo que «no explotó». Para comprobarlo se invirtió a propósito la regla
`fechaFin < fechaInicio` de `validar.js`: 17 pruebas fallaron, entre ellas
`fechas: fin un día antes del inicio se rechaza`, con el mensaje
`evaluarAlta(2027-06-15 → 2027-06-14, hoy 2026-09-29) debía rechazar con «La fechaFin no puede
ser anterior a la fechaInicio»`, y `npm test` salió con código 1.

**`fechaFin` igual a `fechaInicio`.** El PDF del Reto 6 escribe `fechaFin > fechaInicio`; este
servicio acepta el período de un día desde el Reto 5 (el paso 12 de ese PDF lo exige). Las
pruebas fijan el comportamiento real: igual se acepta, anterior se rechaza.

**Riesgo conocido: el solapamiento se decide en SQL.** En producción el conflicto lo devuelve
`buscarConflicto` (`fecha_inicio <= $fin AND fecha_fin >= $inicio`, solo `PROGRAMADA` y
`EN_CURSO`). La tabla de casos prueba `seSolapan` y `periodoQueBloquea`, que hoy replican esa
condición. Si alguien cambia una sin la otra, las unitarias siguen en verde. Solo una prueba de
integración contra Postgres (o la BDD) detecta esa divergencia.

## Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `deps` | `node:22.21.1-alpine3.22` | `npm ci --omit=dev` sobre `package-lock.json` |
| `runtime` | `node:22.21.1-alpine3.22` | `node`, `curl`, `node_modules` de producción, `package.json`, `migrations/` y `src/` |

- **Usuario:** `app` con UID/GID fijos `10001:10001` (`USER 10001:10001`). Código y migraciones
  con dueño `root`, de solo lectura para el proceso; el servicio no escribe en disco (las
  migraciones se aplican con la API programática de node-pg-migrate y su estado vive en la tabla
  `pgmigrations`).
- **Por qué multi-stage:** las devDependencies y la caché de npm nunca llegan a `runtime`; ahí
  además se borran `npm`, `npx`, `corepack` y `yarn`, que no se usan para arrancar. Menos tamaño
  y menos paquetes que reporte el escáner de imagen.
- **Por qué no root:** el proceso comprometido no puede reescribir su código, sus migraciones ni
  instalar paquetes. El UID numérico fijo debe poder leer `/run/secrets/*` (ver
  `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Base fijada:** tag completo de Node y Alpine, para builds reproducibles.
- `curl` se mantiene porque el healthcheck de Compose ejecuta `curl -f http://localhost:8085/health`.
- `.dockerignore` deja fuera `node_modules`, `tests`, `reports`, `coverage`, `.env` y `secrets`.
