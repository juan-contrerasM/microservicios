# Plan de trabajo — Proyecto Final de Microservicios

Basado en [`proyectoFinal.pdf`](proyectoFinal.pdf), en los contratos normativos de los Retos 1–8 y en el estado real del
repositorio. El proyecto final **evoluciona el sistema existente**: no se vuelve a implementar desde
cero. Todo componente heredado debe revalidarse contra este plan antes de contarlo como terminado.

El avance real se registra en [`STATUS.md`](STATUS.md). Las evidencias finales se consolidarán en
`EVIDENCIAS.md`. El trabajo está distribuido para **dos personas**, con fases que pueden ejecutarse
en paralelo solo cuando no comparten contratos o archivos centrales.

## Objetivo de la entrega

Entregar un sistema de gestión del ciclo de vida del empleado que pueda levantarse desde cero con
`docker compose up --build`, tenga siete servicios de aplicación, comunicación HTTP y por eventos,
seguridad, pruebas con cobertura mínima del 70 %, CI/CD, observabilidad completa, secretos fuera del
repositorio, tolerancia a fallos y documentación reproducible.

## Regla de trabajo y protección del avance existente

1. Crear la etiqueta `reto8-completo` sobre el último estado estable.
2. Crear y publicar la rama `proyecto-final`; `main` queda intacta hasta aprobar la entrega.
3. Trabajar por ramas cortas `proyecto-final/fase-N-descripcion`.
4. Integrar únicamente fases que cumplan su definición de terminado.
5. Mantener commits pequeños por requisito; no mezclar secretos, funcionalidad, CI y evidencias.
6. No reescribir el historial publicado salvo aprobación explícita del equipo.

## Inventario reutilizable y brechas de partida

| Área | Base reutilizable | Brecha que debe cerrarse |
|---|---|---|
| Empleados | CRUD, estados, retiro lógico, auditoría, eventos, unicidad | Revalidar contrato, seguridad, OpenAPI, cobertura y métricas de negocio |
| Departamentos | Go, MySQL, alta/listado/consulta, health y resiliencia desde Empleados | Completar actualización y eliminación, pruebas y contrato OpenAPI |
| Auth | JWT HS256, BCrypt, activación, recuperación, cambio de clave y eventos | Endurecer secretos, pruebas de integración y auditoría de seguridad |
| Perfiles | Alta/actualización por eventos, propiedad del recurso y archivado | Agregar todos los campos personales, postales, profesionales, foto y redes |
| Vacaciones | Programación, consultas, cancelación, scheduler y eventos | Aclarar/completar CRUD y validar ejecución segura con múltiples réplicas |
| Notificaciones | RabbitMQ, Mailhog, deduplicación y correo | Plantillas configurables y cobertura completa del catálogo de eventos |
| Gateway | Borde único, proxy, JWT y RBAC | Revalidar todas las rutas; agregados opcionales separados del health propio |
| Observabilidad | Prometheus, Grafana, Loki, Promtail, Zipkin, OTel, dashboard, alertas y evidencias del Reto 8 | Revalidar métricas de negocio, canal externo y reconstrucción limpia para la entrega final |
| CI/CD | Jenkins, SonarQube y pipelines de Empleados/Vacaciones | Pipelines para los cinco servicios restantes, seguridad, Registry y despliegue |
| Escalabilidad | Circuit breaker, timeouts y reintentos | Load balancer, réplicas, stateless, health-aware routing y prueba de caída |
| Secretos | `.env.example` y variables de entorno | Marcadores, Compose Secrets, generación/rotación y auditoría de Git |
| Documentación | README, Swagger y evidencias por reto | Documentación final consolidada, guías, eventos, arquitectura y paquete |

## Arquitectura objetivo y decisiones compartidas

