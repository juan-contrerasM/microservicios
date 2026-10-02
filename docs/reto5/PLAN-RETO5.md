# Plan de implementación — Reto 5: Seguridad y control de acceso con JWT

Basado en [`docs/reto5/reto5.pdf`](reto5.pdf). Este documento traza el plan en etapas para que el
equipo pueda repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](STATUS.md). El Reto 4 queda cerrado; no reabrir etapas de
[`docs/reto4/PLAN-RETO4.md`](../reto4/PLAN-RETO4.md) salvo que un cambio de este reto lo exija
(el `docker-compose.yml` raíz, las rutas del Gateway, los bindings de `q.notificaciones` y el
scheduler de `vacaciones-service`).

El PDF dice en el contexto «en este cuarto reto». El título del documento es **Reto 5**. Se
implementa el título: `auth-service`, JWT en el borde, RBAC con propiedad del recurso, el ciclo
de vida de la cuenta y el scheduler de vacaciones.

Las cargas de evento no se inventan. El contrato es
[`docs/reto4/catalogo-de-eventos.pdf`](../reto4/catalogo-de-eventos.pdf), secciones 3.4 a 3.10.
La sección 5 de ese catálogo numera los retos distinto a esta carpeta; aquí manda el título del
PDF de cada `docs/retoN/`.

## Qué hay que resolver (y qué no)

El sistema de Reto 4 entra por un solo puerto, publica eventos y sobrevive a la caída de un
consumidor. Cualquiera que conozca `localhost:8080` crea o retira empleados.

1. **La identidad vive en un servicio nuevo.** `auth-service` verifica credenciales, guarda la
   contraseña hasheada y firma el JWT. Los demás servicios no tienen tabla de usuarios.
2. **El borde es quien pide el token.** El Gateway del Reto 3 ya ve todas las peticiones
   externas. Valida la firma una sola vez y aplica el rol. Un servicio de negocio no añade
   su propia librería de JWT.
3. **La cuenta no se crea en el login.** Nace inactiva cuando llega `empleado.creado`, se
   activa con el token del correo, se suspende con `vacaciones.iniciadas`, vuelve con
   `vacaciones.finalizadas` y muere con `empleado.retirado`. Un retiro durante las vacaciones
   no se deshace cuando el período termina.
4. **El tiempo lo mira vacaciones.** El scheduler pasa `PROGRAMADA → EN_CURSO → FINALIZADA`
   y publica `vacaciones.iniciadas` y `vacaciones.finalizadas`. En el Reto 4 el período nacía
   `PROGRAMADA` y solo salía de ahí si se cancelaba.

## Decisiones de arquitectura ya tomadas

Estas decisiones fijan las reglas del juego para todas las etapas. Si alguien quiere cambiarlas,
que lo discuta con el equipo antes de tocar el compose raíz.

