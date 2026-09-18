# Plan de implementación — Reto 3: API Gateway y Resiliencia Sincrónica

Basado en [`docs/reto3/reto3.pdf`](reto3.pdf). Este documento traza el plan en etapas para que el
equipo pueda repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](STATUS.md). El Reto 2 queda cerrado; no reabrir etapas de
[`docs/reto2/PLAN-RETO2.md`](../reto2/PLAN-RETO2.md) salvo que un cambio de este reto lo exija
(p. ej. el `docker-compose.yml` raíz).

## Qué hay que resolver (y qué no)

El sistema de Reto 2 funciona, pero tiene dos agujeros que el PDF marca como caros si se dejan
para después:

1. **No hay borde.** El cliente habla con `localhost:8080` (empleados) y `localhost:8081`
   (departamentos). Cada servicio nuevo suma un puerto. En el Reto 5 no habría un solo sitio
   donde validar el JWT.
2. **Reintenta contra un servicio que ya sabe que está caído.** El timeout + backoff del Reto 2
   protege un fallo corto. Si `departamentos-service` lleva minutos abajo, `empleados-service`
   se bloquea en reintentos condenados y el fallo se propaga: cascada.

Este reto introduce el **API Gateway** (punto de entrada único) y el **Circuit Breaker** en la
llamada síncrona `empleados → departamentos`.

## Decisiones de arquitectura ya tomadas

Estas decisiones fijan las reglas del juego para todas las etapas; si alguien quiere cambiarlas,
que lo discuta con el equipo antes de tocar el compose raíz.

| Decisión | Elección | Por qué |
|---|---|---|
| Tipo de Gateway | Gateway de **aplicación** (código), no Traefik/Nginx | El PDF lo exige (§1.2). Traefik no valida JWT, no propaga identidad ni compone respuestas; esas tres cosas llegan en Retos 5, 10 y el proyecto final. Traefik aparece en el Reto 11, delante de este Gateway, no en su lugar. |
| Lenguaje de `api-gateway` | **Node.js 22 + Express + `http-proxy-middleware`** | **No puede ser un lenguaje que ya usamos.** El curso exige ≥4 lenguajes distintos y cada reto nuevo añade un servicio en uno diferente (`CLAUDE.md`, Reto 2). Java ya es `empleados-service`; Go ya es `departamentos-service`. Si se permitiera repetir, el PDF (§1.3) haría lo obvio: **Spring Cloud Gateway** sobre el stack Java que ya tenemos. Por eso se descarta Spring Cloud Gateway y también `httputil.ReverseProxy` en Go: no aportan lenguaje nuevo. Quedan Node.js, Python o .NET; se elige Node.js porque es la opción típica del §1.3, Express + `http-proxy-middleware` propaga cuerpo/cabeceras/status sin magia, y más adelante entra JWT/JWKS (Retos 5 y 10) sin cambiar de familia. Cero lógica de negocio. |
| Puerto publicado al host | **Solo `8080`**, el del Gateway | Lo pide el §1.4 y el criterio 2. Coincidencia útil: hoy empleados ya se consume en `:8080`; las URLs de `/empleados` no cambian de puerto, solo se unifica `/departamentos` bajo la misma base. |
| Puertos internos de negocio | Se conservan: empleados `8080`, departamentos `8081` | El PDF ilustra `8081`/`8082` como ejemplo, no como contrato. El §Consideraciones exige compatibilidad con Reto 2: **las rutas internas no cambian**. Solo se pasa de `ports:` a `expose:`. |
| Dónde va el Circuit Breaker | En **`empleados-service`** (quien llama), con **Resilience4j** | El PDF lo deja explícito: el CB protege al consumidor, no al proveedor. Java → Resilience4j (§2.5). Prohibido implementarlo a mano: las métricas del estado las pide el Reto 11. |
| Parámetros del circuito | 3 fallos consecutivos · 30 s en OPEN · timeout de llamada 5 s | Rango del §2.3 (3–5 fallos, 30–60 s). Con 3 fallos y `sleep 35` del §3.3 se ve el salto CLOSED→OPEN y la recuperación sin reiniciar. Hoy el timeout del cliente es 3 s; hay que subirlo a 5 s para alinearlo al enunciado. |
| Reintentos del Reto 2 | Se **conservan dentro de CLOSED** | El §3.3 espera que las primeras 3–5 peticiones tarden *segundos* (timeout + reintentos) y que a partir del umbral respondan casi instantáneas. Un fallo “lógico” (se agotaron los reintentos) cuenta **una** vez para abrir el circuito, no cada intento HTTP. |
| Estrategia de fallback | Registrar con `estado: PENDIENTE_VALIDACION` (disponibilidad) | El PDF (§2.6) ofrece rechazar 503 o aceptar pendiente. Rechazar deja a RRHH parado — es lo que ya hace el Reto 2 y no demuestra una decisión nueva. Aceptar pendiente mantiene el onboarding en marcha y obliga a explicar la reconciliación (criterio 4). **Nunca** asignar un departamento por defecto. |
| `depends_on` empleados → departamentos | Quitar el `service_healthy` entre APIs | Con Circuit Breaker, empleados debe poder arrancar (y responder degradado) aunque departamentos esté caído. Sigue esperando a su propia BD. El Gateway sí espera a ambos para el arranque “feliz”, pero su `/health` propio **no** depende de que los backends estén arriba — si lo hiciera, el escenario del §3.2 dejaría el borde DOWN. |