| Decisión | Elección | Criterio |
|---|---|---|
| Entrada pública | API Gateway | Los servicios de negocio mantienen puertos internos; no se rompe el borde único |
| Comunicación síncrona | Gateway → servicios y Empleados → Departamentos | La segunda es la interacción explícita de resiliencia exigida por el enunciado |
| Comunicación asíncrona | RabbitMQ topic exchange | El resto del ciclo de vida se integra mediante el catálogo de eventos |
| Identidad | Auth propio con JWT HS256 | Cumple la opción obligatoria; OIDC/JWKS queda como bonificación posterior |
| Bases | Una base por servicio con persistencia propia | Ningún servicio accede directamente a la base de otro |
| Observabilidad | Loki/Promtail + Prometheus/Grafana + OTel/Zipkin | Reutiliza el Reto 8 y cubre logs, métricas, alertas y trazas |
| CI/CD | Jenkins + SonarQube + Registry Docker local | Pipeline completo build/test/quality/package/push/deploy |
| Secretos | Docker Compose Secrets generados localmente | No guardar credenciales reales en Git, Compose, imágenes, logs o documentación |
| Escalado | Load balancer con al menos dos réplicas demostrables | Una instancia puede caer sin interrumpir el servicio publicado |
| Health | `/health` individual, HTTP 200/503 | Cada servicio reporta proceso y dependencias reales; no requiere puertos públicos |
| Diversidad tecnológica | Java, Go, Node.js y Python | Se conservan al menos cuatro lenguajes reales en los microservicios |
| Persistencia | Base independiente por microservicio con datos persistentes | Ningún servicio consulta tablas o credenciales de la base de otro |

## Contratos que no pueden divergir

### Envelope de eventos

Todos los eventos deben conservar:

```json
{
  "id": "uuid",
  "type": "empleado.creado",
  "version": 1,
  "occurredAt": "2026-11-09T12:00:00Z",
  "producer": "empleados-service",
  "data": {}
}
```

- `id` es obligatorio y se usa para deduplicación persistente.
- `type`, `version`, `occurredAt`, `producer` y `data` son obligatorios.
- El contexto W3C (`traceparent`, `tracestate`, `baggage`) viaja en headers AMQP.
- Nunca viajan contraseñas, hashes, secretos, JWT de acceso o credenciales.
- Los tokens de activación/recuperación solo aparecen en los eventos normativos y nunca en logs.

### Catálogo mínimo obligatorio

| Evento | Productor | Consumidores mínimos |
|---|---|---|
| `empleado.creado` | Empleados | Auth, Perfiles, Notificaciones/Vacaciones según contrato vigente |
| `empleado.actualizado` | Empleados | Perfiles |
| `empleado.retirado` | Empleados | Auth, Perfiles, Notificaciones/Vacaciones según contrato vigente |
| `usuario.creado` | Auth | Notificaciones |
| `usuario.recuperacion` | Auth | Notificaciones |
| `cuenta.activada` | Auth | Notificaciones |
| `cuenta.desactivada` | Auth | Notificaciones |
| `vacaciones.programadas` | Vacaciones | Notificaciones |
| `vacaciones.iniciadas` | Vacaciones | Auth y Notificaciones |
| `vacaciones.finalizadas` | Vacaciones | Auth y Notificaciones |

Antes de modificar productores o consumidores se confrontará esta tabla con el catálogo normativo
del Reto 4 y con el PDF final. En caso de discrepancia prevalece el catálogo normativo.

### Health check

```json
{
  "status": "UP",
  "service": "empleados-service",
  "checks": {
    "database": "UP",
    "messageBroker": "UP"
  }
}
```

- HTTP 200 cuando todos los checks obligatorios están `UP`.
- HTTP 503 cuando alguno está `DOWN`.
- Solo se reportan dependencias reales del proceso.
- El Gateway conserva un health propio; un agregado global es opcional y usa otra ruta.
- Docker y el balanceador consumen estos endpoints dentro de sus redes.

### Calidad mínima por servicio

Cada uno de los siete servicios debe tener:

- OpenAPI completo, Swagger UI, modelos y respuestas de error.
- Pruebas unitarias y de integración del camino feliz y de fallos relevantes.
- Cobertura medida y bloqueada en 70 % o más.
- `/health` y `/metrics` coherentes.
- Logs JSON sin secretos y trazas W3C.
- Dockerfile reproducible, preferiblemente multi-stage y sin ejecución como root cuando sea viable.
- Jenkinsfile con build, test, quality, package, push y política de fallo.

## Distribución para dos personas

### Persona 1 — Dominio Java/Go, calidad y CI

Propiedad principal:

