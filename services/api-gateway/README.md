# api-gateway

Punto de entrada único del ecosistema — Reto 3. Gateway de **aplicación** (código), no un
enrutador declarativo tipo Traefik/Nginx.

**Node.js 22 + Express + [`http-proxy-middleware`](https://github.com/chimurai/http-proxy-middleware).**
Tercer lenguaje del monorepo: Java ya es `empleados-service` y Go ya es `departamentos-service`.
Si se permitiera repetir lenguaje, el PDF (§1.3) usaría Spring Cloud Gateway; aquí no, porque el
curso exige un lenguaje nuevo por servicio.

No hay base de datos. No valida empleados ni aplica el Circuit Breaker: eso vive en
`empleados-service`. Desde el Reto 5 sí autentica y autoriza: comprueba el JWT HS256 y el rol
antes de proxear, y responde 401 o 403 JSON propio. Si el destino no contesta, el 503 JSON no
cambia.

## Tabla de rutas

| Ruta externa (cliente → Gateway) | Destino interno | Notas |
|---|---|---|
| `GET /health` | el propio Gateway | Público. `{ "status": "UP", "service": "api-gateway" }`. No hace ping a los backends ni exige JWT. |
| `GET /openapi.json` y `GET /swagger/index.html` | el propio Gateway | Públicos, para poder abrir la UI y pulsar Authorize. `/swagger` redirige a la UI. |
| `POST /auth/login`, `POST /auth/recover-password`, `POST /auth/reset-password` | `{AUTH_URL}` | Públicas. El token de reset va en el cuerpo, no en `Authorization`. |
| `/auth` y `/auth/*` (el resto, incluido `POST /auth/change-password`) | `{AUTH_URL}` | Path **sin** strip. Exigen access token. |
| `/empleados` y `/empleados/*` | `{EMPLEADOS_URL}/empleados` y `/empleados/*` | Path **sin** strip. Exigen access token. Un `201`/`400` del backend se reenvía tal cual. |
| `/departamentos` y `/departamentos/*` | `{DEPARTAMENTOS_URL}/departamentos` y `/departamentos/*` | Igual: query, cuerpo y status se conservan. |
| `/notificaciones` y `/notificaciones/*` | `{NOTIFICACIONES_URL}` | Solo si la variable está definida. Si no, la ruta responde 404 del propio Gateway. |
| `/perfiles` y `/perfiles/*` | `{PERFILES_URL}` | Igual. |
| `/vacaciones` y `/vacaciones/*` | `{VACACIONES_URL}` | Igual. |

El resto de rutas de negocio exigen `Authorization: Bearer`. Sin token, con firma mala, expirado
o con `type` = `RESET_PASSWORD`: **401** `{"status":401,"mensaje":"No autenticado"}`. Token
válido sin permiso: **403** `{"status":403,"mensaje":"No tiene permisos para realizar esta operación"}`.

`ADMIN` pasa. `USER` solo lee, puede `POST /auth/change-password` y puede `PUT /perfiles/{empleadoId}`
si ese id es el `sub` del token. Un `PUT /perfiles/E002` con `sub` `E001` es 403, no 401.
El Gateway no reenvía un token inválido.

Cualquier otra ruta, ya autenticada, responde `404` JSON
(`{"status":404,"mensaje":"Recurso no encontrado"}`), nunca una página HTML de Express.

Si el destino no responde (conexión rechazada, timeout, reset):

```json
{
  "status": 503,
  "mensaje": "El servicio de departamentos no está disponible",
  "servicio": "departamentos-service"
}
```

El campo `servicio` distingue el backend (`empleados-service`, `departamentos-service`, `notificaciones-service`, `perfiles-service`, `vacaciones-service`, `auth-service`). El `mensaje` usa el nombre sin el sufijo `-service`.

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
| `PROXY_TIMEOUT_MS` | `35000` | Timeout del proxy hacia cada backend. Supera el peor caso de empleados (4×5s + backoff 1s→2s→4s ≈ 27s) para no cortar el fallback antes de tiempo. |

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

```bash
cd services/api-gateway
npm ci
npm test
```

Cubre: `/health` propio, propagación de `201`/`400`/cuerpo/cabeceras/query, conservación del
prefijo `/empleados/{id}`, `503` JSON cuando el destino no acepta conexión, y el JWT: 401 sin
token o con firma alterada, 403 si un `USER` borra o edita el perfil de otro, y el proxy cuando
el `USER` edita el suyo o un `ADMIN` borra.

## Docker

La imagen se construye desde el compose raíz (`build: ./services/api-gateway`). A mano:

```bash
docker build -t api-gateway ./services/api-gateway
```

`node:22-alpine` + `curl` para el healthcheck de Compose.

## Qué se implementó en la Etapa 1

Scaffold del tercer microservicio, en un lenguaje distinto a Java y Go:

- Express + `http-proxy-middleware` (Gateway de aplicación, no Traefik).
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