## Arquitectura objetivo

```
Cliente HTTP (curl / Postman)
        │
        │  única URL base: http://localhost:8080
        ▼
┌───────────────────┐     ports: "8080:8080"     ← único puerto al host
│    api-gateway    │     GET /health propio
│  Node.js/Express  │
└─────────┬─────────┘
          │ red interna microservices-network
    ┌─────┴──────────────────────┐
    │ /empleados/*               │ /departamentos/*
    ▼                            ▼
empleados-service            departamentos-service
expose 8080                  expose 8081
  │ REST + Circuit Breaker
  │ (Resilience4j + fallback)
  ▼
departamentos-service        (sin puertos al host)
        │                            │
        ▼                            ▼
 database-empleados          database-departamentos
 (Postgres, volumen)         (MySQL, volumen)
```

Acceso directo `localhost:8081` / `localhost:8082` → conexión rechazada.

## Etapas

Cada etapa indica **qué entrega**, **de qué depende** y **a qué criterio de evaluación del PDF
corresponde** (los 5 puntos del reto). Varias etapas pueden avanzar en paralelo si no dependen
entre sí — se marca explícitamente cuándo sí hay dependencia dura.

### Etapa 0 — Plan, STATUS y colección de pruebas (infraestructura, sin puntaje directo)

- Este archivo, [`STATUS.md`](STATUS.md) y
  [`Reto3.postman_collection.json`](Reto3.postman_collection.json) en `docs/reto3/`.
- La colección ya apunta a la URL base única `http://localhost:8080` (variable `gateway_url`).
  Las carpetas 0–3 se podrán correr con Newman cuando existan Gateway y compose; las 4–5 son
  manuales (hay que parar un servicio), igual que la carpeta de resiliencia del Reto 2.

No depende de nada. No bloquea el código, pero es la referencia de contrato: no inventar rutas
ni códigos distintos a los de aquí y del PDF.

### Etapa 1 — Scaffold de `api-gateway` (Node.js) — criterio 1 del PDF (1.5 pts)

Carpeta nueva: `services/api-gateway/`. Un microservicio más del ecosistema, no un sidecar
declarativo.

- `package.json` con Express y `http-proxy-middleware`. Variables de entorno, nunca hosts
  hardcodeados: `PORT`, `EMPLEADOS_URL`, `DEPARTAMENTOS_URL`.
- Enrutamiento (path **sin** strip: las rutas internas del Reto 2 se conservan):

  | Ruta externa (cliente → Gateway) | Destino interno |
  |---|---|
  | `GET /health` | el propio Gateway (no se proxea) |
  | `/empleados` y `/empleados/*` | `{EMPLEADOS_URL}/empleados` y `/empleados/*` |
  | `/departamentos` y `/departamentos/*` | `{DEPARTAMENTOS_URL}/departamentos` y `/departamentos/*` |

- Propagación: reenviar método, cuerpo, query string, cabeceras relevantes y **el código de
  estado tal cual**. Un `201` de empleados sigue siendo `201`; un `400` de departamentos sigue
  siendo `400`. El Gateway no reescribe payloads de negocio.
- Si el destino no responde (conexión rechazada, timeout, reset): **503** con JSON propio,
  no stack trace de Express ni página HTML. Contrato propuesto:

  ```json
  {
    "status": 503,
    "mensaje": "El servicio de departamentos no está disponible",
    "servicio": "departamentos-service"
  }
  ```

  El campo `servicio` distingue qué backend falló (`empleados-service` o
  `departamentos-service`).
- `GET /health` propio: `{ "status": "UP", "service": "api-gateway" }`. **No** hace ping a los
  backends (si departamentos está caído el Gateway tiene que seguir UP: criterios 1 y 3 se
  prueban apagando backends).