- `empleados-service`, `perfiles-service`, `departamentos-service`.
- CRUD de Departamentos y ampliación de Perfiles.
- Pruebas/cobertura Java y Go.
- Jenkinsfiles, SonarQube y calidad de esos servicios.
- Docker Registry, empaquetado y plantillas de pipeline compartidas.

### Persona 2 — Dominio Node/Python, seguridad y observabilidad

Propiedad principal:

- `api-gateway`, `vacaciones-service`, `auth-service`, `notificaciones-service`.
- Plantillas de correo, seguridad JWT/RBAC y scheduler escalable.
- Pruebas/cobertura Node y Python.
- Dashboards, alertas, Discord, trazas y evidencia de caos.

### Integración compartida

| Archivo/área | Responsable de integración | Regla |
|---|---|---|
| `docker-compose.yml` | Una persona por fase | No editar en paralelo; cambios revisados por la otra persona |
| `.env.example`, `secrets.example/`, scripts | Persona 2 | Ningún valor utilizable ni secreto real |
| Catálogo de eventos | Ambas | Contrato primero, implementación después |
| Gateway y load balancer | Persona 2 | Persona 1 valida health y escalado de sus servicios |
| Jenkins/Registry | Persona 1 | Persona 2 aporta comandos exactos de Node/Python |
| README y docs finales | Ambas por turnos | Una sola persona integra cada documento |
| Evidencias | Quien ejecuta; revisa la otra | Toda captura debe tener procedimiento reproducible |

## Fases de ejecución

### Fase 0 — Punto de restauración, rama y documentación normativa

**Responsable:** conjunta.  
**Objetivo:** iniciar sin arriesgar los Retos 1–8.

- Etiquetar `reto8-completo`.
- Crear y publicar `proyecto-final`.
- Guardar el PDF final en `docs/proyecto-final/`.
- Versionar este plan, `STATUS.md` y un `EVIDENCIAS.md` vacío.
- Registrar commit, rama, versiones de Docker/Compose y estado de los contenedores.
- Confirmar que no hay cambios locales ajenos antes de integrar.

**Pruebas:** etiqueta y rama visibles en remoto; `git status` limpio.  
**Cierre:** existe un punto de retorno y ninguna implementación ha cambiado todavía.

### Fase 1 — Matriz normativa y baseline ejecutable

**Responsable:** ambas; integra Persona 1.  
**Objetivo:** convertir el PDF en verificaciones concretas y medir la base heredada.

- Crear una matriz requisito → archivo → prueba → evidencia.
- Inventariar los siete servicios, bases, puertos internos, rutas, eventos y lenguajes.
- Confirmar y documentar los cuatro lenguajes mínimos: Java, Go, Node.js y Python.
- Confirmar que cada microservicio con persistencia posee su propia base y no comparte esquema.
- Ejecutar todas las suites existentes y registrar cobertura real, no estimada.
- Ejecutar `docker compose config` con configuración local generada.
- Levantar desde volúmenes limpios y validar flujos BDD actuales.
- Clasificar cada requisito como reutilizable, parcial, faltante u opcional.
- Congelar contratos HTTP/eventos antes de cambios funcionales.

**Pruebas:** unitarias, integración, BDD, health de siete servicios y smoke del Gateway.  
**Cierre:** baseline reproducible con lista exacta de fallos; no quedan requisitos ambiguos.

### Fase 2 — Cero secretos y configuración segura

**Responsable:** Persona 2; revisión Persona 1.  
**Objetivo:** cumplir el criterio de seguridad antes de generar nuevos artefactos.

- Sustituir valores utilizables de `.env.example` por marcadores.
- Eliminar credenciales por defecto del código y configuraciones.
- Crear `secrets.example/` sin secretos reales.
- Crear `scripts/setup-secrets.sh` y `scripts/setup-secrets.ps1` con generación criptográfica.
- Adoptar Docker Compose Secrets (o justificar Vault) para JWT, bases, broker, Jenkins, Sonar,
  Grafana, Registry y webhook.
- Adaptar servicios para leer secretos por archivo (`*_FILE`) sin imprimirlos.
- Excluir `.env`, `secrets/`, certificados privados y artefactos sensibles.
- Incorporar escaneo de secretos del árbol y del historial Git.
- Documentar creación, rotación y revocación.

