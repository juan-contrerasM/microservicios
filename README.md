# Sistema de Onboarding/Offboarding de Empleados — Monorepo

Monorepo de microservicios del curso. Un módulo por servicio bajo `services/`, cada uno con su
propio lenguaje, base de datos y Dockerfile, orquestados desde un único `docker-compose.yml` en
la raíz.

> Antes de tocar código: lee [`CLAUDE.md`](CLAUDE.md) (convenciones del repo).
> Reto 4 (activo): [`docs/reto4/PLAN-RETO4.md`](docs/reto4/PLAN-RETO4.md) y
> [`docs/reto4/STATUS.md`](docs/reto4/STATUS.md).
> Reto 3 (cerrado): [`docs/reto3/PLAN-RETO3.md`](docs/reto3/PLAN-RETO3.md) y
> [`docs/reto3/STATUS.md`](docs/reto3/STATUS.md).
> Reto 2 (cerrado): [`docs/reto2/PLAN-RETO2.md`](docs/reto2/PLAN-RETO2.md) y
> [`docs/reto2/STATUS.md`](docs/reto2/STATUS.md).

## Servicios

| Servicio | Lenguaje | Motor de BD | Puerto host | Carpeta |
|---|---|---|---|---|
| `api-gateway` | Node.js 22 / Express | *(ninguna)* | **`8080` (negocio)** | [`services/api-gateway`](services/api-gateway) |
| `empleados-service` | Java 21 / Spring Boot | PostgreSQL 16 | *no publicado* (`expose: 8080`) | [`services/empleados-service`](services/empleados-service) |
| `departamentos-service` | Go | MySQL 8.4 | *no publicado* (`expose: 8081`) | [`services/departamentos-service`](services/departamentos-service) |
| `message-broker` | RabbitMQ 3 | *(no es base de un servicio)* | `5672` AMQP, `15672` UI | solo en `docker-compose.yml` |
| `notificaciones-service` | Python 3.12 / FastAPI | PostgreSQL 16 | *no publicado* (`expose: 8084`) | [`services/notificaciones-service`](services/notificaciones-service) |
| `perfiles-service` | Java 21 / Spring Boot | PostgreSQL 16 | *no publicado* (`expose: 8083`) | [`services/perfiles-service`](services/perfiles-service) |
| `vacaciones-service` | Node.js 22 / Express | PostgreSQL 16 | *no publicado* (`expose: 8085`) | [`services/vacaciones-service`](services/vacaciones-service) |

URL base del sistema: **`http://localhost:8080`**. Todo el tráfico de negocio pasa por el Gateway:
`/empleados`, `/departamentos`, `/perfiles`, `/notificaciones`, `/vacaciones` y `/health`.
Los puertos `8081`, `8082`, `8083`, `8084` y `8085` no están publicados: desde el host la conexión
se rechaza. `5672` y `15672` son del broker, no una segunda API de negocio.

Hay cuatro lenguajes: Java (empleados y perfiles), Go (departamentos), Node.js (Gateway y
vacaciones) y Python (notificaciones). El Gateway no es Spring Cloud Gateway.

## Arranque desde cero

```bash
cp .env.example .env      # ajustar credenciales si hace falta
docker compose up --build
```

Verificar que las bases de datos y el broker queden `(healthy)`:

```bash
docker compose ps
```

La UI de RabbitMQ queda en **`http://localhost:15672`** (usuario y contraseña: `RABBITMQ_USER` /
`RABBITMQ_PASSWORD` del `.env`). El exchange y las colas los declaran los servicios al arrancar;
en esta etapa el broker sano no exige crearlos a mano.

Detener conservando datos:

```bash
docker compose down
```

Detener y borrar también los volúmenes (reinicio total, se pierden los datos — útil para
reproducir el esquema desde cero):

```bash
docker compose down -v
```

## Documentación OpenAPI / Swagger

Desde el navegador, con el compose arriba:

- [Swagger del Gateway](http://127.0.0.1:8080/swagger)
- [Spec del Gateway](http://127.0.0.1:8080/openapi.json)

Si `localhost` responde y `127.0.0.1` no, usa [http://localhost:8080/swagger](http://localhost:8080/swagger).
`/swagger` redirige a `/swagger/index.html`. Ese documento lista las rutas públicas.

Los otros Swagger no tienen puerto publicado. La URL es la del contenedor; el archivo es el contrato en el repo.

| Servicio | UI (red Docker) | Spec |
|---|---|---|
| Gateway | [http://127.0.0.1:8080/swagger](http://127.0.0.1:8080/swagger) | [http://127.0.0.1:8080/openapi.json](http://127.0.0.1:8080/openapi.json) · [`openapi.json`](services/api-gateway/src/openapi.json) |
| empleados | `http://empleados-service:8080/swagger-ui.html` | `http://empleados-service:8080/v3/api-docs` |
| departamentos | `http://departamentos-service:8081/swagger/index.html` | [`openapi.yaml`](services/departamentos-service/internal/httpapi/swagger/openapi.yaml) |
| perfiles | `http://perfiles-service:8083/swagger-ui.html` | `http://perfiles-service:8083/v3/api-docs` |
| notificaciones | `http://notificaciones-service:8084/docs` | `http://notificaciones-service:8084/openapi.json` |
| vacaciones | `http://vacaciones-service:8085/swagger/index.html` | [`openapi.json`](services/vacaciones-service/src/openapi.json) |

## Colección de Postman (Reto 3)

Importar [`docs/reto3/Reto3.postman_collection.json`](docs/reto3/Reto3.postman_collection.json).
Variable `gateway_url` = `http://localhost:8080`.

Con el compose arriba, en Postman:

1. Carpetas **0–3** (sistema sano): health del Gateway, enrutamiento, departamentos, empleados.
2. Carpeta **1**: las peticiones de acceso directo a `:8081`/`:8082` **deben fallar** (conexión
   rechazada). Esa pantalla de error es la evidencia del punto de entrada único.
3. Carpeta **4**: en Docker Desktop, **Stop** `departamentos-service` o `empleados-service` y
   envía las peticiones A o B. El Gateway sigue UP y responde `503` JSON. Luego **Start** otra
   vez.
4. Carpeta **5**: detén `departamentos-service` y ejecútala completa. Las tres primeras altas
   deben tardar segundos y las siguientes milisegundos, todas con `PENDIENTE_VALIDACION`.
5. Inicia `departamentos-service`, espera 35 segundos y ejecuta la carpeta **6**. Comprueba
   `HALF_OPEN → CLOSED` y la reconciliación de pendientes.

La colección de Reto 2 (`docs/reto2/Reto2.postman_collection.json`) apunta a dos puertos y
quedó obsoleta para el tráfico público.

## Reto 3 — avance

Plan: [`docs/reto3/PLAN-RETO3.md`](docs/reto3/PLAN-RETO3.md). Avance:
[`docs/reto3/STATUS.md`](docs/reto3/STATUS.md). Colección:
[`docs/reto3/Reto3.postman_collection.json`](docs/reto3/Reto3.postman_collection.json).

### Etapa 1 — `api-gateway` (Node.js)

Tercer microservicio, en un lenguaje distinto a Java y Go:

- Express + `http-proxy-middleware`. Enruta `/empleados/*` y `/departamentos/*` **sin** strip
  de path; propaga cuerpo, query, cabeceras y código de estado.
- `GET /health` propio (`200` `{ "status": "UP", "service": "api-gateway" }`), independiente
  de los backends.
- Si el destino no responde: `503` JSON descriptivo (`mensaje` + `servicio`), no HTML de
  Express.
- Dockerfile `node:22-alpine`. Tests: `cd services/api-gateway && npm test` (10/10).

Se eligió Node.js + Express porque el Gateway es código de aplicación: en retos posteriores debe
validar JWT/JWKS, propagar identidad y permitir composición de respuestas. Además introduce un
tercer lenguaje sin repetir Java ni Go. Traefik/Nginx quedan para balanceo, no sustituyen este
borde de aplicación.

| Ruta pública | Destino interno |
|---|---|
| `GET /health` | El propio Gateway; no consulta backends |
| `/empleados` y `/empleados/*` | `http://empleados-service:8080` conservando path, query, cuerpo, cabeceras y status |
| `/departamentos` y `/departamentos/*` | `http://departamentos-service:8081` conservando path, query, cuerpo, cabeceras y status |

README del servicio: [`services/api-gateway/README.md`](services/api-gateway/README.md).

### Etapa 2 — borde único en Compose

- `api-gateway` es el único servicio con `ports:` (`8080:8080`).
- `empleados-service` y `departamentos-service` usan `expose:` (8080 y 8081 internos).
- En Docker Desktop, Stop de un backend + petición por Postman a esa ruta = `503` JSON del
  Gateway; `/health` del borde sigue `UP`.
- El Gateway solo usa `depends_on: service_started`: arranca aunque un backend no llegue a
  saludable y puede responder su propio `/health` y el `503` descriptivo.

### Etapa 3 — Circuit Breaker y fallback

La llamada `empleados → departamentos` está protegida por Resilience4j para Spring Boot 4.
Los reintentos del Reto 2 se conservan dentro de `CLOSED`; cada agotamiento completo cuenta como
un solo fallo lógico del circuito.

| Parámetro | Valor | Motivo |
|---|---|---|
| Instancia | `departamentos` | Protege exactamente la dependencia síncrona del consumidor |
| Ventana / mínimo de llamadas | `3` / `3`, `COUNT_BASED` | Tres fallos lógicos seguidos abren el circuito |
| Umbral | `100 %` | Con ventana de tres, exige que fallen las tres |
| Tiempo en `OPEN` | `30s` | Después permite comprobar recuperación sin reiniciar |
| Llamadas en `HALF_OPEN` | `1` | Un éxito cierra; un fallo vuelve a abrir |
| Timeout HTTP | `5s` | Límite de cada intento hacia departamentos |
| Backoff | `1s → 2s → 4s` | Conserva tolerancia a fallos transitorios |
| Timeout del Gateway | `35s` | Supera el peor caso de 4×5s + 7s de backoff |

Si la dependencia no responde, se prioriza disponibilidad: el alta devuelve `201` y se persiste
como `PENDIENTE_VALIDACION`, nunca con un departamento inventado. Cuando departamentos vuelve,
`POST /empleados/reconciliar` revalida cada pendiente; solo los departamentos existentes pasan a
`ACTIVO`. `GET /empleados/circuit-breaker` expone `CLOSED`, `OPEN` o `HALF_OPEN`.

### Etapa 4 — pruebas y evidencias

La ejecución real del 21 de septiembre de 2026 quedó documentada en
[`docs/reto3/EVIDENCIAS.md`](docs/reto3/EVIDENCIAS.md). Resultado central:

| Solicitud con departamentos detenido | Tiempo | Estado persistido |
|---|---:|---|
| 1 | 11.155s | `PENDIENTE_VALIDACION` |
| 2 | 9.645s | `PENDIENTE_VALIDACION` |
| 3 | 9.704s | `PENDIENTE_VALIDACION`; circuito abre |
| 4–8 | 33–39ms | `PENDIENTE_VALIDACION`; sin llamada de red |

Al restaurar departamentos, el circuito pasó automáticamente a `HALF_OPEN`. Un alta con
`departamentoId: NO-EXISTE` respondió `400` en 180ms y lo llevó a `CLOSED`, sin reiniciar
empleados ni el Gateway. La reconciliación posterior evaluó 16 pendientes y activó los 16.

## Qué se implementó en el Reto 2 (etapas 1 y 2 de ese plan)

Detalle del plan: [`docs/reto2/PLAN-RETO2.md`](docs/reto2/PLAN-RETO2.md). Avance histórico:
[`docs/reto2/STATUS.md`](docs/reto2/STATUS.md).

### Etapa 1 — `departamentos-service` (Go)

Segundo microservicio, en un lenguaje distinto al de empleados:

- Go + [chi](https://github.com/go-chi/chi) + MySQL 8.4, sin ORM (`database/sql`).
- Endpoints: `POST /departamentos` (201 / 400 si el id ya existe), `GET /departamentos/{id}` (200 / 404), `GET /departamentos` (200).
- `GET /health` real: hace `PING` a MySQL (`200 UP` / `503 DOWN`). Lo usa Docker como healthcheck del contenedor.
- Esquema versionado con `golang-migrate` (`db/migrations/0001_create_departamentos.up.sql` / `.down.sql`), aplicado al arrancar. `id` es `PRIMARY KEY` (unicidad en el esquema); el error 1062 se traduce a 400.
- Dockerfile multi-stage. Variables de entorno (nunca hosts/credenciales hardcodeados).

README del servicio: [`services/departamentos-service/README.md`](services/departamentos-service/README.md).

### Etapa 2 — `docker-compose.yml` raíz

Orquestación de los 4 contenedores con arranque ordenado:

- Servicios: `empleados-service`, `departamentos-service`, `database-empleados` (Postgres), `database-departamentos` (MySQL).
- Red interna `microservices-network`: los servicios se resuelven por nombre (p. ej. `http://departamentos-service:8081`). Nunca `localhost` entre contenedores.
- Un volumen por base (`vol-empleados`, `vol-departamentos`): `docker compose down` conserva datos; `down -v` los borra.
- Healthchecks reales: `pg_isready` en Postgres; `mysqladmin ping --protocol=tcp -h 127.0.0.1` en MySQL (el ping por socket Unix daba falso "healthy" durante el mysqld temporal de inicialización).
- `depends_on: condition: service_healthy`: cada API espera a su BD; `empleados-service` también espera a `departamentos-service`.
- Credenciales y URLs inyectadas desde `.env` (plantilla: `.env.example`).
- El healthcheck de `empleados-service` apunta a `GET /health` (Etapa 3).

### Etapa 3 — evolución de `empleados-service`

- Liquibase versiona el esquema (`UNIQUE` en `email` y `numeroEmpleado`); Hibernate en `validate`.
- `POST /empleados` responde **201**. Duplicados y departamento inexistente responden **400**.
  `GET /empleados` lista todos los registrados (**200**), como pide el §4 del enunciado.
- Cliente HTTP a `departamentos-service`: timeout 3s, 4 intentos, backoff 1s→2s→4s. Si se agotan, **503** y no persiste.
- `GET /health` hace PING real a PostgreSQL (`200 UP` / `503 DOWN`).

### Etapa 5 — pruebas, evidencia y documentación final

Verificado end-to-end con `docker compose up --build` (Docker Engine 28.4). Evidencia real:

**Arranque ordenado — `docker compose ps` tras un `docker compose down -v && up --build` desde cero:**

```
NAME                             IMAGE                        STATUS
micro-database-departamentos-1   mysql:8.4                    Up (healthy)
micro-database-empleados-1       postgres:16-alpine           Up (healthy)
micro-departamentos-service-1    micro-departamentos-service  Up (healthy)
micro-empleados-service-1        micro-empleados-service      Up (healthy)
```

Sin errores de conexión en los logs de ningún servicio.

**Persistencia — contraste `down` vs `down -v`:**

```bash
$ docker compose down && docker compose up -d   # SIN -v
$ curl http://localhost:8080/empleados/E001
{"id":"E001", ... "estado":"ACTIVO"}            # 200 — el dato sobrevive

$ docker compose down -v && docker compose up -d --build   # CON -v
$ curl http://localhost:8080/empleados/E001
{"mensaje":"El empleado con id E001 no existe"}  # 404 — se perdió con el volumen
```

**Flujo completo del §8 del PDF**, incluida la validación cruzada real entre servicios: crear
departamento `IT` → registrar empleado con `departamentoId: "IT"` → `201`, conservando los 10
campos del modelo canónico. Las cuatro validaciones responden `400`: id duplicado, email
duplicado, `numeroEmpleado` duplicado y departamento inexistente.

**Tolerancia a fallos real** (no solo en teoría): con `docker compose stop
departamentos-service`, registrar un empleado tarda ~8-20s (los reintentos con backoff
1s→2s→4s) y responde `503` sin persistir nada; `docker compose start departamentos-service` y
el mismo request vuelve a responder `201` normalmente.

**Colección Postman** (`Reto2.postman_collection.json`) corrida con `newman` contra el sistema
real: 17 requests / 34 assertions en las carpetas 0-2, y 3 assertions en la carpeta de
resiliencia — 0 fallos en ambas corridas.

**Swagger UI** accesible en ambos servicios (`empleados-service` redirige `/swagger-ui.html` →
`/swagger-ui/index.html`, código 200 final; `departamentos-service` sirve `/swagger/index.html`
directo).

## Decisiones técnicas (Reto 2)

Resumen — el detalle y el "por qué" de cada una está en `docs/PLAN-RETO2.md` y en el README de
cada servicio afectado:

1. **Motor de BD por servicio**: PostgreSQL para empleados, MySQL para departamentos
   (persistencia poliglota deliberada).
2. **Creación de esquema**: changelogs versionados con rollback — Liquibase en `empleados-service`,
   `golang-migrate` en `departamentos-service`. Nunca auto-DDL de ORM como mecanismo definitivo.
3. **Garantía de unicidad**: restricción a nivel de esquema (`UNIQUE`/`PRIMARY KEY`) además de
   consulta previa desde el código, para no depender solo de una ventana de "consultar y luego
   insertar".

## Message broker (Reto 4, criterio 1)

El onboarding deja de ser solo HTTP: un alta tiene que avisar a otros dominios sin que
`empleados-service` los conozca. El broker es **RabbitMQ 3** (`rabbitmq:3-management`), servicio
`message-broker` en el compose.

Se eligió RabbitMQ porque este flujo es un aviso a varios servicios, cada uno con su cola, y el
enunciado pide republicar el mismo mensaje desde una consola de administración. RabbitMQ trae
esa UI en `:15672`, un exchange topic y colas durables. La routing key es el `type` del evento.
Si un consumidor está caído, su cola conserva la copia y los demás siguen.

| Opción | Por qué no es la de este sistema |
|---|---|
| **Kafka** | El fan-out de este volumen es un exchange y una cola por consumidor, no un log de particiones. Además hay que sumar otra consola para republicar un mensaje, y el enunciado pide hacerlo desde una UI de administración. |
| **Redis Streams** | Sirve como log ligero, pero no trae una consola para publicar el mismo envelope dos veces. Esa republicación es la evidencia de deduplicación del criterio 3. |
| **NATS** | Encaja en mensajería simple y tampoco trae esa consola de administración. |
| **RabbitMQ** | Trae la management UI en `:15672`, exchanges topic y colas durables. Un `basic.publish` con routing key = `type` llega a cada cola ligada a esa key. Si un consumidor se cae, los demás siguen. |

Dentro de la red los servicios hablarán `message-broker:5672`. En el host, `5672` permite
inspeccionar AMQP y `15672` es la UI del paso 9 del enunciado. El usuario y la contraseña salen
de `RABBITMQ_USER` y `RABBITMQ_PASSWORD`. El volumen `vol-rabbitmq` monta `/var/lib/rabbitmq`:
`docker compose down` conserva colas durables; `down -v` las borra. El `hostname` del contenedor
está fijo en `message-broker` porque el nombre del nodo queda guardado en ese volumen.

### Correo con Mailhog

Mailhog es un SMTP de desarrollo. No entrega a internet: guarda el mensaje para verlo en
[http://localhost:8025](http://localhost:8025). El puerto de la bandeja es `8025`. El SMTP,
`1025`, solo existe dentro de la red Docker.

El compose inyecta en `notificaciones-service`:

| Variable | Valor | Para qué |
|---|---|---|
| `SMTP_HOST` | `mailhog` | Nombre del contenedor |
| `SMTP_PORT` | `1025` | Puerto SMTP |
| `SMTP_FROM` | `onboarding@empresa.com` | Remitente |

Si `SMTP_HOST` queda vacío, no se envía correo. En el compose siempre apunta a `mailhog`.

Después de guardar la fila y escribir el log `[NOTIFICACIÓN]`, el servicio manda ese mismo
`mensaje`. El asunto depende del tipo:

| Tipo | Asunto |
|---|---|
| `BIENVENIDA` | Bienvenida |
| `DESVINCULACION` | Desvinculación |
| `VACACIONES` | Vacaciones programadas |

Si Mailhog no responde, el error queda en el log. La fila ya está guardada y el mensaje de
RabbitMQ se confirma. El mismo `id` de evento no manda un segundo correo, porque la
deduplicación ocurre antes de registrar la notificación.

Para ver uno: con el compose arriba, abre la bandeja y crea un empleado que todavía no exista
(`POST /empleados`). Los avisos anteriores no se reenvían.

El código está en `notificaciones-service`:

- `src/notificaciones/config.py`: `Settings` lee `SMTP_HOST`, `SMTP_PORT` y `SMTP_FROM`.
- `src/notificaciones/correo.py`: `crear_enviador` abre la conexión SMTP y manda el mensaje.
- `src/notificaciones/consumidor.py`: al arrancar crea el enviador y se lo pasa a `procesar`.
- `src/notificaciones/procesar.py`: guarda la fila, escribe el log y llama al enviador. El mapa `ASUNTOS` define el asunto.

Al arrancar, cada servicio declara de forma idempotente el exchange topic `onboarding.eventos`
y su cola:

| Cola | Bindings |
|---|---|
| `q.notificaciones` | `empleado.creado`, `empleado.retirado`, `vacaciones.programadas` |
| `q.perfiles` | `empleado.creado`, `empleado.actualizado`, `empleado.retirado` |
| `q.vacaciones` | `empleado.creado`, `empleado.retirado` |

La routing key es el `type` del evento. El ack es manual y el prefetch es 1. Esas colas quedan
en 0 porque el consumidor confirma el mensaje al recibirlo. Para ver el JSON, crea antes del
POST una cola `q.prueba` atada a `onboarding.eventos` y léela con Get messages, Ack requeue false.

### Una copia por cola

RabbitMQ no guarda un solo mensaje y espera a que lo lean N servicios. Al publicar, el exchange
copia el mensaje en cada cola atada a esa routing key. Cada cola tiene su copia y su propio ack.

Con `empleado.creado` quedan tres copias: `q.vacaciones`, `q.notificaciones` y `q.perfiles`. Si
vacaciones ya confirmó y notificaciones todavía no, la copia de `q.vacaciones` desaparece y la
de `q.notificaciones` sigue hasta que ese servicio haga ack. Una cola no bloquea a las otras.
No hay un contador de servicios pendientes: el reparto ocurrió al publicar.

La cola no es el almacén del negocio. Vacaciones, al consumir `empleado.creado`, guarda
`empleadoId`, `email` y `estado` en `empleados_replica`, tabla de su propia base, y después
confirma. Un `POST /vacaciones` posterior lee esa tabla. El detalle de por qué es una réplica y
no un GET a empleados está en [Réplica de empleados en vacaciones](#réplica-de-empleados-en-vacaciones).

## Estado del proyecto

Reto 2: ver [`docs/reto2/STATUS.md`](docs/reto2/STATUS.md). Todas las etapas de ese plan (0-5)
están implementadas y verificadas end-to-end.

Reto 3: ver [`docs/reto3/STATUS.md`](docs/reto3/STATUS.md). Todas las etapas 0–4 están
implementadas y verificadas: Gateway, borde único, Circuit Breaker, fallback, recuperación,
reconciliación, colección Postman y evidencias reproducibles.

Reto 4: ver [`docs/reto4/STATUS.md`](docs/reto4/STATUS.md). Las etapas 0 a 7 están cerradas.
La evidencia de la corrida está en [`docs/reto4/EVIDENCIAS.md`](docs/reto4/EVIDENCIAS.md).

## Eventos (Reto 4)

Contrato del catálogo [`docs/reto4/catalogo-de-eventos.pdf`](docs/reto4/catalogo-de-eventos.pdf).
El envelope no cambia entre tipos:

```json
{
  "id": "uuid",
  "type": "empleado.creado",
  "version": 1,
  "occurredAt": "2027-03-01T10:00:00Z",
  "producer": "empleados-service",
  "data": {}
}
```

`id` es la clave de deduplicación. Republicar el mismo `id` no repite el efecto.

| `type` | `data` | Quién publica | Quién consume |
|---|---|---|---|
| `empleado.creado` | `empleadoId`, `nombre`, `apellido`, `email`, `numeroEmpleado`, `cargo`, `area`, `departamentoId`, `fechaIngreso`, `estado` | empleados | perfiles, notificaciones, vacaciones |
| `empleado.actualizado` | `empleadoId`, `nombre`, `apellido`, `email`, `cargo`, `area`, `departamentoId` | empleados | perfiles |
| `empleado.retirado` | `empleadoId`, `email`, `fechaRetiro`, `motivo` | empleados | perfiles, notificaciones, vacaciones |
| `vacaciones.programadas` | `vacacionesId`, `empleadoId`, `email`, `fechaInicio`, `fechaFin`, `diasHabiles` | vacaciones | notificaciones |

`empleado.retirado` usa `motivo` `RENUNCIA` cuando el `DELETE` no trae cuerpo. `diasHabiles`
cuenta lunes a viernes, inclusive, sin festivos. Del 15 al 30 de marzo de 2027 son 12.

El alta y el retiro de este reto disparan `BIENVENIDA` y `DESVINCULACION`. El catálogo reserva
`usuario.creado` y `cuenta.desactivada` para el reto de autenticación, que todavía no existe.
`vacaciones.iniciadas` y `vacaciones.finalizadas` tampoco se publican aquí.

## Réplica de empleados en vacaciones

Vacaciones no llama a empleados por HTTP para aceptar un período. Guarda una réplica con
`empleado.creado` (id, email, estado) y la pasa a `RETIRADO` con `empleado.retirado`. Así puede
programar vacaciones aunque empleados esté caído. El costo es la ventana hasta que el evento
llega: un alta recién hecha puede responder 400 `El empleado con id {id} no existe`, y un retiro
recién hecho puede aceptar un período unos segundos más. El `POST` reintenta esa espera. No
consume `empleado.actualizado`: el email del evento de vacaciones es el del alta.

## Cómo repetir el flujo

Fechas de ejemplo: **2027-03-15** a **2027-03-30**. No uses junio de 2026: esa fecha ya pasó y
`fechaInicio` anterior a hoy responde 400.

1. `docker compose up --build` y espera `healthy`.
2. Si `localhost` se queda colgado, usa `http://127.0.0.1:8080`.
3. Importa [`docs/reto4/Reto4.postman_collection.json`](docs/reto4/Reto4.postman_collection.json) y corre las carpetas 0 a 7 en orden. Hace falta una base vacía: un segundo `POST` de `E001` o de `IT` no es 201.
4. Carpeta 8, a mano: en `http://localhost:15672` publica dos veces el mismo JSON en `onboarding.eventos` con routing key `empleado.creado`. El `id` del envelope no se cambia. Después `GET /notificaciones/E002` trae una sola `BIENVENIDA` y `GET /perfiles/E002` un solo perfil.
5. Carpeta 9, a mano: `8081`, `8083`, `8084` y `8085` rechazan la conexión.

Para correr las carpetas automáticas con Newman, sobre datos limpios:

```bash
npx newman run docs/reto4/Reto4.postman_collection.json \
  --folder "0. Salud y departamento" \
  --folder "1. Alta y fan-out" \
  --folder "2. Actualizar empleado" \
  --folder "3. Perfil por REST" \
  --folder "4. Programar vacaciones" \
  --folder "5. Validaciones de vacaciones" \
  --folder "6. Consultar y cancelar" \
  --folder "7. Retiro y auditoria" \
  --env-var "gateway_url=http://127.0.0.1:8080"
```

Swagger de cada servicio responde dentro de la red Docker. Desde el navegador se abre el del
Gateway: [http://127.0.0.1:8080/swagger](http://127.0.0.1:8080/swagger).