| Decisión | Elección | Por qué |
|---|---|---|
| Lenguaje de `auth-service` | **Python 3.12 + FastAPI** | El curso ya tiene los cuatro lenguajes (Java, Go, Node y Python). Este reto no pide un quinto. Se repite Python, en otra carpeta, con su propio proceso, su Postgres y su Alembic: no comparte runtime ni base con `notificaciones-service`. FastAPI deja el OpenAPI en `/docs`, y pika es el mismo cliente AMQP que ya consume la cola de notificaciones. |
| Dónde se valida el JWT | **Solo en el API Gateway** | Es la opción 1 del PDF. Seis servicios en cuatro lenguajes obligarían a cuatro librerías y a repetir el cambio cuando el Reto 10 pase a JWKS. Los servicios de negocio, dentro de la red, no comprueban la firma. |
| Excepción | `POST /auth/change-password` vuelve a verificar el access token **dentro** de `auth-service` | Ese endpoint tiene que saber el `sub` para cambiar la contraseña de quien llama, y tiene que rechazar un token de reset usado como Bearer. Es el emisor leyendo su propio token, no la opción 2 repartida por el ecosistema. El Gateway igual exige el access token antes de proxear. |
| Algoritmo | **HMAC SHA-256** (`HS256`) | El PDF lo pide por simplicidad académica. La misma `JWT_SECRET` entra por entorno al Gateway y a `auth-service`. Nada hardcodeado. |
| Access token | `sub` = `empleadoId` (`E001`) o `admin` en la semilla. `role` = `ADMIN` o `USER`. `exp` a **60 minutos** (`JWT_ACCESS_MINUTES`) | El PDF compara `recurso.empleadoId` con `sub`. Si `sub` fuera el email, `PUT /perfiles/E001` nunca podría ser 200 para el dueño. El login sigue siendo por **email**. El ejemplo `"sub": "nombre_de_usuario"` del PDF es la forma del claim, no el valor. La diferencia `exp - iat` del ejemplo es 3600 s. |
| Token de activación / recuperación | **Opción A**: JWT distinto, claim `type` = `RESET_PASSWORD`, expira a **60 minutos** (`RESET_TOKEN_MINUTES`) | El PDF la recomienda. No se guarda el token. No sirve como Bearer: el Gateway y `change-password` rechazan cualquier token cuyo `type` sea `RESET_PASSWORD` (401). El valor que viaja en `tokenActivacion` / `tokenRecuperacion` es ese JWT. El UUID del catálogo es un ejemplo de «un token», no un formato obligatorio. |
| Contraseña | **BCrypt**. Nunca en el JWT, nunca en un evento, nunca en un log | Lo pide el PDF y la sección 2.2 del catálogo. |
| Política de contraseña | Mínimo 8 caracteres, una mayúscula, una minúscula y un dígito | El PDF dice que `change-password` valida una política y no la escribe. Esta es la regla única para reset y change. Si falla: **400** `{"mensaje":"La contraseña no cumple la política de seguridad"}`. |
| Estados de la cuenta | `PENDIENTE_ACTIVACION`, `ACTIVA`, `SUSPENDIDA_TEMPORAL`, `DESACTIVADA_PERMANENTE` | El PDF prohíbe un booleano. El alta nace pendiente, sin hash válido. Hace falta el cuarto estado porque «inactiva, sin contraseña» no es lo mismo que suspendida por vacaciones ni que retirada. |
| Reactivar al fin de vacaciones | Solo si el estado es `SUSPENDIDA_TEMPORAL` | No se consulta la base de empleados. El catálogo dice «si el empleado sigue ACTIVO»; en este servicio eso se materializa así: `empleado.retirado` deja `DESACTIVADA_PERMANENTE` y `vacaciones.finalizadas` no la toca ni publica `cuenta.activada`. Si ya estaba retirada cuando llega `vacaciones.iniciadas`, tampoco se baja a temporal ni se publica otra desactivación. |
| Login fallido | **401** en todos los casos | Credenciales malas: `{"mensaje":"Credenciales inválidas"}`. Cuenta que no está `ACTIVA`: `{"mensaje":"La cuenta no está activa"}`. El PDF acepta 401 o 403 en el retiro; se usa 401 para no mezclarlo con el 403 de RBAC. No se revela si el email existe cuando la contraseña no coincide. |
| Semilla ADMIN | Variables `AUTH_ADMIN_USUARIO`, `AUTH_ADMIN_EMAIL`, `AUTH_ADMIN_PASSWORD`. Si no hay fila `admin`, se crea al arrancar con estado `ACTIVA` y rol `ADMIN` | El paso 2 del PDF lo pide. Valores de referencia académica en `.env.example`: usuario `admin`, email `admin@empresa.com`, clave `Admin1234!`. `sub` del token = `admin`. |
| Puerto interno | **`8086`**. Solo `expose:` | El diagrama del PDF pone `auth-service` en `:8085`, pero ese puerto ya es `vacaciones-service` desde el Reto 4. Publicarlo rompería el criterio de un solo puerto de negocio. Se alcanza por `http://localhost:8080/auth`. |
| Base de datos | PostgreSQL 16 propio (`database-auth`), volumen propio | Misma regla que el resto: no compartir el contenedor de otro servicio. |
| Esquema | **Alembic** (upgrade/downgrade) | Es el changelog con rollback que ya usa notificaciones. Las revisiones viven en `services/auth-service/alembic/versions/`, no en el árbol de notificaciones. No se entrega `create_all` de SQLAlchemy como mecanismo definitivo. |
| Cola | `q.auth` durable, ack manual, prefetch 1, tabla `eventos_procesados` | Bindings: `empleado.creado`, `empleado.retirado`, `vacaciones.iniciadas`, `vacaciones.finalizadas`. Misma regla del Reto 4: commit primero; si el publish falla, la fila queda y el error queda en el log. |
| Scheduler | **`node-cron`** en `vacaciones-service`, variable `VACACIONES_CRON` | El servicio ya es Node. Default de desarrollo: `* * * * *` (cada minuto). Un período `PROGRAMADA` con `fechaInicio` ≤ hoy (UTC, solo fecha) pasa a `EN_CURSO` y publica `vacaciones.iniciadas`. Un `EN_CURSO` con `fechaFin` **anterior** a hoy pasa a `FINALIZADA` y publica `vacaciones.finalizadas`. «Ya pasó» no incluye el día de `fechaFin`: si no, un período de un solo día nacería y moriría en el mismo tick. |
| Cómo demostrarlo sin esperar un día | `POST /vacaciones/{id}/forzar-inicio` y `POST /vacaciones/{id}/forzar-fin`, rol `ADMIN` | El PDF ofrece tres estrategias. El cron cada minuto cubre el camino real, pero con fechas solo-día un período que termina hoy no finaliza hasta mañana. Los dos POST de desarrollo disparan la misma transición y el mismo evento, marcados en OpenAPI como endpoints de desarrollo. La colección Newman usa fechas de **mañana** y estos POST, para no competir con el cron. |
| Mismo día | `fechaFin` puede ser **igual** a `fechaInicio` | El paso 12 del PDF programa inicio y fin hoy. La validación del Reto 4 exigía `fechaFin` posterior y rechazaría ese caso. El mensaje pasa a `La fechaFin no puede ser anterior a la fechaInicio`. Un `fechaFin` anterior sigue en 400. |
| `EN_VACACIONES` en el empleado | **No se transiciona** | El enum existe en Java. Este PDF desactiva la **cuenta** en `auth-service`, no el estado del empleado. Cambiarlo aquí mezclaría el retiro y la auditoría. |
| Correo de bienvenida | Sale con `usuario.creado`, tipo `SEGURIDAD`. `empleado.creado` **ya no** inserta `BIENVENIDA` | El PDF y el catálogo lo dicen: sin el token el correo no sirve. `empleado.creado` solo guarda el destinatario (email, nombre, apellido) para los eventos que no traen email. La fila `DESVINCULACION` de `empleado.retirado` también sale: el adiós es `cuenta.desactivada`. Perfiles no cambia. |
| Logs de notificación recortados en el PDF | Se completan las tres líneas que el PDF corta al margen, y la colección las fija | En la página del enunciado el texto no continúa fuera del cuadro. Las cadenas de abajo son el contrato de este repo. Si la cátedra publica la frase completa, se cambia en un solo sitio: este plan, el procesador y la colección. |
| Swagger | Esquema **BearerAuth** en el OpenAPI de cada servicio, sin duplicar la validación | El PDF pide probar desde la UI. El botón Authorize documenta el header. Quien hace cumplir el token sigue siendo el Gateway. Desde el host no se abren los puertos internos para ver esa UI. |
| Denylist / logout | Fuera de este reto | Un access token vale hasta `exp`. Cambiar la contraseña hace fallar el login viejo; el JWT ya emitido sigue válido hasta que expire. Se documenta en el README. |

