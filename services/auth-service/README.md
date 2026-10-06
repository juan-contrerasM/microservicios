# auth-service

Identidad del onboarding. Crea la cuenta cuando llega `empleado.creado`, firma el access JWT
y publica los eventos de usuario y de cuenta. Python 3.12, FastAPI, PostgreSQL propio, Alembic
y pika.

## Por qué se repite Python

El curso ya tiene cuatro lenguajes: Java, Go, Node y Python. Este reto no pide un quinto.
El servicio vive en su propia carpeta, con su propio proceso, su propio Postgres y su propio
árbol de Alembic. No comparte runtime ni base con `notificaciones-service`. FastAPI deja el
OpenAPI en `/docs`. pika es el mismo cliente AMQP que ya usa notificaciones, contra otra cola
(`q.auth`).

## Por qué el JWT se valida en el Gateway

La firma de las rutas de negocio la comprueba el API Gateway, una sola vez. Este servicio
vuelve a leer el access token solo en `POST /auth/change-password`, porque tiene que saber
el `sub` y tiene que rechazar un token de reset usado como Bearer. El secreto es `JWT_SECRET`,
el mismo de los dos lados, inyectado por entorno. Algoritmo HS256.

## Estados de la cuenta

`PENDIENTE_ACTIVACION`, `ACTIVA`, `SUSPENDIDA_TEMPORAL`, `DESACTIVADA_PERMANENTE`. No es un
booleano: un retiro durante las vacaciones deja la cuenta permanente, y
`vacaciones.finalizadas` no la reactiva.

El alta nace pendiente, sin hash. La contraseña se guarda con bcrypt. No viaja en el JWT ni
en los eventos. El token de activación y el de recuperación son un JWT distinto, con
`type` = `RESET_PASSWORD` y 60 minutos de vida. No sirve como Bearer.

## Endpoints

El Gateway no recorta el prefijo. Este proceso escucha las rutas ya con `/auth`.

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/auth/login` | 200 con `token`, `tokenType`, `expiresIn`, `role`. 401 `Credenciales inválidas` o `La cuenta no está activa` |
| `POST` | `/auth/recover-password` | 200 con el mismo mensaje exista o no el correo. Publica `usuario.recuperacion` solo si la cuenta está pendiente o activa |
| `POST` | `/auth/reset-password` | 200 `Contraseña actualizada`. 400 si el token no vale, si la clave no cumple la política, o si el estado no admite el cambio |
| `POST` | `/auth/change-password` | Exige access token. 200, o 400 si la clave actual no coincide o la nueva no cumple la política |
| `GET` | `/health` | 200 solo si PostgreSQL responde y el consumidor está conectado. Lo usa Docker, no el Gateway |
| `GET` | `/docs` | Swagger de FastAPI. `/swagger` redirige aquí |

`usuario` del login es el email del empleado, o `admin` en la semilla. `sub` del access token
es el `empleadoId` (`E001`), no el email. En la semilla, `sub` es `admin`.

La política de contraseña es mínimo 8 caracteres, una mayúscula, una minúscula y un dígito.

El commit de la cuenta va primero. Si el publish falla, la fila queda y el error queda en el
log. Un `id` de evento ya visto se confirma sin repetir el efecto.

## Cola

`q.auth`, ack manual, prefetch 1. Bindings: `empleado.creado`, `empleado.retirado`,
`vacaciones.iniciadas`, `vacaciones.finalizadas`.

| Consume | Publica |
|---|---|
| `empleado.creado` | `usuario.creado` |
| `POST /auth/recover-password` | `usuario.recuperacion` |
| primer reset desde pendiente, o `vacaciones.finalizadas` desde suspendida | `cuenta.activada` |
| `vacaciones.iniciadas` desde activa, o `empleado.retirado` | `cuenta.desactivada` |

## Variables

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8086` | Puerto interno. No se publica al host |
| `DATABASE_URL` | Postgres local `auth_db` | Base propia |
| `JWT_SECRET` | *(obligatoria)* | Secreto HS256. Sin valor el proceso no arma la app |
| `JWT_ACCESS_MINUTES` | `60` | Vida del access token |
| `RESET_TOKEN_MINUTES` | `60` | Vida del token de activación y de recuperación |
| `AUTH_ADMIN_USUARIO` | `admin` | `empleado_id` y usuario de login de la semilla |
| `AUTH_ADMIN_EMAIL` | `admin@empresa.com` | Email de la semilla |
| `AUTH_ADMIN_PASSWORD` | `Admin1234!` | Clave académica. Se hashea al sembrar |
| `BROKER_URL` | `amqp://localhost:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `onboarding` | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic |
| `BROKER_QUEUE` | `q.auth` | Cola durable |
| `CONSUMER_DISABLED` | `false` | `true` solo en pruebas. En ese caso `/health` no exige la cola |

El esquema lo crea Alembic al arrancar (`alembic upgrade head`). El downgrade borra las dos tablas.

El contenedor vive en `docker-compose.yml` junto a `database-auth`. Solo declara `expose: 8086`;
desde el host se usa `http://localhost:8080/auth` a través del Gateway.

## Pruebas

```bash
pip install -r requirements.txt pytest httpx
pytest
```
