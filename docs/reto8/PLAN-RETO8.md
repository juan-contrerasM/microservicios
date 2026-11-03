# Plan de implementación — Reto 8: Observabilidad y monitoreo

Basado en [`reto8.pdf`](reto8.pdf), que es el enunciado normativo, y en
[`observabilidad.pdf`](observabilidad.pdf), que se usa como material conceptual de apoyo. Este
documento organiza el trabajo para **dos personas**. El avance
real se registra en [`STATUS.md`](STATUS.md).

El Reto 7 está cerrado. Este reto conserva los contratos de negocio, la seguridad, la suite BDD y los
pipelines existentes: agrega observabilidad transversal a los siete procesos HTTP del ecosistema y a
los flujos asíncronos de RabbitMQ. Si la instrumentación descubre un defecto funcional, se corrige en
un cambio separado y con su prueba.

## Qué hay que resolver

| Pilar | Estado actual | Resultado exigido |
|---|---|---|
| Salud | Los siete servicios tienen algún `GET /health`, pero las respuestas y dependencias comprobadas no son uniformes | `200 UP` o `503 DOWN`, nombre del servicio y checks reales de base/broker cuando apliquen |
| Métricas | No existen endpoints Prometheus | Los siete servicios exponen CPU/memoria, tráfico, códigos HTTP y latencia; Prometheus los raspa por nombre Docker |
| Trazas | No hay trazabilidad distribuida | OpenTelemetry, W3C Trace Context y una traza completa del onboarding en Zipkin |
| Mensajería | Los eventos no transportan contexto de traza | Contexto inyectado en headers AMQP y extraído por consumidores; el scheduler crea su span raíz |
| Logs | Predomina texto plano y logs locales | JSON con `timestamp`, `level`, `service`, `traceId` y mensaje; Promtail los envía a Loki |
| Visualización | No existe dashboard | Grafana aprovisionado desde Git con Prometheus y Loki y cuatro paneles mínimos |
| Alertas | No existen | Dos reglas aprovisionadas y notificación real a Discord |
| Evidencia | Jenkins conserva evidencia del Reto 7 | Caos controlado, capturas, consulta LogQL, traza completa y análisis documentado |

Los siete objetivos son: `api-gateway`, `empleados-service`, `departamentos-service`,
`perfiles-service`, `notificaciones-service`, `vacaciones-service` y `auth-service`.

## Decisiones técnicas iniciales

Estas decisiones evitan que cada persona resuelva el mismo problema de forma incompatible. Si una
validación real obliga a cambiarlas, se registra primero en `STATUS.md`.

| Decisión | Elección | Motivo |
|---|---|---|
| Backend de trazas | **Zipkin** | El enunciado lo recomienda para el entorno académico, tiene una UI simple y recibe spans en `/api/v2/spans`. |
| Propagación | **W3C `tracecontext,baggage`** | Es el contrato entre Java, Go, Node, Python y los headers AMQP. No usar propagadores distintos por lenguaje. |
| Sampling | 100 % en el entorno local del reto | Permite demostrar todos los flujos; no es una recomendación para producción. |
| Puerto de Prometheus | Host `${PROMETHEUS_PORT:-9091}` → contenedor `9090` | Jenkins ya publica `9090`; copiar literalmente el ejemplo del PDF produciría una colisión. |
| Puertos restantes | Grafana `${GRAFANA_PORT:-3000}`, Loki interno `3100`, Zipkin `${ZIPKIN_PORT:-9411}` | Loki no necesita exposición al host; Grafana es la entrada para consultar logs. |
| Imágenes | Versiones fijas, definidas al implementar la Fase 1 | No usar `latest`: el mismo commit debe levantar el mismo stack. |
| Métricas | Rutas nativas: Java `/actuator/prometheus`; Node/Python/Go `/metrics` | Prometheus declarará la ruta real por job. No se fuerza una ruta artificial común. |
| Nombres de métricas HTTP | Normalizar en el dashboard con consultas por lenguaje/job | Las librerías producen nombres y labels distintos. No se falsifican métricas para igualarlos. |
| Logs | JSON a `stdout`; Promtail descubre contenedores por el socket Docker | Se conserva el modelo de contenedores y no se agregan archivos de log compartidos. |
| Correlación | `traceId` y, cuando exista, `spanId` en cada log de aplicación | Permite saltar de Loki a Zipkin. Nunca registrar JWT, claves ni cuerpos sensibles. |
| Grafana | Datasources, dashboard, reglas, contact point y policy aprovisionados desde `observability/grafana/provisioning/` | Debe quedar disponible después de `docker compose up`, sin configuración manual. |
| Canal de alerta | Discord, URL en `GRAFANA_ALERT_WEBHOOK_URL` local | Cumple el canal externo. `.env.example` documenta la variable sin contener el webhook real. |
| Alertas mínimas | Servicio caído durante 1 min y tasa de 5xx superior al 10 % durante 2 min | Cubren disponibilidad y comportamiento; si la heterogeneidad de métricas bloquea la segunda, se normaliza con recording rules. |
| Datos persistentes | Volúmenes propios para Prometheus, Grafana, Loki y Zipkin si la imagen elegida lo soporta | No reutilizar bases o volúmenes de negocio ni los de Jenkins/Sonar. |
| Jenkins | No mover ni cambiar su puerto | El Reto 7 permanece reproducible. La observabilidad se integra al pipeline solo después de cerrar el flujo local obligatorio. |

