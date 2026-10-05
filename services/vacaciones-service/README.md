# vacaciones-service

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

## Días hábiles

`diasHabiles` cuenta lunes a viernes entre `fechaInicio` y `fechaFin`, ambos inclusive. No resta
festivos. Del 15 al 30 de marzo de 2027 son 12.

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/vacaciones` | 201, estado `PROGRAMADA`. Después del commit publica el evento. Si el broker falla, el período queda |
| `GET` | `/vacaciones/{id}` | 200, o 404 `{"mensaje":"El período de vacaciones con id {id} no existe"}` |
| `GET` | `/vacaciones?empleadoId={id}` | 200, arreglo (vacío si no hay) |
| `GET` | `/vacaciones` | 200, arreglo |
| `DELETE` | `/vacaciones/{id}` | 200 y `CANCELADA` si está `PROGRAMADA` y `fechaInicio` es posterior a hoy (UTC). Si no, 400. No publica evento |
| `POST` | `/vacaciones/{id}/forzar-inicio` | **Desarrollo, solo ADMIN.** 200 y `EN_CURSO`, publica `vacaciones.iniciadas`. 400 `{"mensaje":"Solo se puede forzar el inicio de un período PROGRAMADA"}` si no está `PROGRAMADA`. 404 si no existe |
| `POST` | `/vacaciones/{id}/forzar-fin` | **Desarrollo, solo ADMIN.** 200 y `FINALIZADA`, publica `vacaciones.finalizadas`. 400 `{"mensaje":"Solo se puede forzar el fin de un período EN_CURSO"}` si no está `EN_CURSO`. 404 si no existe |
| `GET` | `/health` | 200 solo si PostgreSQL responde y el consumidor está conectado |
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
`fechaFin` anterior responde 400 `{"mensaje":"La fechaFin no puede ser anterior a la fechaInicio"}`.

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

El esquema lo aplica node-pg-migrate al arrancar (`migrations/001_inicial.cjs`). El archivo es CommonJS porque el migrador carga las revisiones con `require`. El `down` borra las cuatro tablas.

## Pruebas

```bash
npm test
```

`tests/transiciones.test.js` cubre el scheduler con un repositorio en memoria: un `PROGRAMADA`
que inicia hoy pasa a `EN_CURSO` y publica una sola vez aunque el job corra dos veces; un
`EN_CURSO` cuya `fechaFin` es hoy no finaliza; un `CANCELADA` no se toca; `forzar-fin` publica
`vacaciones.finalizadas`; y un broker caído no revierte el cambio de estado.