## Catálogo que este reto añade

Envelope, igual que en el Reto 4. `producer` de los cuatro eventos de identidad:
`auth-service`. `producer` de los dos de vacaciones: `vacaciones-service`. `version` = `1`.

`usuario.creado` (al consumir `empleado.creado`):

```json
{
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "tokenActivacion": "<jwt type=RESET_PASSWORD>",
  "expiraEn": "2026-10-02T15:30:00Z"
}
```

`usuario.recuperacion` (`POST /auth/recover-password`):

```json
{
  "email": "juan.perez@empresa.com",
  "tokenRecuperacion": "<jwt type=RESET_PASSWORD>",
  "expiraEn": "2026-10-02T16:00:00Z"
}
```

`cuenta.activada`:

```json
{
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "motivo": "ACTIVACION_INICIAL"
}
```

| `motivo` | Cuándo |
|---|---|
| `ACTIVACION_INICIAL` | El reset token estableció la contraseña y la cuenta salió de `PENDIENTE_ACTIVACION` |
| `FIN_VACACIONES` | Llegó `vacaciones.finalizadas` y la cuenta estaba `SUSPENDIDA_TEMPORAL` |

Un reset sobre una cuenta que **ya** estaba `ACTIVA` (recuperación) cambia el hash y **no**
publica `cuenta.activada`. Recuperar o resetear una cuenta `SUSPENDIDA_TEMPORAL` o
`DESACTIVADA_PERMANENTE` responde **400**
`{"mensaje":"La cuenta no admite un cambio de contraseña en su estado actual"}`
y no publica nada.

`cuenta.desactivada`:

```json
{
  "empleadoId": "E001",
  "email": "juan.perez@empresa.com",
  "motivo": "VACACIONES",
  "permanente": false
}
```

| `motivo` | `permanente` | Cuándo |
|---|---|---|
| `VACACIONES` | `false` | Consumo de `vacaciones.iniciadas` desde `ACTIVA` |
| `RETIRO` | `true` | Consumo de `empleado.retirado` |

`vacaciones.iniciadas` y `vacaciones.finalizadas`: las cargas de las secciones 3.9 y 3.10 del
catálogo. El scheduler y los dos POST de desarrollo publican exactamente eso. No llevan
`diasHabiles`.

## Endpoints de `auth-service`

Todos detrás del Gateway, prefijo `/auth`. El servicio escucha en el puerto interno `8086`.