## Distribución para dos personas

La división se hace por archivos y lenguajes, no simplemente por criterios del PDF. Así ambos pueden
avanzar en paralelo y los conflictos se concentran en puntos de integración previstos.

### Persona 1 — Infraestructura, Java y Go

Propietaria principal de:

- `observability/**` (estructura, Prometheus, Loki, Promtail y aprovisionamiento de Grafana).
- `services/empleados-service/**`.
- `services/perfiles-service/**`.
- `services/departamentos-service/**`.
- Integración controlada de `docker-compose.yml` y `.env.example`.
- Dashboard, reglas de alerta y datasource de Loki/Prometheus.

Entregables propios:

1. Stack de observabilidad levantando sin colisionar con Jenkins/Sonar.
2. Métricas, salud, trazas y JSON logs en los dos servicios Java y el servicio Go.
3. Jobs Prometheus para los siete servicios, inicialmente aunque los de la Persona 2 todavía estén
   `DOWN`.
4. Dashboard y alertas aprovisionados, usando las métricas reales acordadas.

### Persona 2 — Node, Python y trazabilidad asíncrona

Propietaria principal de:

- `services/api-gateway/**`.
- `services/vacaciones-service/**`.
- `services/auth-service/**`.
- `services/notificaciones-service/**`.
- Pruebas de propagación HTTP/AMQP y generador de tráfico/caos.
- `docs/reto8/EVIDENCIAS.md` y capturas, una vez que ambas ramas estén integradas.

Entregables propios:

1. Métricas, salud, trazas y JSON logs en los dos servicios Node y los dos Python.
2. Instrumentación HTTP del Gateway antes de registrar proxies, para que sea la raíz de la traza.
3. Propagación manual de W3C en headers RabbitMQ y span raíz del scheduler de vacaciones.
4. Pruebas reproducibles del onboarding y del flujo de vacaciones asíncrono.

### Archivos compartidos y regla de integración

| Archivo | Quién edita | Regla |
|---|---|---|
| `docker-compose.yml` | Persona 1 | Persona 2 entrega la lista exacta de variables por servicio; no edita el Compose en paralelo. |
| `.env.example` | Persona 1 | Nunca se agregan webhooks, tokens o contraseñas reales. |
| `README.md` | Una persona por turno | Persona 1 escribe arquitectura/infraestructura; integra antes de que Persona 2 agregue pruebas y hallazgos. |
| `docs/reto8/STATUS.md` | Quien cierre una fase | Se actualiza en el mismo commit que la evidencia verificable. |
| Catálogo de headers AMQP | Persona 2 propone; ambas validan | Se fija antes de modificar publicadores Java y consumidores de otros lenguajes. |

Trabajo recomendado: ramas `reto8/infra-java-go` y `reto8/node-python-tracing`. La primera
integración debe ser la Fase 1; después se rebasan ambas ramas sobre ese contrato. No mezclar en un
mismo commit instrumentación, dashboard y capturas de caos.

