# api-gateway

## Ficha del servicio (plantilla C6)

1. **Propósito:** único borde de negocio: autentica (JWT), autoriza (RBAC y propiedad del perfil),
   enruta a los seis servicios con resiliencia y publica la Swagger UI agregada.
2. **Tecnología:** Node.js 22 + Express, `jsonwebtoken`, `fetch` nativo. Sin base de datos. No se usa
   Spring Cloud Gateway para no repetir Java/Spring Boot.
3. **Endpoints** (contrato: [`openapi.yaml`](openapi.yaml); UI agregada en `/swagger`): todas las
   rutas de negocio de los seis servicios (`/auth/*`, `/empleados*`, `/departamentos*`, `/perfiles*`,
   `/vacaciones*`, `/notificaciones*`) más `GET /health`, `GET /health/services` (salud agregada de
   los seis), `GET /metrics`, `GET /openapi.json` y `GET /swagger/specs/{servicio}`. Tabla completa en el [README raíz](../../README.md#5-endpoints-principales-del-api-gateway).
4. **Eventos:** no publica ni consume (`N/A`).
5. **Configuración:** `PORT`, `*_URL` de cada servicio (vía el balanceador: `http://<servicio>-lb:8000`),
   `*_HEALTH_URL` (directa, para `/health/services`), `HEALTH_SERVICES_TIMEOUT_MS`,
   `HEALTH_SERVICES_CACHE_MS`, `JWT_SECRET_FILE` (≥ 32 bytes), `PROXY_TIMEOUT_MS` y `*_PROXY_TIMEOUT_MS`,
   `PROXY_RETRY_DELAYS_MS`, `CB_FAILURE_THRESHOLD`, `CB_OPEN_DURATION_MS`, `CORS_ORIGINS`,
   `MAX_BODY_BYTES`, `AUTH_RATE_LIMIT_MAX`, `AUTH_RATE_LIMIT_WINDOW_MS`, `OTEL_*`.
6. **Migraciones:** `N/A` (sin base de datos).
7. **Pruebas:** `npm test`: JUnit y LCOV en `reports/`, umbral 70 %. 91 pruebas, cobertura 97,65 %.
   La integración levanta servidores HTTP reales como backends (JWT, RBAC, proxy, 503 y circuito).
8. **Observabilidad:** prom-client (`/metrics`), logs JSON C5, OpenTelemetry. Negocio/resiliencia:
   `gateway_upstream_errors_total{servicio,causa}` y `gateway_circuit_breaker_state{servicio}`.
9. **Resiliencia:** circuito independiente por backend, timeout por servicio, reintento 1–2–4 s solo
   en `GET`, fallback 503 C3; `/health` propio sin consultar backends (el borde sigue UP) y
   `/health/services` aparte para ver el estado de los seis.
10. **Seguridad y decisiones:** cabeceras seguras (CSP, `X-Frame-Options`, `nosniff`), CORS por lista
    blanca, límite de tamaño de cuerpo (413) y rate limiting por IP en login (fallos), reset (fallos)
    y recuperación (todas): 429 con `Retry-After`. El límite vive en memoria de cada réplica: con N
    réplicas es N veces mayor, suficiente para frenar fuerza bruta. Escala detrás del balanceador.


Punto de entrada único del ecosistema — Reto 3. Gateway de **aplicación** (código), no un
enrutador declarativo tipo Traefik/Nginx.

**Node.js 22 + Express + [`http-proxy-middleware`](https://github.com/chimurai/http-proxy-middleware).**
Tercer lenguaje del monorepo: Java ya es `empleados-service` y Go ya es `departamentos-service`.
Si se permitiera repetir lenguaje, el PDF (§1.3) usaría Spring Cloud Gateway; aquí no, porque el
curso exige un lenguaje nuevo por servicio.

No hay base de datos. No valida empleados ni aplica el Circuit Breaker: eso vive en
`empleados-service`. Desde el Reto 5 sí autentica y autoriza: comprueba el JWT HS256 y el rol
antes de proxear, y responde 401 o 403 JSON propio. Si el destino no contesta, responde 503 JSON.
Todos los errores propios siguen el contrato C3 (Fase 5).

## Tabla de rutas

| Ruta externa (cliente → Gateway) | Destino interno | Notas |
|---|---|---|
| `GET /health` | el propio Gateway | Público. `{"status":"UP","service":"api-gateway","version":"1.0.0","checks":{"process":"UP"}}`. No hace ping a los backends ni exige JWT. |
| `GET /health/services` | `/health` directo de los seis servicios | Público. Salud agregada: 200 `UP` o 503 `DEGRADED`/`DOWN` con el estado y los checks de cada servicio. Ver [Salud agregada](#salud-agregada-get-healthservices). |
| `GET /openapi.json` | el propio Gateway | Público. Contrato del borde (copia exportada en [`openapi.yaml`](openapi.yaml)). |
| `GET /swagger` (también `/swagger/` y `/swagger/index.html`) | el propio Gateway | Público. Swagger UI con selector de los siete contratos. |
| `GET /swagger/specs/{servicio}` | `{<SERVICIO>_URL}{ruta del OpenAPI}` | Público. Contrato de cada servicio leído por HTTP; 503 C3 si ese backend no responde. |
| `POST /auth/login`, `POST /auth/recover-password`, `POST /auth/reset-password` | `{AUTH_URL}` | Públicas. El token de reset va en el cuerpo, no en `Authorization`. |
| `/auth` y `/auth/*` (el resto, incluido `POST /auth/change-password`) | `{AUTH_URL}` | Path **sin** strip. Exigen access token. |
| `/empleados` y `/empleados/*` | `{EMPLEADOS_URL}/empleados` y `/empleados/*` | Path **sin** strip. Exigen access token. Un `201`/`400` del backend se reenvía tal cual. |
| `/departamentos` y `/departamentos/*` | `{DEPARTAMENTOS_URL}/departamentos` y `/departamentos/*` | Igual: query, cuerpo y status se conservan. |
| `/notificaciones` y `/notificaciones/*` | `{NOTIFICACIONES_URL}` | Solo si la variable está definida. Si no, la ruta responde 404 del propio Gateway. |
| `/perfiles` y `/perfiles/*` | `{PERFILES_URL}` | Igual. |
| `/vacaciones` y `/vacaciones/*` | `{VACACIONES_URL}` | Igual. |

El resto de rutas de negocio exigen `Authorization: Bearer`. Sin token, con firma mala, expirado
o con `type` = `RESET_PASSWORD`: **401** con `message` `No autenticado` y cabecera
`WWW-Authenticate: Bearer`. Token válido sin permiso: **403** con `message`
`No tiene permisos para realizar esta operación`.

`ADMIN` pasa. `USER` solo lee, puede `POST /auth/change-password` y puede `PUT /perfiles/{empleadoId}`
si ese id es el `sub` del token. Un `PUT /perfiles/E002` con `sub` `E001` es 403, no 401.
El Gateway no reenvía un token inválido.

Cualquier otra ruta, ya autenticada, responde `404` JSON con `message` `Recurso no encontrado`,
nunca una página HTML de Express. Una excepción no controlada responde `500`
`Error interno del servidor` sin la pila (la pila va solo al log).

Si el destino no responde (conexión rechazada, timeout, reset):

```json
{
  "timestamp": "2026-11-09T12:00:00.000Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "El servicio de departamentos no está disponible",
  "path": "/departamentos",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

`message` nombra el backend sin el sufijo `-service`. Las respuestas que sí genera el backend
(2xx, 4xx de negocio) se reenvían sin tocar.

## Contrato HTTP (Fase 5 del proyecto final)

**Error uniforme (C3).** `src/errors.js` arma `{timestamp, status, error, message, path, traceId}`
para los 401, 403, 404, 500 y 503 propios. `traceId` es el del span activo de OpenTelemetry (el
mismo que viaja al backend en `traceparent`); sin span, el del header `traceparent` entrante. Por
qué se quitó el campo `servicio` del 503: C3 fija los seis campos para los siete servicios; el
backend ya está en `message` y en `path`.

**Health (C2).** Se agregó `version` (de `package.json`). Sigue sin ping a los backends: el health
del borde mide el proceso, y un destino caído se ve como 503 solo en sus rutas.

**Auditoría de rutas.** `tests/contrato.test.js` recorre la tabla *Operaciones expuestas* del PDF
(4 de `/auth`, 5 de `/empleados`, 2 de `/departamentos`, 2 de `/perfiles`, 2 de `/vacaciones`) y las
que el Gateway ya tenía (`GET/PUT/DELETE /departamentos/{id}`, `GET /perfiles`, `GET/PUT/DELETE
/vacaciones/{id}`, `forzar-inicio`/`forzar-fin`, `/notificaciones`, `/empleados/circuit-breaker` y
`/reconciliar`): cada una está en el contrato y llega al backend correcto sin cambiar el path. También
comprueba la matriz RBAC de las rutas del PDF para `USER` y sin token. No hay `/auth/register`. El
RBAC no cambió.

**OpenAPI propio (E08).** `src/openapi.json` (lo que sirve `/openapi.json`; la imagen solo copia
`src/`) documenta todas las rutas públicas con schemas, ejemplos, `BearerAuth` y los códigos
2xx/400/401/403/404/409/500/503 de cada una. Es público sin JWT en `/health`, `/swagger*`,
`/openapi.json`, `login`, `recover-password` y `reset-password`; `change-password` exige Bearer.
[`openapi.yaml`](openapi.yaml) es la copia exportada (`npm run openapi:export`); la prueba falla si
difiere del contrato servido o si no es un OpenAPI válido (`@apidevtools/swagger-parser`).
El 409 de los `POST` de alta documenta el contrato C3 de unicidad; hasta que Empleados y
Departamentos cierren 5.EMP/5.DEP pueden seguir respondiendo 400 para un duplicado.

**Swagger UI agregada (`GET /swagger`).** Una sola Swagger UI con un selector de los siete
contratos: `api-gateway` y los seis servicios. Cada entrada es `/swagger/specs/<servicio>`, que el
Gateway resuelve **por HTTP** contra el backend con la misma URL que usa para proxear
(`AUTH_URL`, `EMPLEADOS_URL`, …): no hay hosts nuevos ni variables nuevas en Compose.

| Servicio | Ruta del contrato (default) | Variable para cambiarla |
|---|---|---|
| auth-service | `/openapi.json` (FastAPI) | `AUTH_OPENAPI_PATH` |
| empleados-service | `/v3/api-docs` (Springdoc) | `EMPLEADOS_OPENAPI_PATH` |
| departamentos-service | `/openapi.yaml` (YAML embebido en Go; el Gateway lo convierte a JSON) | `DEPARTAMENTOS_OPENAPI_PATH` |
| perfiles-service | `/v3/api-docs` (Springdoc) | `PERFILES_OPENAPI_PATH` |
| vacaciones-service | `/openapi.json` | `VACACIONES_OPENAPI_PATH` |
| notificaciones-service | `/openapi.json` (FastAPI) | `NOTIFICACIONES_OPENAPI_PATH` |

Decisiones y su porqué:

- **Por HTTP y en cada petición, sin caché:** el contrato sale del servicio que está corriendo,
  así que nunca queda desfasado respecto al código desplegado.
- **Un backend caído no rompe los demás:** cada lectura tiene timeout (`SWAGGER_SPEC_TIMEOUT_MS`,
  3 s) y cualquier fallo (sin conexión, timeout, 5xx, respuesta que no es OpenAPI, URL sin
  configurar) responde **503 C3 solo en esa entrada**; la UI sigue cambiando entre contratos.
- **`servers` reescrito a `/`:** los contratos de Springdoc anuncian el hostname interno
  (`http://empleados-service:8080`), que el navegador no resuelve. Al apuntar al origen del Gateway,
  *Try it out* pasa por el Gateway con el JWT de *Authorize*, igual que un cliente real.
- **`swagger-ui-dist` 5.17.14 servido por el propio Gateway** (`/swagger/ui/*`, versión fijada en
  `package.json`): la UI funciona sin salida a internet/CDN.
- **Público:** `/swagger*` y `/openapi.json` van antes del middleware JWT, igual que `/health` y
  `/metrics`; la UI los necesita antes de que exista un token.

## Variables de entorno

Nunca se hardcodean hosts. En Compose las inyecta el `docker-compose.yml` raíz (Etapa 2).

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP del Gateway |
| `EMPLEADOS_URL` | *(obligatoria)* | Origen interno, p. ej. `http://empleados-service:8080` |
| `DEPARTAMENTOS_URL` | *(obligatoria)* | Origen interno, p. ej. `http://departamentos-service:8081` |
| `NOTIFICACIONES_URL` | *(opcional)* | `http://notificaciones-service:8084`. Si falta, `/notificaciones` no se publica. |
| `PERFILES_URL` | *(opcional)* | `http://perfiles-service:8083` |
| `VACACIONES_URL` | *(opcional)* | `http://vacaciones-service:8085` |
| `AUTH_URL` | *(obligatoria)* | `http://auth-service:8086`, nombre interno del contenedor de autenticación |
| `JWT_SECRET` | *(obligatoria)* | Secreto HS256 compartido con `auth-service`. No se loguea. |
| `SWAGGER_SPEC_TIMEOUT_MS` | `3000` | Timeout de cada lectura de contrato en `/swagger/specs/*` |
| `<SERVICIO>_HEALTH_URL` | la `*_URL` del servicio | Origen directo que consulta `/health/services` (en Compose `http://<servicio>:<puerto>`, sin pasar por `-lb`) |
| `HEALTH_SERVICES_TIMEOUT_MS` | `2500` | Timeout por servicio en `/health/services`; se consultan en paralelo |
| `HEALTH_SERVICES_CACHE_MS` | `2000` | Tiempo durante el que se reutiliza el último resultado de `/health/services` |
| `<SERVICIO>_OPENAPI_PATH` | ver tabla de Swagger | Ruta del OpenAPI dentro de cada backend; opcional |
| `PROXY_TIMEOUT_MS` | `35000` | Timeout general; se conserva como fallback para compatibilidad. |
| `<SERVICIO>_PROXY_TIMEOUT_MS` | `5000` (`35000` Empleados) | Timeout individual de Auth, Empleados, Departamentos, Notificaciones, Perfiles o Vacaciones. |
| `PROXY_RETRY_DELAYS_MS` | `1000,2000,4000` | Backoff antes de los tres reintentos; se aplica exclusivamente a `GET`. |
| `CB_FAILURE_THRESHOLD` | `3` | Fallos definitivos consecutivos que abren el circuito independiente del backend. |
| `CB_OPEN_DURATION_MS` | `30000` | Tiempo en `OPEN` antes de admitir una única prueba `HALF_OPEN`. |

## Resiliencia síncrona

Cada backend tiene un circuito aislado con estados `CLOSED`, `OPEN` y `HALF_OPEN`. Los errores de
red, timeouts y respuestas 5xx cuentan como fallo; al abrirse el circuito el Gateway responde el
contrato C3 con 503 sin esperar al backend. Una recuperación en `HALF_OPEN` cierra el circuito.

Los `GET`, por ser idempotentes, permiten el intento inicial y tres reintentos con backoff
1 s, 2 s y 4 s. Las operaciones que cambian estado (`POST`, `PUT`, `PATCH`, `DELETE`) se intentan
una sola vez para evitar duplicados. Prometheus expone
`gateway_circuit_breaker_state{servicio}` (0 cerrado, 1 semiabierto, 2 abierto) y
`gateway_upstream_errors_total{servicio,causa}`.

## Salud agregada (`GET /health/services`)

Dos endpoints con propósitos distintos:

| Endpoint | Qué mide | Lo usan |
|---|---|---|
| `GET /health` | Solo el proceso del Gateway; siempre 200 si el proceso atiende | Healthcheck de Docker y Traefik |
| `GET /health/services` | El `/health` C2 de los seis servicios de negocio | Personas, monitoreo, la demo |

`/health` no consulta a los backends a propósito: si lo hiciera, la caída de un solo servicio
pondría al Gateway `unhealthy`, Traefik lo sacaría de rotación y se caería todo el sistema, también
las rutas de los servicios sanos.

**Qué pasa cuando falla uno de los seis** (verificado en Docker, ver `EVIDENCIAS.md`):

- `/health/services` responde **503 `DEGRADED`**: el servicio caído en `DOWN` y los demás en `UP`.
  Si caen todos, 503 `DOWN`. Con los seis arriba, 200 `UP`.
- `/health` del Gateway sigue **200** y las rutas de los otros servicios siguen respondiendo.
- Las rutas del servicio caído responden 503 C3 y su circuito se abre (ver Resiliencia síncrona).
- El detalle dice por qué está caído: con su base o el broker caídos, el servicio responde su propio
  503 y se reenvían sus checks (p. ej. `{"database":"DOWN","messageBroker":"UP"}`); si el contenedor
  no responde, `checks: null` y `detail` («sin conexión», «sin respuesta en 2500 ms»).

Decisiones:

- **Consulta directa, no por el balanceador.** Cuando el `/health` de un servicio da 503, Traefik saca
  la réplica de rotación y responde su propio 503 sin cuerpo: se perdería qué check falló. Por eso
  Compose fija `<SERVICIO>_HEALTH_URL=http://<servicio>:<puerto>`. Con varias réplicas, el DNS de
  Compose elige una; el estado por réplica está en Prometheus/Traefik.
- **En paralelo con timeout por servicio (2,5 s)**, sin circuit breaker ni reintentos: un servicio
  colgado no retrasa a los demás y la respuesta llega en menos de 3 s (Empleados tarda ~2,1 s en
  responder 503 con PostgreSQL caído, por eso no 2 s).
- **Público como `/health`**, pero solo se reenvían `status` y checks con valor texto: ninguna URL,
  host ni mensaje interno. Para que no sirva de amplificador (una petición → seis), las peticiones
  simultáneas comparten una consulta y el resultado se reutiliza 2 s (`Cache-Control: no-store`
  hacia el cliente).

```bash
curl -s http://localhost:8080/health/services
```

## Cómo correrlo

El camino normal es el compose de la raíz (Etapa 2): el Gateway es el único puerto al host.

```bash
cp .env.example .env      # si aún no existe
docker compose up --build
```

| Desde el host | Resultado |
|---|---|
| `http://localhost:8080/health` | Gateway `UP` |
| `http://localhost:8080/empleados` | proxy a empleados |
| `http://localhost:8080/departamentos` | proxy a departamentos |
| `http://localhost:8081/...` | conexión rechazada |
| `http://localhost:8082/...` | conexión rechazada |

Para la prueba de `503`: en Docker Desktop, Stop de `departamentos-service` o
`empleados-service` (no apagues el Gateway) y envía la petición por Postman
(carpeta **4** de `docs/reto3/Reto3.postman_collection.json`). `/health` del Gateway sigue `200`.

### Fuera de Compose (solo desarrollo)

Hace falta Node.js 22+. Ya no sirve apuntar a `localhost:8080` de empleados: ese puerto es el
Gateway. Los backends no publican puertos. Usa Compose.

## Tests del módulo

### Fase 8 — cobertura, integración y mutación (proyecto final)

| Aspecto | Valor |
|---|---|
| Comando | `npm test` (JUnit `reports/junit.xml`, LCOV `reports/lcov.info`, falla bajo 70 %) |
| Cobertura | 72 pruebas · 97,03 % de líneas |
| Integración (E14) | Servidores HTTP reales hacen de backends: JWT, RBAC, proxy, 503 y circuito (`bash scripts/test-integration.sh gateway`) |
| Mutación (E17) | `src/auth.js`: USER tratado como ADMIN → `tests/app.test.js` falla (`bash scripts/test-mutation.sh gateway`) |

```bash
cd services/api-gateway
npm ci
npm test
```

Cubre: `/health` propio, propagación de `201`/`400`/cuerpo/cabeceras/query, conservación del
prefijo `/empleados/{id}`, `503` JSON cuando el destino no acepta conexión, y el JWT: 401 sin
token o con firma alterada, 403 si un `USER` borra o edita el perfil de otro, y el proxy cuando
el `USER` edita el suyo o un `ADMIN` borra.

`tests/contrato.test.js` (Fase 5) agrega: auditoría de las rutas del PDF y extra contra backends
simulados, matriz RBAC, formato C3 de 401/403/404/500/503, drift y validez de `openapi.yaml`, y
`/swagger` + `/swagger/specs/*` con backends simulados (JSON, YAML, uno colgado, uno con 500, uno
sin URL): solo la entrada caída responde 503.

El mismo `npm test` falla si la cobertura de líneas baja de 70 % y genera JUnit en
`reports/junit.xml` y LCOV en `reports/lcov.info`. Se miden los nueve módulos con lógica
ejecutable; `index.js`, el bootstrap de telemetría y el contrato JSON se excluyen porque solo
conectan infraestructura o contienen datos estáticos. Medición del 2026-11-11: 97,03 % de líneas
y 83,33 % de ramas.

## Docker

La imagen se construye desde el compose raíz (`build: ./services/api-gateway`). A mano:

```bash
docker build -t api-gateway ./services/api-gateway
```

### Imagen Docker

| Etapa | Base | Contenido |
|---|---|---|
| `deps` | `node:22.21.1-alpine3.22` | `npm ci --omit=dev` sobre `package-lock.json` |
| `runtime` | `node:22.21.1-alpine3.22` | `node`, `curl`, `node_modules` de producción, `package.json` y `src/` |

- **Usuario:** `app` con UID/GID fijos `10001:10001` (`USER 10001:10001`). El código queda con
  dueño `root`, de solo lectura para el proceso; el Gateway no escribe en disco.
- **Por qué multi-stage:** npm, su caché y cualquier artefacto de instalación se quedan en
  `deps`; en `runtime` además se borran `npm`, `npx`, `corepack` y `yarn`, que no se usan para
  arrancar (`CMD ["node", ...]`). Menos tamaño y menos paquetes que reporte el escáner de imagen.
- **Por qué no root:** si el proceso se ve comprometido no puede modificar su propio código ni
  instalar paquetes. Un UID numérico fijo permite verificar `runAsNonRoot` y leer los secretos
  montados en `/run/secrets` (ver `docs/proyecto-final/SEGURIDAD-SECRETOS.md`).
- **Base fijada:** tag completo de Node y Alpine; nada de `latest` ni `22-alpine` flotante, para
  que dos builds del mismo commit den la misma imagen.
- `curl` se mantiene porque el healthcheck de Compose ejecuta `curl -f http://localhost:8080/health`.
- `.dockerignore` deja fuera `node_modules`, pruebas, reportes, `.env` y `secrets`.

## Qué se implementó en la Etapa 1

Scaffold del tercer microservicio, en un lenguaje distinto a Java y Go:

- Express + proxy HTTP resiliente del Gateway de aplicación (no Traefik).
- Rutas `/empleados/*` y `/departamentos/*` sin strip de path.
- Propagación fiel de método, cuerpo, query, cabeceras y código de estado.
- `503` JSON descriptivo si el destino no responde.
- `GET /health` propio, independiente de los backends.
- Dockerfile Alpine + tests con el runner nativo de Node (`node:test` + SuperTest).

## Qué se implementó en la Etapa 2

- Servicio `api-gateway` en el `docker-compose.yml` raíz, único `ports:` (`8080:8080`).
- `empleados-service` y `departamentos-service` con `expose:` (ya no se publican al host).

## Qué se implementó en el Reto 5 (etapa 3)

- Verificación HS256 con `JWT_SECRET` antes de proxear.
- Ruta `/auth` hacia `AUTH_URL`, sin strip.
- 401 si falta el token, la firma no vale o el token es de reset. 403 si el rol no alcanza
  o el `USER` edita el perfil de otro.