| Método | Ruta | Auth en el Gateway | Respuesta |
|---|---|---|---|
| `POST` | `/auth/login` | Pública | 200 `{"token","tokenType":"Bearer","expiresIn":3600,"role"}`. 401 si la clave no coincide o la cuenta no está `ACTIVA` |
| `POST` | `/auth/recover-password` | Pública | 200 `{"mensaje":"Si el correo existe, se envió un enlace de recuperación"}` siempre, exista o no el email. Solo publica `usuario.recuperacion` si la cuenta está `PENDIENTE_ACTIVACION` o `ACTIVA` |
| `POST` | `/auth/reset-password` | Pública. El token va en el **cuerpo**, no en `Authorization` | 200 `{"mensaje":"Contraseña actualizada"}`. 400 si el token no es un reset válido o expiró: `{"mensaje":"El token de recuperación no es válido o expiró"}`. 400 si no cumple la política o el estado no admite el cambio |
| `POST` | `/auth/change-password` | Access token. `USER` y `ADMIN` | 200 `{"mensaje":"Contraseña actualizada"}`. Usa el `sub` del token. 400 si la actual no coincide: `{"mensaje":"La contraseña actual no es correcta"}`. 400 si la nueva no cumple la política |
| `GET` | `/health` | No pasa por el RBAC del negocio: el Gateway deja pasar `GET /health` **suyo**. El `/health` de auth solo lo usa Docker, dentro de la red | 200 solo si Postgres responde y el consumidor está conectado |

Cuerpos:

```json
{ "usuario": "juan.perez@empresa.com", "contrasena": "Usuario123!" }
```

```json
{ "email": "juan.perez@empresa.com" }
```

```json
{ "token": "<jwt RESET_PASSWORD>", "contrasena": "Usuario123!" }
```

```json
{ "contrasenaActual": "Usuario123!", "contrasenaNueva": "Usuario456!" }
```

`usuario` del login es el **email** del empleado, o `admin` en la semilla. La clave JSON es
`contrasena`, sin eñe, igual que el resto de campos del repo no llevan tildes.

Alta de la cuenta, al consumir `empleado.creado`: `empleadoId`, email como usuario, rol
`USER`, estado `PENDIENTE_ACTIVACION`, sin hash. Publica `usuario.creado`. Si el `empleadoId`
ya tiene cuenta, se confirma y no se publica otro `usuario.creado`.

## RBAC en el Gateway

Públicas, sin header:

- `GET /health`
- `POST /auth/login`
- `POST /auth/recover-password`
- `POST /auth/reset-password`

Cualquier otra ruta (`/empleados`, `/departamentos`, `/perfiles`, `/notificaciones`,
`/vacaciones`, `/auth/change-password`, y los `forzar-*`):

| Situación | Código | Cuerpo |
|---|---|---|
| Sin header, token mal formado, firma mala, expirado, o `type` = `RESET_PASSWORD` | **401** | `{"status":401,"mensaje":"No autenticado"}` |
| Access token válido y la regla de abajo no lo deja | **403** | `{"status":403,"mensaje":"No tiene permisos para realizar esta operación"}` |

Regla, en este orden:

1. `role` = `ADMIN` → sigue el proxy.
2. `role` = `USER` y el método es `GET` → sigue el proxy. Incluye el perfil y las
   notificaciones de otro empleado: el PDF solo pide propiedad en la escritura del perfil.
3. `role` = `USER` y la ruta es `POST /auth/change-password` → sigue el proxy.
4. `role` = `USER`, método `PUT` y la ruta es `/perfiles/{empleadoId}` con `empleadoId` igual
   al `sub` → sigue el proxy.
5. En cualquier otro caso → 403. Un `USER` que hace `PUT /perfiles/E002` con `sub` `E001`
   recibe **403**, no 401. Un `USER` que hace `DELETE`, `POST` o `PUT` de empleados,
   departamentos o vacaciones recibe 403.

El 503 JSON del Reto 3 no cambia cuando el backend está caído. El Gateway no reescribe el
cuerpo de un 200/400/404 de negocio.

Límite conocido, se documenta y no se «arregla» en este reto: un `USER` autenticado puede
leer `GET /notificaciones/{empleadoId}` de otro y ver el token de activación. El PDF no pide
ABAC en las lecturas.

## Notificaciones nuevas

`q.notificaciones` suma bindings: `usuario.creado`, `usuario.recuperacion`,
`cuenta.activada`, `cuenta.desactivada`, `vacaciones.iniciadas`, `vacaciones.finalizadas`.
Siguen los tres bindings del Reto 4. Deduplicación por `id` del envelope, igual que hoy.
Mailhog sigue siendo el SMTP opcional; si falla, la fila queda.