**Pruebas:** clon limpio → setup → Compose válido; escáner sin hallazgos reales; logs sin secretos.  
**Cierre:** el repositorio puede publicarse sin comprometer credenciales.

### Fase 3A — Funcionalidad faltante Java/Go

**Responsable:** Persona 1.  
**Puede correr en paralelo con:** Fase 3B.

- Completar `PUT` y `DELETE /departamentos/{id}` con reglas, migraciones si aplican y errores.
- Revalidar CRUD, unicidad y auditoría de Empleados.
- Completar Perfil: apellido, código postal, cargo, área, número de empleado, foto, biografía,
  redes sociales y dirección completa.
- Mantener autorización por propiedad: otro usuario recibe 403.
- Sincronizar campos normativos desde `empleado.creado`/`empleado.actualizado`.
- Mantener archivado por `empleado.retirado`.
- Permitir al empleado ver su información de perfil de forma centralizada mediante el contrato
  público correspondiente.
- Actualizar OpenAPI, migraciones y pruebas.

**Cierre:** CRUD y modelos del PDF completos, con pruebas de propiedad, archivado y errores.

### Fase 3B — Funcionalidad faltante Node/Python

**Responsable:** Persona 2.  
**Puede correr en paralelo con:** Fase 3A.

- Completar/explicitar CRUD de Vacaciones, incluido cambio válido de un período programado.
- Mantener coherencia de fechas, no pasado, no solapamiento y empleado existente.
- Externalizar plantillas configurables para todos los correos exigidos.
- Verificar onboarding sin contraseña temporal: cuenta inactiva, token expirante y BCrypt.
- Verificar login, activación, recuperación y cambio de contraseña.
- Verificar suspensión temporal, reactivación y baja permanente no reversible.
- Confirmar que no existe `/register` público.
- Actualizar OpenAPI, migraciones y pruebas.

**Cierre:** onboarding, vacaciones, notificaciones y offboarding cumplen literalmente el PDF.

### Fase 4 — Catálogo de eventos e idempotencia integral

**Responsable:** ambas; integra Persona 2.  
**Objetivo:** garantizar comunicación asíncrona normativa y tolerancia a duplicados.

- Versionar `docs/eventos.md` con productor, consumidor, routing key y payload de cada evento.
- Validar envelope completo en todos los productores.
- Rechazar o aparcar eventos inválidos sin bloquear permanentemente la cola.
- Persistir `eventId` procesado en cada consumidor con efecto de negocio.
- Hacer atómica la operación negocio + deduplicación cuando sea posible.
- Definir retry, dead-letter y política ante mensajes poison.
- Confirmar colas/exchanges durables, acknowledgements y reconexión.
- Probar duplicados en Auth, Perfiles, Vacaciones y Notificaciones.
- Mantener contexto W3C en headers AMQP.

**Cierre:** republicar cada evento crítico no duplica cuentas, perfiles, correos ni transiciones.

### Fase 5 — APIs, health, Swagger y borde único

**Responsable:** ambas; integra Persona 2.  
**Objetivo:** cerrar los requisitos transversales HTTP.

- Auditar todas las rutas públicas del Gateway contra el PDF.
- Mantener los servicios internos y el Gateway como único puerto de negocio.
- Completar OpenAPI/Swagger UI de los siete servicios.
- Documentar esquemas, errores 400/401/403/404/409/503 y seguridad Bearer.
- Revalidar `/health` con base/broker disponibles y caídos.
- Asegurar que `/health`, `/metrics` y documentación necesaria no quedan bloqueados por JWT.
- Mantener el health propio del Gateway independiente de backends.
- Opcional: agregar `/health/services` sin sustituir los health individuales.
- Opcional: agregar `GET /empleados/{id}/completo` con manejo de fallos parciales.

**Cierre:** contratos completos y verificables; ninguna API interna se expone accidentalmente.

### Fase 6 — Pruebas, cobertura y aceptación funcional

**Responsable:** ambas sobre sus servicios.  
**Objetivo:** alcanzar y bloquear la cobertura mínima del proyecto.

