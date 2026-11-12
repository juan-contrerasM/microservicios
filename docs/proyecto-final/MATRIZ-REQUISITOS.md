# Matriz de requisitos — Proyecto Final

Auditoría inicial sobre la rama `proyecto-final`. Esta matriz se completa con resultados de
ejecución durante la Fase 1. Los estados iniciales no sustituyen pruebas ni evidencias.

Leyenda: ✅ reutilizable/verificado · 🟡 parcial o pendiente de revalidación · ❌ faltante · ⭐ opcional ·
N/A no aplica (con justificación) · 🧪 implementado y con pruebas unitarias en verde; falta la validación
integrada con Docker (compose + BDD) para pasar a ✅

## Matriz por servicio — Estándar E01–E25

Cada práctica de los retos aplica a los siete servicios (ver «Principio rector» y la definición de
E01–E25 en [`PLAN-PROYECTO-FINAL.md`](PLAN-PROYECTO-FINAL.md)). Estado inicial tomado de una auditoría
del código de la rama `proyecto-final` el 2026-11-11 y actualizado al cerrar las cuatro olas (2026-11-12); 🟡 significa que existe algo, pero falta
revalidarlo contra el estándar. **Ninguna fase se cierra con celdas ❌ o 🟡 en sus filas.**

| ID | Requisito | Empleados | Departamentos | Auth | Perfiles | Vacaciones | Notificaciones | Gateway | Fase |
|---|---|---|---|---|---|---|---|---|---|
| E01 | Dockerfile multi-stage, base fijada, `.dockerignore` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 7 |
| E02 | Usuario no root, `curl`/`wget` | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | 7 |
| E03 | Configuración y secretos por env/`*_FILE` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 2 ✅ / 14 ✅ |
| E04 | Sin `ports:` (solo `expose:`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | N/A borde (8080) | 14 |
| E05 | BD propia, migraciones con rollback, `UNIQUE` | ✅ rollback y UNIQUE probados offline | ✅ `.down.sql` probado offline | ✅ downgrade `--sql` | ✅ | ✅ `pgm` simulado | ✅ downgrade `--sql` | N/A sin BD | 5 |
| E06 | Healthcheck Compose → `/health` + `service_healthy` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ `service_started` hacia backends, excepción documentada | 14 |
| E07 | `/health` C2 con 200/503 real | ✅ timeout 2 s por check | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ propio + agregado en `/health/services` | 5 |
| E08 | OpenAPI completo + Swagger UI + `openapi.yaml` + `bearerAuth` | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ `/swagger` con 7 contratos | 5 |
| E09 | Error HTTP C3 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 5 |
| E10 | Timeout + retry backoff + circuit breaker en clientes HTTP | ✅ Resilience4j → Departamentos | N/A | N/A | N/A | N/A | N/A | ✅ circuito por backend, timeout por servicio y retry solo GET | 6 |
| E11 | Productor: envelope, post-commit, traceparent | ✅ `afterCommit` | N/A | ✅ | N/A | ✅ incluido scheduler | N/A | N/A | 4 |
| E12 | Consumidor: dedup atómica, retry, DLQ, poison | ✅ `q.empleados` (vacaciones) | N/A | ✅ | ✅ | ✅ | ✅ correo tras commit | N/A | 4 |
| E13 | Unitarias con dobles | ✅ 104 | ✅ 43 | ✅ 76 | ✅ 54 | ✅ 115 | ✅ 42 | ✅ 72 | 8 |
| E14 | Integración con BD/broker reales | ✅ PostgreSQL (Testcontainers) | ✅ MySQL | ✅ PostgreSQL + RabbitMQ | ✅ PostgreSQL + RabbitMQ (Testcontainers) | ✅ PostgreSQL + RabbitMQ | ✅ PostgreSQL + RabbitMQ + SMTP | ✅ backends HTTP reales simulados | 8 |
| E15 | Cobertura ≥70 % de la lógica de negocio | ✅ 87,95 % | ✅ 74,2 % | ✅ 82,79 % | ✅ 88,67 % | ✅ 82,27 % todo `src/` | ✅ 78,21 % | ✅ 97,03 % | 8 |
| E16 | Un comando, JUnit + cobertura en ruta documentada | ✅ `mvnw verify` + `check` | ✅ `scripts/test.sh` | ✅ `pytest` | ✅ `mvnw verify` + `check` | ✅ `npm test` | ✅ `pytest` | ✅ `npm test` | 8 |
| E17 | Evidencia de mutación | ✅ | ✅ (reveló un caso sin prueba) | ✅ | ✅ | ✅ | ✅ | ✅ | 8 |
| E18 | Jenkinsfile con todas las etapas | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 9 |
| E19 | Proyecto SonarQube propio | ✅ Maven | ✅ | ✅ | ✅ Maven | ✅ | ✅ | ✅ | 9 |
| E20 | Métricas C4 + negocio | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 11 |
| E21 | Logs C5 en Loki | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 11 |
| E22 | OpenTelemetry W3C + Zipkin | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 11 |
| E23 | Stateless, escala con `--scale=2` | ✅ demo: reparto 15/30, 2 consumidores en `q.empleados` | ✅ demo: reparto 30/60 | ✅ demo: reparto 15/30 en login, 2 consumidores en `q.auth` | ✅ demo: reparto 15/30, 2 consumidores en `q.perfiles` | ✅ demo: reparto 15/30 + advisory lock del scheduler | ✅ demo: reparto 15/30, 2 consumidores en `q.notificaciones` | ✅ demo: reparto 30/60 detrás de Traefik | 12 |
| E24 | Seguridad de entrada, RBAC/propiedad | ✅ validación + RBAC en Gateway | ✅ | ✅ JWT ≥32 B, BCrypt, logs sin secretos probado | ✅ propiedad del perfil en Gateway | ✅ | ✅ | ✅ cabeceras, CORS, 413, rate limiting | 13 |
| E25 | README con plantilla C6 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 15 |

## Funcionalidad

| Requisito | Estado | Evidencia |
|---|---|---|
| Siete servicios y Gateway | ✅ | Empleados, Departamentos, Auth, Perfiles, Vacaciones, Notificaciones y Gateway existen |
| CRUD de Empleados | ✅ | Alta, consulta, actualización y retiro verificados por BDD (23 escenarios) e integración con PostgreSQL |
| Estados `ACTIVO`, `EN_VACACIONES`, `RETIRADO` | ✅ | Empleados consume `vacaciones.iniciadas/finalizadas` (`q.empleados`); BDD verifica `EN_VACACIONES` y la vuelta a `ACTIVO`; el retiro es definitivo |
| Retiro lógico con `fechaRetiro` | ✅ | BDD de offboarding: estado `RETIRADO` y auditoría |
| Auditoría de retirados por rango | ✅ | `GET /empleados?estado=RETIRADO&desde=&hasta=` verificado por BDD |
| Unicidad email/número de empleado | ✅ | `EmpleadoRepositoryIT` con PostgreSQL real (restricciones UNIQUE) y BDD 409 |
| CRUD de Departamentos | ✅ | POST, GET, PUT y DELETE verificados contra MySQL limpio; DELETE retorna 204 |
| Validación Empleados → Departamentos | ✅ | Timeout, reintentos y circuit breaker; `PENDIENTE_VALIDACION` verificado en `scripts/caos.sh` |
| Auth sin `/register` público | ✅ | No se encontró endpoint público de registro |
| Cuenta inactiva y token de activación | ✅ | Flujo completo revalidado en la regresión BDD desde el correo |
| Login y JWT | ✅ | HS256 con secreto ≥ 32 bytes, tipo, sujeto, rol y expiración validados; rate limiting de login |
| Cambio y recuperación de contraseña | ✅ | Endpoints, eventos y regresión BDD verificados |
| BCrypt y ausencia de contraseña temporal | ✅ | Hash BCrypt y activación por token cubiertos por 31 pruebas de Auth |
| Suspensión temporal y baja permanente | ✅ | Inicio/fin de vacaciones y retiro irreversible verificados en Auth y BDD |
| Perfil propio y 403 sobre perfil ajeno | ✅ | Propiedad y acceso ajeno revalidados por el flujo integrado |
| Perfil con todos los campos normativos | ✅ | Modelo, migración Liquibase y onboarding sobre base limpia correctos |
| Archivado por retiro | ✅ | Consumidor y flujo integrado de retiro revalidados |
| CRUD de Vacaciones | ✅ | Incluye `PUT /vacaciones/{id}` para períodos PROGRAMADA futuros |
| Validaciones de vacaciones | ✅ | Fechas, pasado, estado, solapamiento y recálculo cubiertos por 66 pruebas |
| Scheduler inicio/fin | ✅ | Advisory lock: con dos réplicas solo una ejecuta (prueba de integración real) |
| Correos de eventos obligatorios | ✅ | Mailhog, eventos y persistencia revalidados en la regresión integrada |
| Plantillas configurables | ✅ | Catálogo JSON externo montado en solo lectura y cubierto por pruebas |
| Borde único y rutas del Gateway | ✅ | Balanceador como único puerto de negocio; los seis servicios sin puerto publicado |
| Composición empleado completo | ⭐ | No obligatoria; pendiente si se busca bonificación |

## Eventos e integración

| Requisito | Estado | Evidencia |
|---|---|---|
| RabbitMQ | ✅ | Exchange topic y colas existentes |
| Envelope común completo | ✅ | Validado por los cinco consumidores (C1) y por las pruebas de integración |
| Catálogo normativo | ✅ | `docs/eventos.md` consolidado |
| Deduplicación persistente | ✅ | Atómica en los cinco consumidores; BDD de duplicado deja una notificación y un perfil |
| Reintentos/DLQ/poison messages | ✅ | Política común (retry 5 s ×3, DLQ por cola) verificada con RabbitMQ real en Fase 4 y por la integración de Fase 8; métrica `eventos_dlq_total` y alerta |
| Headers W3C en AMQP | ✅ | Implementados y evidenciados en el Reto 8 |
| REST permitido y resiliencia | ✅ | Solo Gateway→servicios y Empleados→Departamentos, ambos con resiliencia |

## Calidad técnica

| Requisito | Estado | Evidencia |
|---|---|---|
| OpenAPI/Swagger en siete servicios | ✅ | Siete contratos con prueba de drift y Swagger agregada en `/swagger` |
| Unitarias por servicio | ✅ | Siete suites con dobles y umbral automático (Fase 8) |
| Integración por servicio | ✅ | `scripts/test-integration.sh`: PostgreSQL, MySQL, RabbitMQ y SMTP reales; Testcontainers en Java (Fase 8) |
| Cobertura ≥70 % por servicio | ✅ | Siete servicios entre 74,2 % y 97,03 %, con exclusiones documentadas en cada README (Fase 8) |
| Health 200/503 y dependencias | ✅ | Siete `/health` C2; 503 en ~2 s con la base caída (`scripts/caos.sh`) |
| Dockerfile por servicio | ✅ | Los siete servicios tienen Dockerfile |
| Imágenes optimizadas/no-root | ✅ | Siete imágenes multi-stage, UID 10001, 0 CVE CRITICAL |
| `openapi.yaml` por servicio | ✅ | Siete archivos versionados |
| Swagger UI en el Gateway (`/swagger`) | ✅ | Siete contratos agregados |
| Cuatro lenguajes | ✅ | Java, Go, Node.js y Python |
| Base independiente por servicio | ✅ | Seis bases, una por servicio, solo en la red `data` |

## CI/CD y orquestación

| Requisito | Estado | Evidencia |
|---|---|---|
| Jenkins en Compose | ✅ | Aprovisionado con JCasC; entrypoint normalizado y acceso HTTP 200 verificado desde Windows |
| SonarQube/Quality Gate | ✅ | Siete proyectos, gate ≥ 70 % en verde |
| Jenkinsfile en siete servicios | ✅ | Generados desde la plantilla; siete builds SUCCESS |
| Lint y análisis de seguridad | ✅ | Lint por stack, Trivy (dependencias e imagen) y Gitleaks en cada pipeline |
| Package Docker | ✅ | Tag `<commit>-<build>` con etiqueta del commit |
| Push a Registry | ✅ | `localhost:5001` con digest archivado |
| Deploy automatizado | ✅ | Etapa Deploy con smoke test y rollback automático |
| Trigger por webhook | ✅ | `pollSCM` cada 2 min (repositorio local sin servidor Git para webhooks) |
| `docker compose up --build` completo | ✅ | Siete servicios construyen, BDD pasa y Jenkins arranca tras normalizar el entrypoint |
| Seis redes del PDF | ✅ | `frontend`, `backend`, `messaging`, `data`, `observability`, `cicd`; el Gateway no resuelve bases ni broker y Jenkins no ve las bases (Fase 14) |
| Puertos del PDF sin colisión | ✅ | Jenkins 8090, Prometheus 9090, Registry 5001, Grafana 3000, Zipkin 9411, SonarQube 9000, RabbitMQ UI 15672, Mailhog 8025 (Fase 14) |
| Solo el Gateway publica puerto de negocio | ✅ | 8081–8086 y AMQP 5672 rechazan la conexión desde el host (Fase 14) |
| Etapa E2E serializada en pipelines | ✅ | `lock('ecosistema-e2e')` en E2E y Deploy |
| Escaneo de dependencias e imágenes | ✅ | Trivy en todos los pipelines; 0 CRITICAL/HIGH en dependencias |

## Observabilidad

| Requisito | Estado | Evidencia |
|---|---|---|
| Logs JSON centralizados | ✅ | Loki/Promtail y correlación evidenciados en Reto 8 |
| Prometheus/Grafana | ✅ | Siete targets y dashboard versionado en el último estado de Reto 8 |
| Métricas técnicas y de negocio | ✅ | Contadores de negocio en los siete servicios y de eventos en los cuatro consumidores (Fase 11) |
| Dashboards | ✅ | `reto8-observabilidad` + `pf-negocio-eventos` (negocio, eventos/DLQ, circuit breakers), aprovisionados desde Git |
| Alertas externas | ✅ | Cinco reglas; contact point Discord + correo. Alerta de DLQ real entregada en Discord y en Mailhog (Fase 11) |
| OTel/Zipkin W3C | ✅ | Trazas HTTP/AMQP de onboarding y vacaciones evidenciadas |
| Correlación log ↔ traza | ✅ | `traceId` y enlace Loki→Zipkin existentes |

## Seguridad y configuración

| Requisito | Estado | Evidencia |
|---|---|---|
| Cero secretos versionados | ✅ | Plantilla con marcadores; Gitleaks sin hallazgos en árbol e historial |
| `.env` y `secrets/` ignorados | ✅ | `.env`, `secrets/`, llaves y certificados privados excluidos; plantilla sin valores utilizables |
| Compose Secrets/Vault | ✅ | Quince secretos montados por archivo; bases, broker, servicios, Jenkins, Sonar y Grafana validados |
| Script de generación local | ✅ | Scripts PowerShell y POSIX generan 15 secretos criptográficos; prueba aislada correcta |
| Rotación documentada | ✅ | `SEGURIDAD-SECRETOS.md` cubre creación, rotación y revocación específica |
| RBAC y propiedad | ✅ | USER solo lectura, cambio de su contraseña y su perfil; ajeno 403 (BDD) |
| IdP OIDC/JWKS | ⭐ | Bonificación no implementada |
| Escaneo de secretos/SAST/dependencias/imágenes | ✅ | Gitleaks árbol e historial, Trivy y SonarQube en CI |

## Escalabilidad y resiliencia

| Requisito | Estado | Evidencia |
|---|---|---|
| Circuit breaker con fallback | ✅ | Resilience4j en Empleados→Departamentos y circuito aislado por backend en el Gateway; fallback C3 503 y estado Prometheus |
| Timeouts y reintentos | ✅ | Empleados usa 5 s y backoff 1–2–4 s; Gateway permite timeout por backend y reintenta únicamente GET con backoff 1–2–4 s |
| Publicación de eventos post-commit | ✅ | Empleados, Auth y Vacaciones publican después del commit |
| Servicios stateless | ✅ | Los siete escalan a 2 réplicas; el único estado en memoria (rate limit) está documentado |
| Load balancer | ✅ | Traefik 3.6 |
| Réplicas horizontales | ✅ | `scripts/demo-escalado.sh`: los siete con 2 réplicas sanas y cinco colas con 2 consumidores |
| Health-aware routing | ✅ | Health checks activos cada 5 s a `/health` |
| Continuidad al caer una instancia | ✅ | 210/210 respuestas 2xx (30 por servicio) con una réplica de cada servicio detenida |

## Documentación y entrega

| Requisito | Estado | Evidencia |
|---|---|---|
| README final mínimo | ✅ | README raíz con las siete secciones del PDF |
| Arquitectura final | ✅ | `docs/arquitectura.md` con diagramas |
| `docs/eventos.md` | ✅ | Catálogo, topología y políticas |
| Guías de terceros | ✅ | Ocho guías en `docs/guias/` |
| Scripts/seed idempotente | ✅ | `scripts/seed-data.sh` vía Gateway; segunda ejecución no crea nada (Fase 14) |
| README por servicio con plantilla común | ✅ | Ficha C6 en los siete README |
| Equivalencia con la estructura de referencia del PDF | ✅ | [`docs/estructura-entrega.md`](../estructura-entrega.md) (Fase 14) |
| Paquete `.zip`/`.tar.gz` limpio | ✅ | `scripts/empaquetar.sh`; validado desde una copia descomprimida |

## Condiciones detectadas del entorno de baseline

- Rama local: `proyecto-final`.
- Punto estable: etiqueta local `reto8-completo` sobre `b28c220`.
- Node, Java, Go y Python no están instalados en el host actual.
- Las pruebas del baseline se ejecutarán en contenedores reproducibles.
- La rama `proyecto-final` y la etiqueta `reto8-completo` ya están publicadas en el remoto (verificado el 2026-11-11).