- `Dockerfile` (imagen `node:22-alpine`). README del servicio: variables, tabla de rutas,
  cómo correrlo en local contra los servicios ya levantados.
- Sin lógica de dominio: ni validar empleados, ni Circuit Breaker aquí. El CB va en quien llama
  (Etapa 3).

Pruebas mínimas del módulo (Jest o similar): proxy de un 201/400 mockeado y el 503 cuando el
target no acepta conexión.

Depende de Etapa 0 solo como contrato. Puede avanzar en paralelo con la Etapa 3.

### Etapa 2 — `docker-compose.yml`: borde único — criterio 2 del PDF (1.0 pts)

- Añadir servicio `api-gateway`:
  - `build: ./services/api-gateway`
  - `ports: ["${GATEWAY_PORT:-8080}:8080"]` — **el único `ports:` de todo el compose**
  - `environment`: `EMPLEADOS_URL=http://empleados-service:8080`,
    `DEPARTAMENTOS_URL=http://departamentos-service:8081`
  - `depends_on` con `condition: service_healthy` hacia `empleados-service` y
    `departamentos-service` (arranque ordenado del borde).
  - `healthcheck` contra `GET http://localhost:8080/health`.
- En `empleados-service` y `departamentos-service`: sustituir `ports:` por `expose:`
  (`"8080"` y `"8081"` respectivamente). Las BDs **siguen sin publicar puertos** (ya era así).
- Actualizar `.env.example`: `GATEWAY_PORT=8080`. Dejar de usar `EMPLEADOS_PORT` /
  `DEPARTAMENTOS_PORT` en el compose (si se conservan, documentar que ya no se publican).
- Verificación obligatoria del PDF (§1.5 / §3.1), con los puertos reales de este repo:

  ```bash
  # A través del Gateway: debe funcionar
  curl http://localhost:8080/empleados
  curl http://localhost:8080/departamentos
  curl http://localhost:8080/health

  # Acceso directo: DEBE FALLAR (conexión rechazada)
  curl http://localhost:8081/departamentos
  curl http://localhost:8082/empleados
  ```

Depende de Etapa 1 (hace falta el `Dockerfile` del Gateway). Las BDs y los dos servicios de
negocio ya existen.

### Etapa 3 — Circuit Breaker en `empleados-service` — criterios 3 y 4 del PDF (1.5 + 0.5 pts)

Solo se toca `services/empleados-service/` (y, si hace falta, variables nuevas en `.env.example`
/ compose). El Circuit Breaker **no** va en el Gateway ni en departamentos.

- Dependencia **Resilience4j** (`resilience4j-spring-boot3` / `resilience4j-circuitbreaker`).
  Envolver la llamada existente de `DepartamentoClient.validarExistencia`.
- Estados demostrables y observables (`CLOSED` / `OPEN` / `HALF_OPEN`). Exponer el estado con
  un endpoint de lectura, p. ej. `GET /empleados/circuit-breaker`, para no depender solo de
  logs a la hora de evidenciar el criterio 3:

  ```json
  { "name": "departamentos", "state": "CLOSED" }
  ```

  Ese path cae de forma natural bajo `/empleados/*` del Gateway.
- Configuración (sobreescribible por env, nunca hardcodeada opaca):

  | Parámetro Resilience4j | Valor | Por qué |
  |---|---|---|
  | `slidingWindowType` | `COUNT_BASED` | Umbral por número de llamadas, no por tiempo. |
  | `slidingWindowSize` / `minimumNumberOfCalls` | 3 | Tres fallos lógicos abren el circuito (rango 3–5 del PDF). |
  | `failureRateThreshold` | 100 | Equivale a fallos consecutivos en una ventana de 3. |
  | `waitDurationInOpenState` | 30s | El §3.3 hace `sleep 35` antes de probar HALF_OPEN. |
  | `permittedNumberOfCallsInHalfOpenState` | 1 | Una llamada de prueba; éxito → CLOSED, fallo → OPEN. |
  | `automaticTransitionFromOpenToHalfOpenEnabled` | true | Recuperación **sin reiniciar** ningún contenedor. |
  | timeout de la llamada HTTP | 5s | §2.3; alinear `DEPARTAMENTOS_SERVICE_TIMEOUT`. |

- Relación con los reintentos del Reto 2: el circuito cuenta **una** llamada de negocio, no
  cada GET interno. Mientras está `CLOSED`, se mantiene el backoff 1s→2s→4s (por eso las
  primeras peticiones del §3.3 tardan segundos). En `OPEN` no se toca la red: fallback
  inmediato.