| Evento | `tipo` | Mensaje |
|---|---|---|
| `usuario.creado` | `SEGURIDAD` | `Para establecer o restablecer su contraseña ingrese a https://app.empresa.com/reset?token={tokenActivacion}` |
| `usuario.recuperacion` | `SEGURIDAD` | La misma frase, con `tokenRecuperacion`. El `empleadoId` de la fila se busca por email en el destinatario guardado. Si no hay destinatario, se confirma sin fila |
| `cuenta.desactivada` | `CUENTA` | `Su cuenta fue desactivada` |
| `cuenta.activada` | `CUENTA` | `Bienvenido de regreso. Su cuenta ha sido reactivada` |
| `vacaciones.iniciadas` | `VACACIONES` | `Sus vacaciones del {fechaInicio} al {fechaFin} han iniciado` |
| `vacaciones.finalizadas` | `VACACIONES` | `Sus vacaciones finalizaron el {fechaFin}` |
| `vacaciones.programadas` | `VACACIONES` | Sin cambio respecto al Reto 4 |
| `empleado.creado` | — | Solo actualiza el destinatario. No hay fila `BIENVENIDA` |
| `empleado.retirado` | — | No hay fila `DESVINCULACION`. El adiós es `cuenta.desactivada` |

El log sigue el formato del Reto 4:

`[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: {email} | Mensaje: "{mensaje}"`

Asuntos SMTP nuevos: `Seguridad`, `Cuenta`. Los tres asuntos viejos se quedan para
`VACACIONES`.

`usuario.recuperacion` no trae `empleadoId`. Por eso el alta tiene que haber guardado el
destinatario antes. En el flujo real `empleado.creado` ocurre primero.

## Scheduler y períodos

Además del cron:

| Método | Ruta | Quién | Efecto |
|---|---|---|---|
| `POST` | `/vacaciones/{id}/forzar-inicio` | `ADMIN` | Igual que el cron al llegar `fechaInicio`: `PROGRAMADA` → `EN_CURSO` y `vacaciones.iniciadas`. Si no está `PROGRAMADA`, **400** `{"mensaje":"Solo se puede forzar el inicio de un período PROGRAMADA"}` |
| `POST` | `/vacaciones/{id}/forzar-fin` | `ADMIN` | `EN_CURSO` → `FINALIZADA` y `vacaciones.finalizadas`. Si no está `EN_CURSO`, **400** `{"mensaje":"Solo se puede forzar el fin de un período EN_CURSO"}` |

Un `USER` recibe 403 en los dos. No sustituyen al cron: el README tiene que explicar la
frecuencia, la variable y que con N instancias el Reto 11 dispararía el evento N veces. La
deduplicación del consumidor mitiga el efecto; el lock distribuido es el Reto 31 (ShedLock).
Este reto acepta una sola instancia.

Caso borde, en orden:

1. `vacaciones.iniciadas` deja la cuenta `SUSPENDIDA_TEMPORAL` y publica
   `cuenta.desactivada` (`VACACIONES`, `permanente: false`). El login responde 401.
2. `DELETE /empleados/{id}` publica `empleado.retirado`. La cuenta pasa a
   `DESACTIVADA_PERMANENTE` y se publica `cuenta.desactivada` (`RETIRO`, `permanente: true`).
3. `vacaciones.finalizadas` **no** publica `cuenta.activada` y el login sigue en 401.
   El período sí queda `FINALIZADA`: el calendario termina aunque la cuenta no vuelva.

## Arquitectura objetivo

```
Cliente HTTP (curl / Postman)
        │
        │  única URL de negocio: http://localhost:8080
        │  Authorization: Bearer <access JWT>
        ▼
┌───────────────────┐   ports 8080
│    api-gateway    │   valida HS256, role y propiedad
│  Node.js/Express  │   /auth  /empleados  /departamentos
└─────────┬─────────┘   /perfiles  /notificaciones  /vacaciones
          │  expose, nunca ports
   ┌──────┼──────────┬──────────────────┐
   ▼      ▼          ▼                  ▼
empleados  departamentos  perfiles   notificaciones   vacaciones     auth
 :8080      :8081         :8083       :8084            :8085          :8086
 Java        Go            Java        Python           Node.js        Python
   │ publica               │            │ consume         │ publica      │ consume y publica
   │ empleado.*            │            │ usuario.*       │ vacaciones.* │ usuario.* cuenta.*
   └────────────┬──────────┴────────────┴─────────────────┴──────────────┘
                ▼
        RabbitMQ  (5672 AMQP, 15672 management)
                │
                ▼
 db-empleados  db-departamentos  db-perfiles  db-notificaciones  db-vacaciones  db-auth
 Postgres      MySQL             Postgres     Postgres           Postgres       Postgres
```

`localhost:8086` en el host debe rechazar la conexión, igual que `:8081`–`:8085`.

## Etapas

Cada etapa indica qué entrega, de qué depende y qué criterio del PDF cierra. Las etapas 2, 4
y 5 pueden avanzar en paralelo cuando el contrato de este archivo ya está aceptado: no
comparten carpeta. El compose raíz y el Gateway se tocan en la Etapa 3 y en la Etapa 6. No
mezclar esos dos cambios en el mismo PR si hay dos personas.

### Etapa 0 — Plan, STATUS y colección de pruebas

- Este archivo, [`STATUS.md`](STATUS.md) y
  [`Reto5.postman_collection.json`](Reto5.postman_collection.json).
