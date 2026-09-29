# notificaciones-service

Consumidor de la cola `q.notificaciones`. No lo llama nadie para crear avisos: registra la
traza cuando llegan eventos y deja consultarla. Python 3.12, FastAPI, PostgreSQL propio y pika.

## Por qué estas tres trazas

El catálogo oficial dice que el correo de bienvenida sale con `usuario.creado` y el de
despedida con `cuenta.desactivada`. Esos eventos son del reto de autenticación, que todavía
no existe. Este reto pide la traza en el alta, el retiro y las vacaciones programadas:

| Evento | Fila | Log |
|---|---|---|
| `empleado.creado` | `BIENVENIDA` | `[NOTIFICACIÓN] Tipo: BIENVENIDA \| Para: {email} \| Mensaje: "Bienvenido {nombre} {apellido}"` |
| `empleado.retirado` | `DESVINCULACION` | `[NOTIFICACIÓN] Tipo: DESVINCULACION \| Para: {email} \| Mensaje: "Su cuenta ha sido desvinculada"` |
| `vacaciones.programadas` | `VACACIONES` | `[NOTIFICACIÓN] Tipo: VACACIONES \| Para: {email} \| Mensaje: "Sus vacaciones del {fechaInicio} al {fechaFin} han sido programadas"` |

`empleado.actualizado` no se consume: el catálogo lo asigna solo a perfiles. Si el email
cambia, un aviso de vacaciones usa el email que trae el propio evento. Si ese campo no viene,
se usa el destinatario guardado en el alta.

## Destinatario desconocido

`vacaciones.programadas` del catálogo trae `email`. Si no viene y tampoco hay un alta previa
de ese `empleadoId`, se registra el error, se confirma el mensaje y no se inventa destinatario.
El `id` queda en `eventos_procesados` para no reencolarlo y bloquear la cola. No se crea fila.

## Deduplicación

Antes de cualquier efecto se busca el `id` del envelope en `eventos_procesados`. Si ya está,
se confirma y no se repite la fila ni el log. La columna `procesado_en` es el `procesadoEn`
del catálogo. El ack es manual y el prefetch es 1: solo se confirma después de persistir, o
después de descartar un duplicado. Un fallo de base de datos no confirma: el broker reentrega.

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| `GET` | `/notificaciones` | 200, arreglo (vacío si no hay) |
| `GET` | `/notificaciones/{empleadoId}` | 200, arreglo de ese empleado. Vacío no es 404 |
| `GET` | `/health` | 200 solo si PostgreSQL responde. Lo usa Docker, no el Gateway |
| `GET` | `/docs` | OpenAPI de FastAPI |

El cuerpo de cada aviso es `id`, `tipo`, `destinatario`, `mensaje`, `fechaEnvio`, `empleadoId`.

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8084` | Puerto interno. No se publica al host |
| `DATABASE_URL` | `postgresql+psycopg://...@database-notificaciones:5432/notificaciones_db` | Base propia |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic que también declara este servicio |
| `BROKER_QUEUE` | `q.notificaciones` | Cola durable. Bindings: `empleado.creado`, `empleado.retirado`, `vacaciones.programadas` |

El esquema lo crea Alembic al arrancar (`alembic upgrade head`), con downgrade que borra las tres tablas.

## Pruebas

```bash
pip install -r requirements.txt pytest
pytest
```

El test de unidad cubre que el mismo `id` deja una sola bienvenida. La corrida contra el broker
está en [`docs/reto4/EVIDENCIAS.md`](../../docs/reto4/EVIDENCIAS.md): el mismo envelope publicado
dos veces deja una sola fila.