## Contratos compartidos antes de programar

### Variables de entorno

```text
OTEL_SERVICE_NAME=<nombre-compose>
OTEL_EXPORTER_ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans
OTEL_PROPAGATORS=tracecontext,baggage
OTEL_TRACES_SAMPLER=always_on
LOG_FORMAT=json
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<local>
GRAFANA_ALERT_WEBHOOK_URL=<local-no-versionar>
```

El nombre exacto de cada variable puede adaptarse al SDK si este no reconoce la convención, pero el
Compose debe seguir siendo la fuente central de sus valores.

### Headers RabbitMQ

- Inyectar las claves W3C producidas por el propagador, como mínimo `traceparent` y, si existe,
  `tracestate`/`baggage`.
- Mantener el cuerpo de los eventos intacto: el contexto viaja en propiedades/headers AMQP, no en
  `data`.
- Cada consumidor extrae el contexto antes de crear el span de procesamiento.
- Cada publicación crea un span `PRODUCER`; cada consumo, uno `CONSUMER`.
- La idempotencia por `eventId` existente no cambia.

### Salud

La forma objetivo es:

```json
{
  "status": "UP",
  "service": "nombre-service",
  "checks": {
    "database": "UP",
    "messageBroker": "UP"
  }
}
```

Solo se incluyen dependencias que realmente tenga el proceso. El Gateway puede reportar su propia
disponibilidad sin convertir `/health` en una comprobación profunda de todos los backends; el estado
global lo calcula Prometheus con los siete targets. Un check `DOWN` produce HTTP 503.

## Fases de implementación

### Fase 0 — Enunciados, plan y estado

**Responsable:** trabajo conjunto, lo prepara una sola persona.  
**Estado inicial esperado:** cerrado al aprobar este plan.

- Guardar los dos PDF en `docs/reto8/`.
- Crear este plan y `STATUS.md`.
- Confirmar los siete servicios, puertos reales y conflicto Prometheus/Jenkins.
- No tocar todavía servicios, Compose o README de entrega.

**Cierre:** los documentos existen, la distribución está acordada y ninguna fase técnica figura
como hecha.

### Fase 1 — Contrato y stack base de observabilidad — criterio 1 (parte)

**Responsable:** Persona 1.  
**Revisión:** Persona 2 valida las variables que recibirán sus cuatro servicios.

- Crear `observability/` con configuraciones de Prometheus, Loki, Promtail y Grafana.
- Agregar Prometheus, Grafana, Loki, Promtail y Zipkin al Compose con versiones fijas,
  healthchecks, red existente y volúmenes propios.
- Aprovisionar datasources Prometheus y Loki.
- Crear `prometheus.yml` con el Gateway primero y los siete jobs por nombre Docker.
- Documentar variables en `.env.example` sin secretos.
- Incorporar un diagrama Mermaid previo en el README: pull de métricas, push de trazas/logs y
  consultas de Grafana.

**Pruebas:** `docker compose config`; arranque aislado del stack; APIs de salud de Prometheus,
Grafana, Loki y Zipkin; Grafana lista ambos datasources.

**Cierre:** la infraestructura arranca y Prometheus muestra siete targets, aunque los endpoints
`/metrics` todavía puedan estar `DOWN`.

### Fase 2A — Instrumentación Java y Go — criterio 2 (parte) y 3 (parte)

**Responsable:** Persona 1.  
**Puede correr en paralelo con:** Fase 2B.

- Empleados y perfiles: Actuator/Micrometer Prometheus, OpenTelemetry y Logback JSON.
- Departamentos: `prometheus/client_golang`, OpenTelemetry HTTP/SQL donde aplique y logger JSON.
- Preservar endpoints de negocio y probar `GET /health` 200/503.
- Instrumentar la llamada empleados → departamentos y el publish/consume AMQP de los servicios
  Java usando el contrato acordado.
- Añadir pruebas unitarias o de integración que demuestren exposición de métricas y propagación sin
  exigir que Zipkin esté disponible para arrancar.

**Cierre:** los tres targets están `UP`, sus logs son JSON y una llamada empleados → departamentos
conserva el mismo trace ID.