- La colección usa `{{gateway_url}}` = `http://localhost:8080`. Las carpetas 0–10 se corren en
  orden, con el sistema sano y la semilla `admin` / `Admin1234!`. La 11 es manual (puertos).
- Las fechas de vacaciones de la colección son **mañana** (UTC). El cron de cada minuto no
  debe adelantarse al `forzar-inicio`. El paso 12 del PDF (hoy/hoy) se muestra en la
  sustentación; Newman no depende de él.

No depende de nada. Es el contrato: no inventar rutas, códigos, roles ni nombres de evento
distintos a los de aquí, del PDF y del catálogo.

### Etapa 1 — `auth-service`: credenciales y access token — criterio 1 (parte de 1.0 pts)

Carpeta `services/auth-service/`. Todavía no consume la cola. Se puede probar el login de la
semilla con el proceso a solas; el Gateway llega en la Etapa 3.

- Python 3.12, FastAPI, Dockerfile `python:3.12-slim` (el mismo corte que notificaciones),
  Alembic, psycopg, bcrypt y PyJWT. pika entra en la Etapa 2, con el consumidor.
- Tablas: `cuentas` (`empleado_id` único, `email` único, `rol`, `estado`, `password_hash`
  nullable, `creada_en`) y `eventos_procesados`. La semilla ADMIN usa `empleado_id` = `admin`.
- `POST /auth/login` de la semilla firma el access token HS256 con `JWT_SECRET`. Un token no
  incluye la contraseña. Claims mínimos: `sub`, `role`, `iat`, `exp`.
- `GET /health` y OpenAPI en `/docs`.
- README del servicio: variables y por qué se repite Python sin compartir nada con
  notificaciones.
- Tests: la clave se guarda como hash; el login bueno devuelve un JWT cuyo `role` es `ADMIN`;
  la clave mala responde 401; el payload no contiene la contraseña.

Depende de la Etapa 0.

### Etapa 2 — Ciclo de vida de la cuenta — criterio 1 y parte del 4 (1.5 pts)

Solo `services/auth-service/`, más el binding que el compose confirmará en la Etapa 6.

- Consumidor de `q.auth`, ack manual, prefetch 1, deduplicación por `id`.
- `empleado.creado` → cuenta `PENDIENTE_ACTIVACION`, rol `USER`, publica `usuario.creado`.
- `POST /auth/reset-password` y `POST /auth/recover-password` con las reglas de estado de
  arriba. La primera contraseña publica `cuenta.activada` con `ACTIVACION_INICIAL`.
- `POST /auth/change-password` verifica el access token con el mismo secreto.
- `vacaciones.iniciadas` → `SUSPENDIDA_TEMPORAL` y `cuenta.desactivada` (`VACACIONES`,
  `permanente: false`), solo desde `ACTIVA`.
- `empleado.retirado` → `DESACTIVADA_PERMANENTE` y `cuenta.desactivada` (`RETIRO`,
  `permanente: true`). No importa si estaba activa o suspendida.
- `vacaciones.finalizadas` → `ACTIVA` y `cuenta.activada` (`FIN_VACACIONES`) **solo** desde
  `SUSPENDIDA_TEMPORAL`. Desde `DESACTIVADA_PERMANENTE` se confirma el mensaje y no se
  publica nada.
- Tests del caso borde: iniciadas, luego retirado, luego finalizadas, y el login sigue en
  401. Un segundo delivery del mismo `id` no publica dos veces.

Depende de la Etapa 1. En paralelo con las Etapas 4 y 5 en cuanto a carpetas. El flujo
completo necesita las tres.

### Etapa 3 — JWT y RBAC en el Gateway — criterios 2 y 3 (0.5 + 1.0 pts)

Solo `services/api-gateway/`.

- `JWT_SECRET` por entorno. Librería de verificación HS256 (una, en Node).
- Lista pública de la tabla de arriba. El resto exige Bearer.
- 401 y 403 con el JSON de este plan, antes de proxear. No se reenvía un token inválido.
- `PUT /perfiles/{empleadoId}` compara el segmento con `sub`.
- `GET /health` del Gateway sigue sin JWT y sin ping a los backends.
- Ruta nueva, sin strip:

  | Ruta externa | Destino |
  |---|---|
  | `/auth` y `/auth/*` | `http://auth-service:8086` |

  Si auth todavía no está en el compose, el 503 JSON usa `"servicio": "auth-service"`.
  El cableado del contenedor es la Etapa 6; esta etapa deja la ruta y la variable
  `AUTH_URL`.
- Tests: sin header 401; firma alterada 401; `USER` hace `DELETE /empleados/E001` → 403;
  `USER` con `sub` E001 hace `PUT /perfiles/E001` → el proxy sigue; `PUT /perfiles/E002` →
  403; `ADMIN` pasa el `DELETE`.