- Unitarias de dominio en los siete servicios.
- Integración con bases reales/migraciones y RabbitMQ donde corresponda.
- Contratos HTTP y eventos.
- Pruebas negativas de autenticación, autorización y propiedad.
- BDD de onboarding, perfil, vacaciones y offboarding.
- Casos de duplicación, caída de dependencia y recuperación.
- Reportes JUnit, JaCoCo, LCOV/Cobertura Python/Go según tecnología.
- Umbral ≥70 % por servicio, no solo promedio del monorepo.
- Pruebas sin depender del orden ni de datos residuales.

**Cierre:** siete reportes verificables ≥70 %, BDD completa en verde y evidencia versionada.

### Fase 7 — Pipelines Jenkins para los siete servicios

**Responsable:** Persona 1; Persona 2 valida Node/Python.  
**Objetivo:** uniformar CI con política de fallo real.

Cada Jenkinsfile debe tener:

1. Checkout.
2. Build/instalación reproducible.
3. Lint/formato.
4. Pruebas unitarias.
5. Pruebas de integración.
6. Publicación de resultados y cobertura.
7. SonarQube/análisis estático.
8. Análisis de dependencias e imagen.
9. Quality Gate con cobertura mínima del 70 %.
10. Docker build solo si calidad pasa.
11. Etiquetado inmutable por commit/build.
12. Push al Registry.
13. Deploy controlado y smoke test.

- Crear pipelines para Gateway, Departamentos, Auth, Perfiles y Notificaciones.
- Reutilizar y endurecer los de Empleados y Vacaciones.
- Configurar webhook/trigger sin credenciales en el repositorio.
- Serializar E2E compartido para evitar colisiones.

**Cierre:** un fallo de prueba, seguridad, cobertura o Quality Gate impide package/push/deploy.

### Fase 8 — Docker Registry, artefactos y despliegue

**Responsable:** Persona 1.  
**Objetivo:** completar el flujo CI/CD exigido.

- Incorporar Registry local persistente o documentar Docker Hub privado.
- Autenticar Jenkins mediante credenciales administradas.
- Publicar imágenes con versión y digest; no depender únicamente de `latest`.
- Crear Compose de despliegue que consuma imágenes publicadas.
- Mantener Compose de desarrollo capaz de construir con `--build`.
- Añadir smoke test y rollback documentado.
- Conservar artefactos y trazabilidad commit → imagen → despliegue.

**Cierre:** un pipeline verde publica y despliega una imagen identificable y verificable.

### Fase 9 — Revalidación de observabilidad completa

**Responsable:** Persona 2; apoyo Persona 1.  
**Objetivo:** reutilizar el Reto 8 ya cerrado y revalidarlo dentro de la arquitectura final.

- Mantener logs JSON con timestamp, nivel, servicio, mensaje, contexto y `traceId`.
- Confirmar Loki/Promtail para los siete servicios.
- Completar métricas técnicas y de negocio: empleados creados, notificaciones enviadas, errores,
  tráfico y latencia.
- Versionar dashboards Grafana con salud, RED y métricas de negocio.
- Crear alertas de servicio caído, tasa 5xx y comportamiento crítico.
- Aprovisionar contact point externo de Discord sin versionar el webhook.
- Verificar trazas completas de onboarding y vacaciones en Zipkin.
- Correlacionar dashboard → logs → traceId → traza.
- Probar que la caída del stack de observabilidad no rompe el negocio.

**Cierre:** dashboards y alertas se reconstruyen desde Git y existe evidencia real del canal externo.

### Fase 10 — Escalabilidad, balanceo y alta disponibilidad

**Responsable:** ambas; integra Persona 2.  
**Objetivo:** cumplir el 10 % de escalabilidad y resiliencia.

- Incorporar Traefik/Nginx u otra capa de balanceo compatible con el Gateway.
- Ejecutar al menos dos réplicas del servicio seleccionado sin nombres de contenedor fijos.
- Mantener estado en bases/broker, no en memoria local de una instancia.
- Implementar health-aware routing y retirar instancias enfermas.
- Probar distribución de tráfico entre réplicas.
- Detener una instancia y comprobar continuidad sin error para el cliente.
- Revisar scheduler con lock distribuido o elección de líder.
- Revisar consumidores RabbitMQ para reparto seguro entre réplicas.
- Revalidar circuit breaker, fallback, timeouts y reintentos.