- Qué cuenta como fallo del circuito: timeout, 5xx, conexión rechazada, agotamiento de
  reintentos. Un **404** de departamentos es éxito del circuito (el servicio respondió) y se
  traduce a `400` al cliente — “departamento inexistente”. El paso 5 del §3.3 se apoya en eso.
- Fallback (criterio 4): si el circuito está OPEN, o si la llamada falla y el circuito pasa a
  OPEN, **sí se persiste** el empleado con `estado: PENDIENTE_VALIDACION` y se responde **201**:

  ```json
  {
    "id": "E010",
    "estado": "PENDIENTE_VALIDACION",
    "mensaje": "Empleado registrado. Validación de departamento pendiente."
  }
  ```

  (El resto de campos canónicos van en el mismo cuerpo; `mensaje` puede ir en el JSON de
  empleado o como cabecera/campo extra — lo importante es que el estado pendiente sea
  consultable después con `GET /empleados/{id}`.)
- Modelo: añadir `PENDIENTE_VALIDACION` a `EstadoEmpleado`. La columna `estado` ya es
  `VARCHAR(255)` en Liquibase; **no hace falta changelog de esquema**, solo el enum Java y
  tests. No usar `EN_VACACIONES` ni `RETIRADO` (siguen siendo de Retos 4/5).
- Reconciliación (obligatoria de documentar en el README porque se eligió la segunda opción
  del §2.6):

  1. Los pendientes quedan persistidos y son listables (`GET /empleados` / `GET /empleados/{id}`).
  2. Cuando departamentos vuelve, el circuito pasa a HALF_OPEN y luego CLOSED **solo** con
     tráfico nuevo (paso 5 del PDF: un alta con `departamentoId: "NO-EXISTE"` debe dar `400`,
     no fallback).
  3. La reconciliación de los ya persistidos **no** es silenciosa ni inventa datos: un proceso
     posterior (endpoint interno `POST /empleados/reconciliar` o un barrido al recuperar el
     circuito) vuelve a consultar `GET /departamentos/{id}` para cada pendiente. Si existe →
     `ACTIVO`. Si no existe → se deja pendiente o se marca para revisión de RRHH; **no** se
     borra en silencio y **no** se asigna un departamento por defecto.
  4. Implementar al menos un mecanismo mínimo y documentado (un endpoint de reconciliación
     disparado a mano basta para este reto; no hace falta un worker asíncrono — eso es Reto 4).

- Tests unitarios: CLOSED (delega al cliente HTTP), OPEN (no llama a la red, persiste
  pendiente), HALF_OPEN (una llamada de prueba), y 404 de departamentos = éxito del circuito
  + 400 al cliente.

Depende de Etapa 0 como contrato. Puede avanzar en paralelo con Etapas 1 y 2. Para evidenciar
el salto de latencia hace falta el compose de la Etapa 2 (parar `departamentos-service` de
verdad).

### Etapa 4 — Pruebas del sistema, evidencias y documentación — criterio 5 del PDF (0.5 pts) + cierre del criterio 2

Depende de que Etapas 1–3 estén terminadas. Cubre el §3 del PDF y los entregables de README.

- Actualizar el **README raíz** (hoy describe Reto 2 con dos puertos). Debe incluir, tal como
  pide el PDF:

  1. Justificación de Node.js + Express para el Gateway (tabla de la Etapa 0).
  2. Tabla de rutas del Gateway (externa → interna).
  3. URL base única del sistema: `http://localhost:8080` — la que usarán Reto 6 (BDD) y
     Reto 8 (Prometheus).
  4. Parámetros del Circuit Breaker y por qué esos valores.
  5. Justificación del fallback (disponibilidad vs. consistencia) y cómo se reconcilia
     `PENDIENTE_VALIDACION`.
  6. Instrucciones para reproducir la prueba del Circuit Breaker (§3.3).
- Actualizar READMEs de `empleados-service` y `departamentos-service`: las URLs de ejemplo
  pasan por el Gateway; Swagger de cada servicio queda en la red interna (el criterio 2 cierra
  el acceso directo). Si se quiere ver Swagger en el navegador, documentar que es un acceso de
  desarrollo interno, no el contrato público.
- Colección Postman de este directorio: verificarla con Newman contra el sistema real y, si se
  copia a la raíz, que sea esta y no la de Reto 2.