Depende de la Etapa 0. Puede avanzar con un secreto de prueba sin esperar el contenedor de
auth. El login real de punta a punta espera la Etapa 6.

### Etapa 4 — Notificaciones de identidad y de vacaciones — parte del criterio 4

Solo `services/notificaciones-service/`.

- Bindings nuevos en `q.notificaciones`.
- Dejar de insertar `BIENVENIDA` y `DESVINCULACION`. Conservar el destinatario en
  `empleado.creado`.
- Las seis filas de la tabla de mensajes, con el mismo log `[NOTIFICACIÓN]`.
- Asuntos SMTP `Seguridad` y `Cuenta`.
- Tests: `usuario.creado` deja una `SEGURIDAD` y el token dentro del mensaje; el mismo `id`
  no deja dos; `empleado.creado` no deja fila; `cuenta.desactivada` deja `CUENTA`;
  `usuario.recuperacion` sin destinatario previo se confirma sin fila.

Depende de la Etapa 0. En paralelo con 2, 3 y 5.

### Etapa 5 — Scheduler de vacaciones — parte del criterio 4

Solo `services/vacaciones-service/`.

- `node-cron` con `VACACIONES_CRON`. Transiciones de la sección Scheduler. Publicar después
  del commit. Si el publish falla, el período ya cambió de estado y el error queda en el log
  (misma regla del Reto 4).
- `fechaFin` igual a `fechaInicio` es válido. Actualizar el mensaje 400.
- `POST /vacaciones/{id}/forzar-inicio` y `forzar-fin` con los 400 de este plan. El Gateway
  los protege; el servicio no repite el RBAC.
- Tests: un `PROGRAMADA` con inicio hoy pasa a `EN_CURSO` y publica una vez aunque el job
  corra dos veces; un `EN_CURSO` con fin hoy **no** finaliza; `forzar-fin` sí publica
  `vacaciones.finalizadas`; un `CANCELADA` no lo toca el cron.

Depende de la Etapa 0. En paralelo con 2, 3 y 4.

### Etapa 6 — Compose, secreto y OpenAPI — criterio 5 (0.5 pts)

- Servicio `auth-service`: `expose: "8086"`, sin `ports:`. `depends_on` su Postgres y el
  broker con `service_healthy`. Healthcheck contra su `/health`.
- `JWT_SECRET` inyectado a `auth-service` y a `api-gateway`. El resto de variables
  (`JWT_ACCESS_MINUTES`, `RESET_TOKEN_MINUTES`, `AUTH_ADMIN_*`, `VACACIONES_CRON`,
  `AUTH_URL`) en `.env.example`. Ninguna clave en el código.
- Gateway `depends_on` auth con `service_started`, no `service_healthy`.
- Esquema BearerAuth en el OpenAPI de auth, empleados, departamentos, perfiles,
  notificaciones y vacaciones. `change-password` y los `forzar-*` lo declaran. Login,
  recover y reset no lo exigen.
- `localhost:8086` rechaza la conexión.

Depende de las Etapas 1 y 3, y del Dockerfile de auth. Las Etapas 2, 4 y 5 tienen que estar
cableadas aquí (`BROKER_URL` ya existe para los otros consumidores).

### Etapa 7 — README, diagrama y evidencias — criterio 6 (0.5 pts)

Depende de las Etapas 1 a 6.

- Recorrer el §4 del PDF con la colección de este directorio (Newman en las carpetas 0–10).
- README raíz, que es entregable del PDF:
  1. Cómo obtener el token: login de `admin`, alta, token dentro de la notificación
     `SEGURIDAD`, `reset-password`, login del empleado. Apuntar a la colección.
  2. Por qué el JWT se valida en el Gateway y no en cada servicio, y por qué
     `change-password` es la excepción.
  3. `JWT_SECRET` nombrado en `.env.example`.
  4. Diagrama de secuencia: `empleado.creado` → cuenta pendiente → `usuario.creado` →
     reset → `cuenta.activada` → vacaciones inician → login falla → vacaciones terminan →
     login funciona → retiro → login falla. Incluir el caso borde en el mismo diagrama o en
     uno al lado.
  5. Cron, `VACACIONES_CRON`, los POST de desarrollo, y por qué Newman usa mañana.
  6. Qué pasa si `vacaciones-service` escala a N instancias, y que el Reto 31 lo cierra
     con ShedLock. La deduplicación de `q.auth` no evita el trabajo duplicado.
  7. Evidencia del caso borde: E002 retirado durante las vacaciones no vuelve a entrar.
- READMEs de auth, Gateway, notificaciones y vacaciones: el «por qué» de la decisión que
  les toca.