**Cierre:** una réplica cae y el sistema sigue atendiendo; la evidencia muestra balanceo y recovery.

### Fase 11 — Seguridad integral

**Responsable:** Persona 2; revisión Persona 1.  
**Objetivo:** verificar seguridad funcional y de suministro.

- RBAC ADMIN/USER en todas las rutas públicas.
- Propiedad del perfil y recursos personales.
- Validación JWT: firma, expiración, tipo, sujeto y rol.
- Política de contraseña y BCrypt.
- Rate limiting básico para login/recuperación si el alcance lo permite.
- Cabeceras seguras, validación de entrada y errores sin detalles internos.
- Escaneo SAST, dependencias, imágenes y secretos en CI.
- Usuarios no root e imágenes mínimas cuando sea viable.
- Auditoría de cuentas retiradas y eventos de seguridad.
- Confirmar que correo/eventos nunca transportan contraseñas.

**Bonificación separada:** migrar a Keycloak/Authentik con OAuth2/OIDC y JWKS solo después de cerrar
la opción JWT obligatoria y su regresión.

**Cierre:** pruebas de seguridad pasan y no existen hallazgos críticos abiertos.

### Fase 12 — Orquestación reproducible y redes

**Responsable:** Persona 1.  
**Objetivo:** levantar todo con un comando en un equipo limpio.

- Compose raíz con siete servicios, bases, RabbitMQ, Mailhog, observabilidad, Jenkins, SonarQube y
  Registry.
- Separar redes de frontend, backend, observabilidad y CI/CD según necesidad mínima.
- Publicar solo Gateway y UIs administrativas necesarias.
- Healthchecks, dependencias, volúmenes y límites razonables.
- Versiones de imágenes fijadas; evitar `latest` en infraestructura.
- `docker compose up --build` compila, empaqueta y despliega el entorno de desarrollo.
- Validar arranque en frío y reinicio conservando datos.
- Validar `down` y procedimiento destructivo `down -v` claramente diferenciados.

**Cierre:** máquina limpia + setup de secretos + un comando deja el sistema saludable.

### Fase 13 — Documentación y datos de demostración

**Responsable:** ambas; integra quien no esté cerrando Fase 12.

- README final con descripción, arquitectura, requisitos previos y versiones.
- Tabla microservicio ↔ lenguaje ↔ base ↔ puerto interno.
- Instrucciones de setup, ejecución, URLs y ejemplos `curl`.
- Endpoints principales del Gateway.
- Configuración y rotación de secretos.
- Accesos iniciales referenciando variables/archivos, nunca claves reales.
- `docs/arquitectura.md`, `docs/eventos.md` y `docs/guias/`.
- Guías de Jenkins, SonarQube, RabbitMQ, Grafana, Loki, Prometheus, Zipkin y Registry.
- Scripts de inicialización y datos de demostración idempotentes.
- Diagramas de contexto, contenedores, eventos, onboarding, CI/CD y despliegue.

**Cierre:** una persona externa puede instalar, ejecutar, probar y entender el sistema sin ayuda oral.

### Fase 14 — Validación final, caos y paquete de entrega

**Responsable:** conjunta.  
**Objetivo:** demostrar todos los criterios y producir el entregable.

- Ejecutar desde clon/archivo limpio, sin caché ni `.env` previo.
- Generar secretos, levantar todo y esperar healthchecks.
- Ejecutar suites, cobertura, Quality Gates y BDD.
- Demostrar onboarding, perfil, vacaciones y offboarding de extremo a extremo.
- Republicar eventos y demostrar deduplicación.
- Caer base, broker, microservicio y una réplica de forma controlada.
- Demostrar circuit breaker, fallback, balanceo y recuperación.
- Capturar dashboards, alerta externa, logs correlacionados y traza completa.
- Ejecutar auditoría final de secretos y licencias/artefactos no deseados.
- Verificar que no se incluyen `.env`, `secrets/`, volúmenes, `node_modules`, builds o credenciales.
- Generar `.zip`/`.tar.gz`, descomprimirlo en otra ruta y repetir el smoke test.
- Completar matriz requisito → evidencia y acta de pendientes igual a cero.

**Cierre:** paquete reproducible, repositorio limpio y todos los criterios obligatorios demostrados.