- Evidencias (capturas o video; el PDF las exige y el criterio 5 las puntúa):

  | # | Qué capturar | Comando / observación |
  |---|---|---|
  | 1 | Punto de entrada único | `curl` al Gateway 200/201 vs. `curl localhost:8081` / `:8082` con conexión rechazada. |
  | 2 | Salto de latencia CLOSED→OPEN | El `for` de 8 `POST /empleados` del §3.3 con `time`: primeras ~segundos, resto ~ms y fallback. |
  | 3 | Recuperación automática | Tras `docker compose start departamentos-service` y `sleep 35`, un alta con `departamentoId: "NO-EXISTE"` responde **400** (consulta real), sin reiniciar empleados ni el Gateway. |

- Flujo del §3.3, adaptado al modelo canónico de 10 campos (el curl del PDF omite campos que
  nuestra API marca como obligatorios; la colección Postman ya lleva el body completo):

  ```bash
  # 1. Departamento con el sistema sano
  curl -X POST http://localhost:8080/departamentos \
    -H "Content-Type: application/json" \
    -d '{"id":"IT","nombre":"Tecnología"}'

  # 2. Tirar la dependencia
  docker compose stop departamentos-service

  # 3. Ocho altas; mirar el tiempo de cada una
  #    (usar ids/emails/numeroEmpleado únicos; ver colección carpeta 5)

  # 4. Restaurar y esperar el timeout del circuito (30 s + margen)
  docker compose start departamentos-service
  sleep 35

  # 5. Prueba de HALF_OPEN → CLOSED: departamento inexistente → 400, no fallback
  curl -X POST http://localhost:8080/empleados ... "departamentoId": "NO-EXISTE"
  ```

- Actualizar [`STATUS.md`](STATUS.md) en el mismo commit que cierre cada etapa, y esta Etapa 4
  al tener las tres capturas.

## Contrato de respuestas que no debe romperse

Compatibilidad Reto 2 (rutas internas y códigos de negocio), más lo nuevo del borde:

| Situación | Código | Quién responde |
|---|---|---|
| Alta OK, departamentos UP, circuito CLOSED | 201, `estado: ACTIVO` | empleados, vía Gateway |
| Duplicado id/email/`numeroEmpleado` | 400 | empleados, vía Gateway |
| Departamento inexistente, circuito CLOSED | 400 | empleados, vía Gateway (después de un GET real) |
| Dependencia caída, circuito CLOSED (aún reintentando) | 201, `PENDIENTE_VALIDACION` cuando se agotan reintentos / se abre el circuito | empleados (fallback) |
| Circuito OPEN | 201 inmediato, `PENDIENTE_VALIDACION`, sin tocar la red | empleados (fallback) |
| Gateway no puede alcanzar un backend (`GET /departamentos` con el servicio stop) | 503 JSON descriptivo | **Gateway** |
| `GET /health` del borde | 200 `{ "status": "UP", "service": "api-gateway" }` | Gateway |
| Acceso a `:8081` o `:8082` en el host | conexión rechazada | Docker (no hay `ports:`) |

## Criterios de evaluación ↔ etapas

| # | Elemento | Pts | Etapa que lo cierra |
|---|---|---|---|
| 1 | API Gateway funcional (compose, proxy fiel, 503 JSON, `/health` propio) | 1.5 | 1 + 2 |
| 2 | Punto de entrada único (`expose:`, evidencia de rechazo, README y colección con URL nueva) | 1.0 | 2 + 4 |
| 3 | Circuit Breaker (Resilience4j, 3 estados, salto de tiempo, recuperación sin reinicio) | 1.5 | 3 + 4 |
| 4 | Fallback coherente + justificación disponibilidad vs. consistencia + reconciliación | 0.5 | 3 + 4 |
| 5 | README con tabla de rutas, parámetros del CB y capturas de las 3 pruebas | 0.5 | 4 |

Nota del PDF: un Circuit Breaker que está en el código pero cuyo efecto no se puede evidenciar
**no cuenta como implementado**. La Etapa 4 no es cosmética.

## Fuera de alcance de Reto 3 (a propósito)

- Autenticación JWT / autorización → Reto 5 (se valida **en este** Gateway, por eso es de
  aplicación).
- JWKS y propagación de identidad a cabeceras internas → Reto 10.
- Traefik como balanceador delante del Gateway → Reto 11.
- Kong, rate limit, versionado de API → Reto 13.
- Comunicación asíncrona, `PUT`/`DELETE`, transiciones `EN_VACACIONES` / `RETIRADO` → Reto 4.
- Prometheus, métricas del Gateway como primer target → Reto 8. Sí dejar el CB en librería
  con métricas nativas para no tener que reescribirlo.
- Lógica de negocio en el Gateway (validar empleados, componer empleado+perfil).
- Implementar el Circuit Breaker a mano, o ponerlo en `departamentos-service`.
- Publicar otra vez los puertos de los microservicios “para facilitar Swagger”.
