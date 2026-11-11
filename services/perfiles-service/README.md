# perfiles-service

Réplica del perfil de cada empleado. Java 21, Spring Boot, PostgreSQL propio y Spring AMQP.
No comparte carpeta ni base con `empleados-service`. No publica eventos: el `PUT` solo edita
los campos de RRHH.

## Por qué estos eventos

| Evento | Efecto |
|---|---|
| `empleado.creado` | Inserta el perfil. `archivado` nace en `false`. Teléfono, dirección, ciudad y biografía nacen en `""`. `nombre` y `email` salen del evento |
| `empleado.actualizado` | Copia `nombre` y `email`. No pisa teléfono, dirección, ciudad, biografía ni `archivado` |
| `empleado.retirado` | `archivado = true`. La fila se queda, así el `GET` sigue respondiendo 200 |

La cola es `q.perfiles`, durable, con ack manual y prefetch 1. Antes de cualquier efecto se busca
el `id` del envelope en `eventos_procesados`. El mismo evento no crea un segundo perfil. Si el
alta ya dejó un perfil y llega otro `empleado.creado` con otro `id`, tampoco se inserta otra fila.

Si `empleado.actualizado` o `empleado.retirado` llegan antes del alta, se registra el error y se
confirma el mensaje. No se inventa un perfil y no se reencola: un reintento no traería el alta.

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| `GET` | `/perfiles/{empleadoId}` | 200 con el perfil, o 404 `{"mensaje":"El perfil del empleado {id} no existe"}` |
| `PUT` | `/perfiles/{empleadoId}` | 200. Editables: `telefono`, `direccion`, `ciudad`, `biografia`. Un campo ausente no se borra. 404 si no hay perfil |
| `GET` | `/perfiles` | 200, arreglo |
| `GET` | `/health` | 200 solo si PostgreSQL responde. Lo usa Docker, no el Gateway |
| `GET` | `/swagger-ui.html` | OpenAPI de Springdoc |

El cuerpo incluye `id`, `empleadoId`, `nombre`, `email`, `telefono`, `direccion`, `ciudad`,
`biografia`, `fechaCreacion` y `archivado`.

## Variables

| Variable | Compose | Descripción |
|---|---|---|
| `PORT` | `8083` | Puerto interno. No se publica al host |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `database-perfiles` | Base propia `perfiles_db` |
| `BROKER_URL` | `amqp://message-broker:5672` | Sin credenciales en la URL |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | las del broker | Usuario AMQP |
| `BROKER_EXCHANGE` | `onboarding.eventos` | Exchange topic |
| `BROKER_QUEUE` | `q.perfiles` | Bindings: `empleado.creado`, `empleado.actualizado`, `empleado.retirado` |

El esquema lo crea Liquibase al arrancar (`001-create-perfiles`), con rollback que borra las dos tablas.

## Pruebas

```bash
./mvnw -q -Dtest=PerfilEventoServiceTest test
```

## Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `build` | `eclipse-temurin:21.0.9_10-jdk-alpine` | Maven Wrapper, `dependency:go-offline` y `package -DskipTests` |
| `runtime` | `eclipse-temurin:21.0.9_10-jre-alpine` | JRE, `curl`, `app.jar`, agente OpenTelemetry `2.33.1` y `docker-entrypoint.sh` |

- **Usuario:** `app` de sistema con UID/GID fijos `10001:10001`, sin home ni shell de login
  (`USER 10001:10001`). `app.jar`, el agente y el entrypoint quedan con dueño `root`, de solo
  lectura (el entrypoint, 0755). Tomcat y la JVM solo escriben en `/tmp` (1777 en la imagen
  base). La foto es una URL (`foto_url`) guardada en PostgreSQL; no hay subida de archivos a disco.
- **Secretos:** `docker-entrypoint.sh` lee `DB_PASSWORD_FILE` y `RABBITMQ_PASSWORD_FILE` y falla
  con mensaje claro si no puede leerlos. Como el proceso ya no es root, `scripts/setup-secrets.*`
  deja los archivos 0644 dentro de `secrets/` 0700 (ver
  `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Por qué multi-stage y JRE:** el JDK, Maven, `~/.m2` y el código fuente se quedan en `build`;
  el JRE basta para ejecutar el fat jar y pesa bastante menos que el JDK.
- **Por qué no root:** el consumidor de eventos no puede reescribir su jar, el agente ni instalar
  paquetes.
- **Base fijada:** versión exacta de Temurin (`21.0.9_10`) en ambas etapas y del agente
  OpenTelemetry (`ARG OTEL_JAVA_AGENT_VERSION=2.33.1`).
- `curl` se mantiene porque el healthcheck de Compose ejecuta `curl -f http://localhost:8083/health`.
- `.dockerignore` deja fuera `target`, `src/test`, IDE, `.env` y `secrets`.