- Evidencias en un `EVIDENCIAS.md` de esta carpeta, al estilo del Reto 4:

  | # | Qué | Cómo se ve |
  |---|---|---|
  | 1 | Alta protegida | Sin token, `GET /empleados` es 401. Con el JWT de E001 es 200 y el `sub` es `E001`. |
  | 2 | RBAC y propiedad | `DELETE /empleados/E001` con el USER es 403. `PUT /perfiles/E001` es 200 y `PUT /perfiles/E002` es 403. |
  | 3 | Vacaciones | `forzar-inicio` → login 401 y una `CUENTA` «Su cuenta fue desactivada». `forzar-fin` → login 200 y «Bienvenido de regreso. Su cuenta ha sido reactivada». |
  | 4 | Caso borde | E002 retirado en `EN_CURSO`. Tras `forzar-fin` el login sigue en 401 y no aparece la bienvenida de regreso. |
  | 5 | Secreto y puertos | `JWT_SECRET` solo en el entorno. `curl` a `:8086` rechazado. |

- Actualizar [`STATUS.md`](STATUS.md) en el mismo commit que cierre cada etapa.

La colección del Reto 4 deja de ser el contrato de las notificaciones de alta y de retiro:
busca `BIENVENIDA` y `DESVINCULACION`, que este reto retira. No hace falta reescribirla; queda
como evidencia histórica del Reto 4.

## Contrato de respuestas que no debe romperse

Lo del Reto 4 sigue igual en empleados, departamentos, perfiles y en el 503 del Gateway,
con dos salvedades: casi todas esas rutas ahora exigen access token, y `fechaFin` puede ser
igual a `fechaInicio`. Se suma:

| Situación | Código | Quién |
|---|---|---|
| Login de `admin` o de una cuenta `ACTIVA` | 200 y access JWT | auth |
| Login con clave mala | 401 `Credenciales inválidas` | auth |
| Login de cuenta no `ACTIVA` | 401 `La cuenta no está activa` | auth |
| Recover, email exista o no | 200, el mismo mensaje | auth |
| Reset token bueno y cuenta pendiente o activa | 200. Si estaba pendiente, evento `cuenta.activada` | auth |
| Reset token malo, expirado o de tipo access | 400 | auth |
| Change-password con la clave actual bien | 200 | auth |
| Petición de negocio sin access token | 401 | Gateway |
| `USER` escribe donde no es dueño, o escribe fuera de su perfil y de su clave | 403 | Gateway |
| `USER` lee | 200 | Gateway, luego el servicio |
| `forzar-inicio` de un `PROGRAMADA` | 200, `EN_CURSO`, evento `vacaciones.iniciadas` | vacaciones |
| `forzar-fin` de un `EN_CURSO` | 200, `FINALIZADA`, evento `vacaciones.finalizadas` | vacaciones |
| `vacaciones.finalizadas` con cuenta `DESACTIVADA_PERMANENTE` | el período termina; no hay `cuenta.activada` | auth |
| Gateway sin auth levantado | 503 JSON, `servicio` = `auth-service` | Gateway |
| `curl` a `:8086` en el host | conexión rechazada | Docker |

Los GET de perfil y de notificación siguen siendo asíncronos: la colección reintenta. Un 404
inmediato después del `POST /empleados` no es un fallo; lo es si sigue el 404 al agotar la
espera. La `SEGURIDAD` da dos saltos (auth y luego notificaciones): la espera es de 20 s.

## Criterios de evaluación ↔ etapas

| # | Elemento | Pts | Etapa |
|---|---|---|---|
| 1 | `auth-service`, hash, JWT, token de activación con exp, login / recover / reset / change-password | 1.0 | 1 + 2 |
| 2 | Los servicios exigen el JWT. Sin token o con token alterado → 401 | 0.5 | 3 |
| 3 | `ADMIN` / `USER`, 403, el USER edita su perfil y su clave, y no el perfil de otro | 1.0 | 3 |
| 4 | Consume los cuatro eventos, publica los cuatro de identidad, scheduler, login OK → falla → OK, caso borde | 1.5 | 2 + 4 + 5 + 7 |
| 5 | `JWT_SECRET` y el resto por `docker-compose.yml`, sin hardcode | 0.5 | 6 |
| 6 | `docker compose up`, BearerAuth, diagrama de secuencia, limitación del scheduler | 0.5 | 6 + 7 |

## Fuera de alcance de Reto 5 (a propósito)

- Validar el JWT en empleados, departamentos, perfiles, notificaciones o vacaciones.
- JWKS y el proveedor de identidad del Reto 10.
- Pasar al empleado a `EN_VACACIONES`.
- ShedLock, o cualquier lock para que una sola instancia corra el cron (Reto 31).
- Denylist, logout o refresh token.
- Inbox pattern, DLQ y backoff (Reto 29).
- Ocultar el token de activación de los `GET` de notificaciones.
- Firma asimétrica.
- Publicar el puerto 8086 para ver Swagger.
- Lógica de negocio nueva dentro del Gateway, fuera de autenticar, autorizar y enrutar.
