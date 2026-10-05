# notificaciones-service

Consumidor de la cola `q.notificaciones`. No lo llama nadie para crear avisos: registra la
traza cuando llegan eventos y deja consultarla. Python 3.12, FastAPI, PostgreSQL propio y pika.

## Qué evento deja qué fila (Reto 5)

El correo de bienvenida sale con `usuario.creado`, no con `empleado.creado`: es `usuario.creado`
el que trae el token de activación, y sin token el correo no sirve (PDF del Reto 5 y catálogo,
sección 3.4). El adiós es `cuenta.desactivada` con `motivo: RETIRO`. Por eso este servicio ya no
inserta `BIENVENIDA` ni `DESVINCULACION`; las filas viejas del Reto 4 se quedan en la base como
historial.

| Evento | Fila | Mensaje |
|---|---|---|
| `usuario.creado` | `SEGURIDAD` | `Para establecer o restablecer su contraseña ingrese a https://app.empresa.com/reset?token={tokenActivacion}` |
| `usuario.recuperacion` | `SEGURIDAD` | La misma frase, con `tokenRecuperacion` |
| `cuenta.desactivada` | `CUENTA` | `Su cuenta fue desactivada` |
| `cuenta.activada` | `CUENTA` | `Bienvenido de regreso. Su cuenta ha sido reactivada` |
| `vacaciones.programadas` | `VACACIONES` | `Sus vacaciones del {fechaInicio} al {fechaFin} han sido programadas` |
| `vacaciones.iniciadas` | `VACACIONES` | `Sus vacaciones del {fechaInicio} al {fechaFin} han iniciado` |
| `vacaciones.finalizadas` | `VACACIONES` | `Sus vacaciones finalizaron el {fechaFin}` |
| `empleado.creado` | — | Solo guarda el destinatario (email, nombre, apellido) |
| `empleado.retirado` | — | Se confirma sin fila |

Cada fila deja el mismo log del Reto 4:

```
[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: juan.perez@empresa.com | Mensaje: "Para establecer o restablecer su contraseña ingrese a https://app.empresa.com/reset?token=..."
```

El PDF corta las tres frases al margen de la página. Las de esta tabla son las que fija
[`docs/reto5/PLAN-RETO5.md`](../../docs/reto5/PLAN-RETO5.md) y las que busca la colección.
`cuenta.activada` usa la misma frase con `ACTIVACION_INICIAL` y con `FIN_VACACIONES`.

`empleado.actualizado` no se consume: el catálogo lo asigna solo a perfiles.

## Por qué se sigue guardando el destinatario

`usuario.recuperacion` no trae `empleadoId` (catálogo, sección 3.5), y la fila lo necesita para
que `GET /notificaciones/{empleadoId}` la muestre. El `empleadoId` se busca por email en la tabla
`destinatarios` que llena `empleado.creado`. En el flujo real el alta ocurre antes que cualquier
recuperación. La revisión `002_destinatario_email` agrega el índice por email.

Los demás eventos traen `email`. Si no viniera, se usa el destinatario guardado del mismo
`empleadoId`.

## Destinatario desconocido

Si el evento no trae `email` y tampoco hay un alta previa de ese `empleadoId` (o de ese email,
en `usuario.recuperacion`), se registra el error, se confirma el mensaje y no se inventa destinatario.
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
| `GET` | `/docs` | Swagger UI de FastAPI. `/swagger` redirige aquí |
| `GET` | `/openapi.json` | Especificación OpenAPI |

El cuerpo de cada aviso es `id`, `tipo`, `destinatario`, `mensaje`, `fechaEnvio`, `empleadoId`.

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8084` | Puerto interno. No se publica al host |
| `DATABASE_URL` | `postgresql+psycopg://...@database-notificaciones:5432/notificaciones_db` | Base propia |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic que también declara este servicio |
| `BROKER_QUEUE` | `q.notificaciones` | Cola durable. Bindings: `empleado.creado`, `empleado.retirado`, `vacaciones.programadas`, `vacaciones.iniciadas`, `vacaciones.finalizadas`, `usuario.creado`, `usuario.recuperacion`, `cuenta.activada`, `cuenta.desactivada` |
| `SMTP_HOST` | `mailhog` | Servidor SMTP. Vacío: no se envía correo |
| `SMTP_PORT` | `1025` | Puerto SMTP de Mailhog, dentro de la red |
| `SMTP_FROM` | `onboarding@empresa.com` | Remitente |

El esquema lo crea Alembic al arrancar (`alembic upgrade head`). `001_inicial` crea las tres tablas y `002_destinatario_email` el índice por email; los dos tienen downgrade.

## Correo

Mailhog no entrega a internet. La bandeja está en [http://localhost:8025](http://localhost:8025).
El SMTP es el contenedor `mailhog`, puerto `1025`.

`Settings` en `config.py` lee `SMTP_HOST`, `SMTP_PORT` y `SMTP_FROM`. Si el host está vacío, no se envía nada. `consumidor.py` crea el enviador con `crear_enviador` (`correo.py`) y se lo pasa a `procesar`. `_registrar` en `procesar.py` guarda la fila, escribe el log y después llama al enviador. El asunto depende del evento: `Seguridad`, `Cuenta`, `Vacaciones programadas`, `Vacaciones iniciadas` o `Vacaciones finalizadas`. El cuerpo es el mismo `mensaje` de la fila.

Si el SMTP falla, el error queda en el log. La fila no se borra y el evento se confirma. El mismo `id` no manda un segundo correo.

Para verlo: abre la bandeja y haz un `POST /empleados` de un id que todavía no exista; el correo `Seguridad` llega cuando auth-service publica `usuario.creado`. Los avisos ya guardados no se reenvían.

## Pruebas

```bash
pip install -r requirements.txt pytest
pytest
```

Los tests de unidad cubren cada fila de la tabla, que `empleado.creado` y `empleado.retirado` no dejan fila, que el mismo `id` no deja dos y que una recuperación sin destinatario previo se confirma sin fila. La corrida contra el broker
está en [`docs/reto4/EVIDENCIAS.md`](../../docs/reto4/EVIDENCIAS.md): el mismo envelope publicado
dos veces deja una sola fila.