### Fase 2B — Instrumentación Node y Python — criterio 2 (parte) y 3 (parte)

**Responsable:** Persona 2.  
**Puede correr en paralelo con:** Fase 2A.

- Gateway y vacaciones: `prom-client`, OpenTelemetry Node y logger JSON.
- Auth y notificaciones: instrumentador Prometheus/FastAPI, OpenTelemetry Python y logger JSON.
- Inicializar OTel antes de cargar Express/FastAPI y de crear clientes HTTP/AMQP.
- Mantener `/health` y `/metrics` públicos donde el middleware JWT pueda interceptarlos.
- Agregar pruebas de métricas, salud y ausencia de secretos en logs.

**Cierre:** los cuatro targets están `UP`, sus logs son JSON y el Gateway crea/propaga la traza
raíz hacia los backends HTTP.

### Fase 3 — Trazabilidad asíncrona completa — criterio 2 (cierre)

**Responsable principal:** Persona 2.  
**Apoyo:** Persona 1 ajusta publicadores/consumidores Java y valida Go.

- Instrumentar todos los publicadores y consumidores RabbitMQ con headers W3C.
- Crear un span raíz manual en cada ejecución relevante del scheduler de vacaciones.
- Verificar dos trazas:
  1. Onboarding: Gateway → empleados → departamentos → RabbitMQ → auth/perfiles/
     notificaciones.
  2. Vacaciones: scheduler → RabbitMQ → auth/notificaciones.
- Asegurar que la indisponibilidad de Zipkin no rompe el negocio ni deja mensajes sin ack.

**Cierre:** cada flujo aparece como una sola traza, no como spans o trazas independientes, y los
errores quedan marcados en el span correspondiente.

### Fase 4 — Logs centralizados y correlación — criterio 3 (cierre)

**Responsable:** ambas personas sobre sus servicios; integra Persona 1.

- Confirmar JSON válido en stdout de los siete servicios.
- Configurar Promtail con descubrimiento Docker, labels `service`/`container` y parseo JSON.
- Verificar Loki desde Grafana con al menos una consulta LogQL por `service` y otra por `traceId`.
- Definir retención razonable para el entorno académico.
- Revisar que JWT, contraseñas, webhook y datos sensibles no aparecen en logs.

**Cierre:** una operación se localiza en Loki por `traceId` y ese identificador abre la traza
correspondiente en Zipkin; la consulta queda anotada para evidencias.

### Fase 5 — Dashboard y alertas aprovisionadas — criterios 4 y 5

**Responsable:** Persona 1.  
**Apoyo:** Persona 2 genera tráfico y valida nombres de métrica.

- Versionar dashboard JSON con dos filas como mínimo:
  - Resumen: estado de los siete targets.
  - Tráfico: tasa, latencia y errores 4xx/5xx por servicio.
- Adaptar PromQL a las métricas reales de cada stack; usar recording rules si hace falta una vista
  común.
- Aprovisionar las dos alertas y sus annotations/severidad.
- Aprovisionar contact point/policy de Discord usando solo la variable local.
- Verificar que recrear Grafana desde cero vuelve a cargar datasources, dashboard y alertas.

**Cierre:** cuatro paneles mínimos con datos reales y dos reglas visibles sin configuración manual.

### Fase 6 — Caos controlado y evidencia — criterio 6

**Responsable principal:** Persona 2.  
**Ejecución y revisión:** ambas personas.

- Crear un procedimiento/script reversible para generar tráfico.
- Detener `departamentos-service`, sin borrarlo, y comprobar target `DOWN`, panel rojo y alerta de
  Discord después del tiempo configurado.
- Restaurarlo y comprobar resolución de la alerta.
- Inducir latencia de manera controlada mediante una variable de entorno o endpoint solo de
  desarrollo; no dejar código aleatorio ni sleeps permanentes.
- Capturar dashboard, traza del onboarding, consulta LogQL y alerta recibida.
- Responder con datos: qué servicio tardó más y qué span/panel lo demuestra.

**Cierre:** `docs/reto8/EVIDENCIAS.md` permite repetir el procedimiento y referencia las capturas;
todo cambio de caos queda desactivado o revertido.

### Fase 7 — Documentación, regresión y cierre

