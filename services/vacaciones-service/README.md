# vacaciones-service

Programa períodos de vacaciones y publica `vacaciones.programadas`. Node.js 22, Express y
PostgreSQL propio. No comparte proceso con el Gateway.

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
| `GET` | `/health` | 200 solo si PostgreSQL responde y el consumidor está conectado |
| `GET` | `/openapi.json` | OpenAPI estático |

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

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8085` | Puerto interno. No se publica al host |
| `DATABASE_URL` | `postgresql://vacaciones:vacaciones@database-vacaciones:5432/vacaciones_db` | Base propia |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic |
| `BROKER_QUEUE` | `q.vacaciones` | Bindings: `empleado.creado`, `empleado.retirado` |

El esquema lo aplica node-pg-migrate al arrancar (`migrations/001_inicial.cjs`). El archivo es CommonJS porque el migrador carga las revisiones con `require`. El `down` borra las cuatro tablas.

## Pruebas

```bash
npm test
```
