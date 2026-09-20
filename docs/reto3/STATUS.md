# Estado de implementación — Reto 3

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`PLAN-RETO3.md`](PLAN-RETO3.md). Enunciado: [`reto3.pdf`](reto3.pdf).

El Reto 2 está cerrado; su estado histórico vive en [`docs/reto2/STATUS.md`](../reto2/STATUS.md).
No reabrir esas etapas salvo que un cambio de este reto lo exija.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS y colección Postman en `docs/reto3/` (URL base única `http://localhost:8080`) | ✅ | Contrato listo. |
| 1 | Scaffold `api-gateway` (Node.js 22 + Express + `http-proxy-middleware`): rutas `/empleados/*` y `/departamentos/*`, propagación fiel, 503 JSON, `GET /health` propio, Dockerfile | ✅ | Criterio 1 (código). Carpeta `services/api-gateway/`. |
| 2 | `docker-compose.yml`: Gateway con `ports: 8080`; `empleados-service` y `departamentos-service` pasan a `expose:`. Verificar acceso directo rechazado | ✅ | Criterio 2. Único `ports:` = `api-gateway`. Se quitó el `depends_on` empleados→departamentos para poder apagar un backend desde Docker Desktop sin tumbar el arranque del otro. Colección Postman lista para prueba manual (carpetas 0–4). Las 5–6 son Etapa 3: no correrlas aún. |
| 3 | Circuit Breaker Resilience4j en `empleados-service` (llamada a departamentos). Tres estados observables. Fallback `PENDIENTE_VALIDACION` + reconciliación mínima | 🔄 | Criterios 3 y 4. Código y tests unitarios listos (`resilience4j-spring-boot3`, instancia `departamentos`, `GET /empleados/circuit-breaker`, `POST /empleados/reconciliar`). Timeout de llamada subido a 5 s. Falta evidencia real con el compose levantado (salto de latencia CLOSED→OPEN y recuperación sin reinicio) — eso cierra en la Etapa 4, no antes. |
| 4 | README raíz (tabla de rutas, parámetros CB, fallback, URL base), evidencias de las 3 pruebas del PDF, Newman contra la colección de este directorio | ⬜ | Criterio 5. README ya apunta a la URL del Gateway; faltan capturas del Circuit Breaker. |

## Siguiente para el equipo (no reabrir 0–2)

Las etapas **0, 1 y 2 están cerradas**. El tráfico público ya entra solo por `http://localhost:8080`
(`api-gateway`). `empleados-service` y `departamentos-service` usan `expose:`: un `GET` a
`:8081`/`:8082` debe dar `ECONNREFUSED`. Si el backend está caído **pero** la petición va al
Gateway, el 503 es JSON (`mensaje` + `servicio`) — carpeta 4 de Postman.

**Etapa 3 en progreso** (código y tests listos; falta evidencia), solo en
`services/empleados-service/`. No se tocó el Gateway ni Go. Contrato y parámetros:
[`PLAN-RETO3.md`](PLAN-RETO3.md) §Etapa 3. Las carpetas 5 y 6 de
[`Reto3.postman_collection.json`](Reto3.postman_collection.json) ya se pueden correr manualmente
contra el sistema levantado (`docker compose up --build`), ahora que existe el fallback
`PENDIENTE_VALIDACION`; las capturas de esa corrida son insumo de la **Etapa 4**.

**Qué toca ahora:** levantar el sistema completo y capturar las 3 evidencias del §3.3 del PDF
(punto de entrada único, salto de latencia CLOSED→OPEN, recuperación automática sin reinicio) y
avanzar el README raíz — eso es la Etapa 4.

Prueba de las etapas 1–2 (sistema ya levantado con `docker compose up --build`):

1. Importar la colección; carpetas 0–3 con todos los contenedores UP.
2. Carpeta 1, acceso directo: `Could not send request` / `ECONNREFUSED` es correcto.
3. Carpeta 4: Stop de un backend en Docker Desktop, petición al Gateway → 503 JSON; `/health` sigue UP.

## Decisiones técnicas del enunciado

Registrar aquí la versión corta. El "por qué" largo está en `PLAN-RETO3.md` y, cuando se
implemente, debe copiarse al README raíz (entregable del PDF). Mientras la etapa no cierre,
queda como "propuesta".

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Tipo de Gateway | Tomada (la impone el PDF §1.2) | Gateway de aplicación, no Traefik/Nginx |
| Lenguaje / stack del Gateway | Tomada en Etapa 1 | **Node.js 22 + Express + `http-proxy-middleware`**. Queda prohibido Spring Cloud Gateway (repetiría Java/Spring Boot) y el reverse proxy en Go (ya es departamentos). |
| Puerto publicado | Tomada en Etapa 2 | Solo `8080` (`api-gateway`). Internos: empleados `8080`, departamentos `8081` (`expose:`). |
| Librería del Circuit Breaker | Tomada en Etapa 3 | Resilience4j (`resilience4j-spring-boot3` 2.4.0) en `empleados-service` (quien llama), instancia `departamentos`, invocación programática (`CircuitBreakerRegistry`), no anotaciones |
| Parámetros del circuito | Tomada en Etapa 3 | 3 fallos lógicos (`slidingWindowSize`/`minimumNumberOfCalls`), 100% failureRate, 30 s en OPEN, 1 llamada en HALF_OPEN, timeout HTTP 5 s. Todos sobreescribibles por env (`CB_*`) |
| Fallback | Tomada en Etapa 3 | Persistir con `PENDIENTE_VALIDACION` (disponibilidad) y responder 201. Reconciliación: `POST /empleados/reconciliar` reconsulta departamentos por cada pendiente; nunca departamento por defecto |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `PLAN-RETO3.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada por algo externo (duda de negocio, evidencia que no se pudo
   capturar), anótalo igual en "Notas" en vez de dejarla en 🔄 silenciosamente.
4. El Circuit Breaker no se marca ✅ solo porque compile: hace falta evidencia del salto de
   latencia y de la recuperación sin reinicio (nota final del PDF).