**Responsable:** ambas personas; integra quien no haya editado el README en la Fase 6.

- Completar README con tres pilares, Pull vs. Push, CNCF/OpenTelemetry, W3C Trace Context,
  librerías por servicio, elección de Zipkin y canal de alertas.
- Documentar URLs reales y comandos de arranque/verificación.
- Ejecutar pruebas unitarias y BDD existentes para demostrar que la instrumentación no rompió los
  Retos 1–7.
- Levantar desde volúmenes limpios sin borrar accidentalmente los volúmenes persistentes de
  Jenkins/Sonar del equipo.
- Revisar configuraciones y capturas contra los seis criterios de evaluación.

**Cierre:** todas las fases figuran ✅ en `STATUS.md`, las evidencias son reproducibles y el sistema
vuelve a estado saludable después del caos.

## Dependencias entre fases

```text
Fase 0
  └─ Fase 1
      ├─ Fase 2A (Persona 1) ─┐
      └─ Fase 2B (Persona 2) ─┼─ Fase 3 ─ Fase 4 ─ Fase 5 ─ Fase 6 ─ Fase 7
                              ┘
```

Las fases 2A y 2B son el bloque de paralelización principal. La Fase 3 exige integrar ambas porque
una misma traza cruza lenguajes y RabbitMQ. La Fase 5 puede prepararse con datos parciales, pero no se
cierra hasta tener los siete targets.

## Criterios de evaluación ↔ fases y responsables

| Criterio | Puntos | Fases | Responsable principal |
|---|---:|---|---|
| Infraestructura de observabilidad | 1.0 | 1 | Persona 1 |
| Instrumentación de los siete servicios | 1.0 | 2A, 2B, 3 | Ambas |
| Logs centralizados | 1.0 | 2A, 2B, 4 | Ambas; integra Persona 1 |
| Dashboard Grafana | 0.5 | 5 | Persona 1 |
| Alertas proactivas | 0.5 | 5, 6 | Persona 1 configura; Persona 2 evidencia |
| Caos y documentación | 1.0 | 6, 7 | Persona 2 coordina; ambas validan |

## Riesgos que deben vigilarse

1. **Colisión de puertos:** Jenkins ya ocupa `9090`; Prometheus debe usar `9091` en el host.
2. **Arranque acoplado:** ningún servicio de negocio debe dejar de arrancar porque Zipkin,
   Prometheus o Loki estén caídos.
3. **Inicialización tardía de OTel:** en Node/Python, cargar el SDK después de Express, FastAPI o
   clientes HTTP produce trazas incompletas.
4. **RabbitMQ sin contexto:** instrumentar solo HTTP no satisface el onboarding completo ni el caso
   del scheduler.
5. **Cardinalidad:** no usar `employeeId`, URL completa, email o trace ID como labels Prometheus.
   Esos datos pertenecen a spans/logs.
6. **PromQL heterogéneo:** Java, Go, Node y Python no nombran igual las métricas HTTP; el dashboard
   debe basarse en series verificadas, no en los nombres ilustrativos del PDF.
7. **Promtail en Docker Desktop:** validar acceso de solo lectura al socket y nombres reales de
   contenedor; no asumir rutas Linux del host fuera del contenedor.
8. **Secretos:** el webhook de Discord y credenciales de Grafana viven en `.env`, nunca en JSON de
   provisioning, capturas, logs o Git.
9. **Logs de terceros:** librerías como Pika, SQLAlchemy o Spring pueden producir texto/ruido; la
   configuración debe preservar JSON y niveles razonables.
10. **Evidencia falsa:** una UI abierta no cierra una fase. Debe haber targets, series, spans, logs y
    notificación con datos reales.

## Fuera de alcance

- Cambiar contratos de negocio, roles JWT, eventos o esquemas de base de datos.
- Sustituir Jenkins/SonarQube o rehacer el Reto 7.
- Kubernetes, service mesh, Grafana Cloud, ELK, Sentry o un OTel Collector obligatorio.
- Alta disponibilidad o almacenamiento de producción para el stack de observabilidad.
- Publicar secretos o abrir al host puertos internos innecesarios.
- Crear métricas con identificadores personales o de alta cardinalidad.
