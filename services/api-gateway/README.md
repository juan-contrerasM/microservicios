# api-gateway

Punto de entrada único del ecosistema — Reto 3. Gateway de **aplicación** (código), no un
enrutador declarativo tipo Traefik/Nginx.

**Node.js 22 + Express + [`http-proxy-middleware`](https://github.com/chimurai/http-proxy-middleware).**
Tercer lenguaje del monorepo: Java ya es `empleados-service` y Go ya es `departamentos-service`.
Si se permitiera repetir lenguaje, el PDF (§1.3) usaría Spring Cloud Gateway; aquí no, porque el
curso exige un lenguaje nuevo por servicio.

No hay base de datos. No hay lógica de negocio (ni validar empleados, ni Circuit Breaker). El
Gateway enruta, propaga cuerpo/cabeceras/código de estado y responde 503 JSON propio cuando el
destino no contesta. El Circuit Breaker vive en `empleados-service` (Etapa 3).

## Tabla de rutas

| Ruta externa (cliente → Gateway) | Destino interno | Notas |
|---|---|---|
| `GET /health` | el propio Gateway | `{ "status": "UP", "service": "api-gateway" }`. No hace ping a los backends. |
| `/empleados` y `/empleados/*` | `{EMPLEADOS_URL}/empleados` y `/empleados/*` | Path **sin** strip. Un `201`/`400` del backend se reenvía tal cual. |
| `/departamentos` y `/departamentos/*` | `{DEPARTAMENTOS_URL}/departamentos` y `/departamentos/*` | Igual: query, cuerpo y status se conservan. |

Cualquier otra ruta responde `404` JSON (`{"status":404,"mensaje":"Recurso no encontrado"}`),
nunca una página HTML de Express.

Si el destino no responde (conexión rechazada, timeout, reset):

```json
{
  "status": 503,
  "mensaje": "El servicio de departamentos no está disponible",
  "servicio": "departamentos-service"
}
```

El campo `servicio` distingue `empleados-service` y `departamentos-service`.

## Variables de entorno

Nunca se hardcodean hosts. En Compose las inyecta el `docker-compose.yml` raíz (Etapa 2).

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP del Gateway |
| `EMPLEADOS_URL` | *(obligatoria)* | Origen interno, p. ej. `http://empleados-service:8080` |
| `DEPARTAMENTOS_URL` | *(obligatoria)* | Origen interno, p. ej. `http://departamentos-service:8081` |
| `PROXY_TIMEOUT_MS` | `10000` | Timeout del proxy hacia cada backend, en milisegundos |

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
prefijo `/empleados/{id}`, y `503` JSON cuando el destino no acepta conexión.

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
