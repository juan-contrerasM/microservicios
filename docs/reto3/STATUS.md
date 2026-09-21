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
| 2 | `docker-compose.yml`: Gateway con `ports: 8080`; `empleados-service` y `departamentos-service` pasan a `expose:`. Verificar acceso directo rechazado | ✅ | Criterio 2. Único `ports:` = `api-gateway`. El Gateway espera que los backends se inicien, no que permanezcan saludables, por lo que puede arrancar y seguir disponible ante la caída de uno de ellos. |
| 3 | Circuit Breaker Resilience4j en `empleados-service` (llamada a departamentos). Tres estados observables. Fallback `PENDIENTE_VALIDACION` + reconciliación mínima | ✅ | Criterios 3 y 4. Resilience4j para Spring Boot 4, instancia `departamentos`, timeout HTTP de 5 s, tres fallos para abrir, transición automática tras 30 s y una llamada permitida en `HALF_OPEN`. El comportamiento `CLOSED → OPEN → HALF_OPEN → CLOSED` fue comprobado sin reiniciar empleados. |
| 4 | README raíz (tabla de rutas, parámetros CB, fallback, URL base), evidencias de las 3 pruebas del PDF, Newman contra la colección de este directorio | ✅ | Criterio 5. Documentación completada y evidencia reproducible en [`EVIDENCIAS.md`](EVIDENCIAS.md). Newman: flujo sano 16/16 peticiones y 34/34 aserciones; apertura 10/10 y 28/28; recuperación 5/5 y 12/12. |

## Estado de entrega

Las etapas **0 a 4 están cerradas**. El tráfico público entra únicamente por
`http://localhost:8080` (`api-gateway`); los puertos de empleados y departamentos no se publican
en el host. Ante la caída de un backend, el Gateway permanece disponible y devuelve un `503`
JSON controlado. En empleados, la indisponibilidad de departamentos activa el fallback, conserva
el alta como `PENDIENTE_VALIDACION` y permite reconciliarla cuando el proveedor se recupera.

La secuencia completa y sus resultados reales se encuentran en
[`EVIDENCIAS.md`](EVIDENCIAS.md). Para repetirla, importa
[`Reto3.postman_collection.json`](Reto3.postman_collection.json) y ejecuta las carpetas en el
orden documentado. La carpeta 1 comprueba manualmente que `:8081` y `:8082` no son accesibles;
la 4 comprueba el `503` controlado; las carpetas 5 y 6 demuestran apertura, recuperación y
reconciliación.

## Decisiones técnicas del enunciado

Registrar aquí la versión corta. El "por qué" largo está en `PLAN-RETO3.md` y, cuando se
implemente, debe copiarse al README raíz (entregable del PDF). Mientras la etapa no cierre,
queda como "propuesta".

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Tipo de Gateway | Tomada (la impone el PDF §1.2) | Gateway de aplicación, no Traefik/Nginx |
| Lenguaje / stack del Gateway | Tomada en Etapa 1 | **Node.js 22 + Express + `http-proxy-middleware`**. Queda prohibido Spring Cloud Gateway (repetiría Java/Spring Boot) y el reverse proxy en Go (ya es departamentos). |
| Puerto publicado | Tomada en Etapa 2 | Solo `8080` (`api-gateway`). Internos: empleados `8080`, departamentos `8081` (`expose:`). |
| Librería del Circuit Breaker | Tomada en Etapa 3 | Resilience4j (`resilience4j-spring-boot4` 2.4.0, compatible con Spring Boot 4.1) en `empleados-service` (quien llama), instancia `departamentos`, invocación programática (`CircuitBreakerRegistry`), no anotaciones |
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