## Dependencias y paralelización

```text
F0 → F1 → F2
          ├── F3A ─┐
          └── F3B ─┴→ F4 → F5 → F6 → F7 → F8
                                      └────────→ F9
F8 + F9 → F10 → F11 → F12 → F13 → F14
```

- F3A y F3B son el bloque principal de trabajo paralelo.
- F9 puede avanzar después de F6 mientras se completan pipelines/Registry.
- F10 no empieza hasta que health, imágenes y pruebas sean confiables.
- Seguridad se aplica desde F2; F11 es la auditoría/cierre, no el inicio de seguridad.
- Documentación se actualiza por fase; F13 consolida y corrige.

## Matriz de evaluación → fases

| Criterio | Peso | Fases que lo cierran |
|---|---:|---|
| Funcionalidad | 35 % | 3A, 3B, 4, 5, 6, 14 |
| Calidad técnica | 25 % | 3A, 3B, 5, 6, 7 |
| Observabilidad | 15 % | 9, 14 |
| Seguridad y configuración | 10 % | 2, 11, 14 |
| Escalabilidad y resiliencia | 10 % | 4, 10, 14 |
| DevOps | 5 % | 7, 8, 12, 14 |
| Documentación | 5 % | 13, 14 |

> Los pesos publicados suman 105 %. No se normalizan en el plan: se atienden todos los criterios
> tal como aparecen en el PDF y se registra la aclaración para consulta con el docente.

## Requisitos opcionales/bonificación

No bloquean el cierre obligatorio y se implementan únicamente después de la Fase 14 o si el equipo
tiene margen validado:

1. Identity Provider Keycloak/Authentik con OAuth2/OIDC y JWKS.
2. `GET /empleados/{id}/completo` con composición y fallos parciales.
3. Health consolidado público separado del `/health` propio del Gateway.
4. Más de un servicio escalado y pruebas de carga comparativas.
5. TLS local, firma asimétrica y rotación automatizada avanzada.

## Evidencias mínimas obligatorias

- Salida de `docker compose config` sin secretos y stack saludable.
- Swagger/OpenAPI de siete servicios y rutas públicas del Gateway.
- Reportes de pruebas y cobertura ≥70 % por servicio.
- Jenkins Stage View y Quality Gates.
- Imagen publicada en Registry con tag/digest.
- BDD de cuatro flujos de negocio.
- Prueba de duplicado por evento sin efecto duplicado.
- Health `UP` y `DOWN` por dependencia.
- Circuit breaker `CLOSED → OPEN → HALF_OPEN → CLOSED`.
- Dos réplicas, reparto de tráfico y continuidad tras caída.
- Dashboard Grafana, consulta Loki, traza Zipkin y alerta Discord.
- Escaneo de secretos del árbol y del historial.
- Arranque desde paquete descomprimido.

## Riesgos y controles

1. **Duplicar el proyecto:** trabajar en rama, no copiarlo dentro del mismo repositorio.
2. **Perder el historial estable:** etiqueta y rama remota antes de cambios.
3. **Secretos históricos:** escanear antes de la entrega; rotar y sanear con procedimiento aprobado.
4. **Falso cumplimiento de health:** probar dependencia caída manteniendo vivo el servicio.
5. **Cobertura global engañosa:** umbral por servicio.
6. **Scheduler duplicado al escalar:** lock distribuido/liderazgo antes de dos réplicas.
7. **Alertas manuales:** provisioning versionado y reconstrucción desde cero.
8. **Puertos en conflicto:** mantener mapa de host; el diagrama del PDF es referencial.
9. **Pipeline decorativo:** fallos reales deben detener package/push/deploy.
10. **Evidencia no reproducible:** cada captura referencia comando, fecha, commit y resultado esperado.

## Regla para marcar una fase como terminada

Una fase solo pasa a ✅ cuando:

1. Código/configuración están versionados en `proyecto-final`.
2. Pruebas automáticas de la fase pasan.
3. No reduce cobertura ni rompe BDD previa.
4. Existe evidencia reproducible, no solo una captura aislada.
5. La otra persona revisó los contratos compartidos.
6. `STATUS.md` se actualizó en el mismo commit.
7. No se introdujeron secretos ni artefactos locales.
