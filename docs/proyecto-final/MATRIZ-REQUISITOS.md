# Matriz de requisitos — Proyecto Final

Auditoría inicial sobre la rama `proyecto-final`. Esta matriz se completa con resultados de
ejecución durante la Fase 1. Los estados iniciales no sustituyen pruebas ni evidencias.

Leyenda: ✅ reutilizable/verificado · 🟡 parcial o pendiente de revalidación · ❌ faltante · ⭐ opcional ·
N/A no aplica (con justificación) · 🧪 implementado y con pruebas unitarias en verde; falta la validación
integrada con Docker (compose + BDD) para pasar a ✅

## Matriz por servicio — Estándar E01–E25

Cada práctica de los retos aplica a los siete servicios (ver «Principio rector» y la definición de
E01–E25 en [`PLAN-PROYECTO-FINAL.md`](PLAN-PROYECTO-FINAL.md)). Estado inicial tomado de una auditoría
del código de la rama `proyecto-final` el 2026-11-11 y actualizado al integrar la Ola 1 (Fases 4, 5 y 7); 🟡 significa que existe algo, pero falta
revalidarlo contra el estándar. **Ninguna fase se cierra con celdas ❌ o 🟡 en sus filas.**

| ID | Requisito | Empleados | Departamentos | Auth | Perfiles | Vacaciones | Notificaciones | Gateway | Fase |
|---|---|---|---|---|---|---|---|---|---|
| E01 | Dockerfile multi-stage, base fijada, `.dockerignore` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 7 |
| E02 | Usuario no root, `curl`/`wget` | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | ✅ UID 10001 | 7 |
| E03 | Configuración y secretos por env/`*_FILE` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 2 |
| E04 | Sin `ports:` (solo `expose:`) | ✅ | ❌ 8081 | ✅ | ❌ 8083 | ❌ 8085 | ❌ 8084 | N/A borde | 14 |
| E05 | BD propia, migraciones con rollback, `UNIQUE` | ✅ rollback y UNIQUE probados offline | ✅ `.down.sql` probado offline | ✅ downgrade `--sql` | ✅ | ✅ `pgm` simulado | ✅ downgrade `--sql` | N/A sin BD | 5 |
| E06 | Healthcheck Compose → `/health` + `service_healthy` | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 14 |
| E07 | `/health` C2 con 200/503 real | ✅ timeout 2 s por check | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ propio | 5 |
| E08 | OpenAPI completo + Swagger UI + `openapi.yaml` + `bearerAuth` | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ drift test | ✅ `/swagger` con 7 contratos | 5 |
| E09 | Error HTTP C3 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 5 |
| E10 | Timeout + retry backoff + circuit breaker en clientes HTTP | ✅ Resilience4j → Departamentos | N/A | N/A | N/A | N/A | N/A | ❌ solo timeout de 35 s | 6 |
| E11 | Productor: envelope, post-commit, traceparent | ✅ `afterCommit` | N/A | ✅ | N/A | ✅ incluido scheduler | N/A | N/A | 4 |
| E12 | Consumidor: dedup atómica, retry, DLQ, poison | N/A | N/A | ✅ | ✅ | ✅ | ✅ correo tras commit | N/A | 4 |
| E13 | Unitarias con dobles | 🟡 | 🟡 | ✅ | ❌ 1 archivo | ✅ | ✅ | ❌ 1 archivo | 8 |
| E14 | Integración con BD/broker reales | 🟡 | ❌ | 🟡 | ❌ | 🟡 | 🟡 | ❌ | 8 |
| E15 | Cobertura ≥70 % de la lógica de negocio | 🟡 sin medir | 🟡 sin medir | ✅ 72 % | 🟡 sin medir | 🟡 100 % solo módulos seleccionados | ✅ 73 % | 🟡 sin medir | 8 |
| E16 | Un comando, JUnit + cobertura en ruta documentada | 🟡 | 🟡 | ✅ | 🟡 | ✅ | 🟡 | 🟡 | 8 |
| E17 | Evidencia de mutación | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | 8 |
| E18 | Jenkinsfile con todas las etapas | 🟡 existe | ❌ | ❌ | ❌ | 🟡 existe | ❌ | ❌ | 9 |
| E19 | Proyecto SonarQube propio | 🟡 Maven | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | 9 |
| E20 | Métricas C4 + negocio | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 11 |
| E21 | Logs C5 en Loki | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 11 |
| E22 | OpenTelemetry W3C + Zipkin | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 11 |
| E23 | Stateless, escala con `--scale=2` | 🟡 | ❌ puerto fijo | 🟡 | ❌ puerto fijo | ❌ puerto fijo + scheduler sin lock | ❌ puerto fijo | ❌ puerto host fijo sin balanceador | 12 |
| E24 | Seguridad de entrada, RBAC/propiedad | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 13 |
| E25 | README con plantilla C6 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 15 |

## Funcionalidad

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Siete servicios y Gateway | ✅ | Empleados, Departamentos, Auth, Perfiles, Vacaciones, Notificaciones y Gateway existen |
| CRUD de Empleados | 🟡 | Crear, listar, consultar, actualizar y retirar existen; falta revalidación final |
| Estados `ACTIVO`, `EN_VACACIONES`, `RETIRADO` | 🟡 | Modelo y flujos existen; falta revalidar transición completa |
| Retiro lógico con `fechaRetiro` | 🟡 | Implementado; falta prueba de auditoría final |
| Auditoría de retirados por rango | 🟡 | Contrato existente; falta prueba integrada |
| Unicidad email/número de empleado | 🟡 | Implementada; falta revalidación con base real |
| CRUD de Departamentos | ✅ | POST, GET, PUT y DELETE verificados contra MySQL limpio; DELETE retorna 204 |
| Validación Empleados → Departamentos | 🟡 | Cliente HTTP, timeout, retry y circuit breaker existentes |
| Auth sin `/register` público | ✅ | No se encontró endpoint público de registro |
| Cuenta inactiva y token de activación | ✅ | Flujo completo revalidado en la regresión BDD desde el correo |
| Login y JWT | 🟡 | JWT HS256 implementado; falta hardening final |
| Cambio y recuperación de contraseña | ✅ | Endpoints, eventos y regresión BDD verificados |
| BCrypt y ausencia de contraseña temporal | ✅ | Hash BCrypt y activación por token cubiertos por 31 pruebas de Auth |
| Suspensión temporal y baja permanente | ✅ | Inicio/fin de vacaciones y retiro irreversible verificados en Auth y BDD |
| Perfil propio y 403 sobre perfil ajeno | ✅ | Propiedad y acceso ajeno revalidados por el flujo integrado |
| Perfil con todos los campos normativos | ✅ | Modelo, migración Liquibase y onboarding sobre base limpia correctos |
| Archivado por retiro | ✅ | Consumidor y flujo integrado de retiro revalidados |
| CRUD de Vacaciones | ✅ | Incluye `PUT /vacaciones/{id}` para períodos PROGRAMADA futuros |
| Validaciones de vacaciones | ✅ | Fechas, pasado, estado, solapamiento y recálculo cubiertos por 66 pruebas |
| Scheduler inicio/fin | 🟡 | Implementado; falta validar escalado seguro |
| Correos de eventos obligatorios | ✅ | Mailhog, eventos y persistencia revalidados en la regresión integrada |
| Plantillas configurables | ✅ | Catálogo JSON externo montado en solo lectura y cubierto por pruebas |
| Borde único y rutas del Gateway | 🟡 | Gateway funciona como proxy; el commit base publicó puertos de desarrollo que deben revisarse |
| Composición empleado completo | ⭐ | No obligatoria; pendiente si se busca bonificación |

## Eventos e integración

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| RabbitMQ | ✅ | Exchange topic y colas existentes |
| Envelope común completo | 🟡 | Implementaciones existentes; falta auditoría por productor |
| Catálogo normativo | 🟡 | Eventos principales existen; falta documento final consolidado |
| Deduplicación persistente | 🟡 | Auth, Perfiles y Notificaciones tienen mecanismos; auditar Vacaciones y atomicidad |
| Reintentos/DLQ/poison messages | ❌ | No existe política final documentada y verificada |
| Headers W3C en AMQP | ✅ | Implementados y evidenciados en el Reto 8 |
| REST permitido y resiliencia | 🟡 | Gateway→servicios y Empleados→Departamentos; revalidar contra restricción final |

## Calidad técnica

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| OpenAPI/Swagger en siete servicios | 🟡 | Existe documentación en los siete; falta auditoría de completitud |
| Unitarias por servicio | 🟡 | Todas las suites actuales pasan; Departamentos y Perfiles requieren ampliación importante |
| Integración por servicio | ❌ | No todos tienen integración con base/broker real |
| Cobertura ≥70 % por servicio | 🟡 | Auth 72 %, Notificaciones 73 % y Vacaciones 100 % de módulos seleccionados; faltan mediciones comparables de los demás |
| Health 200/503 y dependencias | ❌ | Empleados retorna 200 en UP, pero al detener PostgreSQL no respondió dentro de 15 s en vez de entregar 503 |
| Dockerfile por servicio | ✅ | Los siete servicios tienen Dockerfile |
| Imágenes optimizadas/no-root | ❌ | Gateway, Vacaciones, Auth y Notificaciones tienen una sola etapa; solo los dos servicios Node usan un usuario distinto de root (ver E01/E02) |
| `openapi.yaml` por servicio | ❌ | Lo exige la estructura de entrega del PDF; hoy hay contrato estático solo en Departamentos (`openapi.yaml`), Gateway y Vacaciones (`openapi.json` dentro de `src/`) |
| Swagger UI en el Gateway (`/swagger`) | ❌ | El README de referencia del PDF lo enlaza en `localhost:8080/swagger` |
| Cuatro lenguajes | ✅ | Java, Go, Node.js y Python |
| Base independiente por servicio | 🟡 | Arquitectura existente; falta revalidar aislamiento y secretos |

## CI/CD y orquestación

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Jenkins en Compose | ✅ | Aprovisionado con JCasC; entrypoint normalizado y acceso HTTP 200 verificado desde Windows |
| SonarQube/Quality Gate | 🟡 | Base funcional del Reto 7; solo dos pipelines de aplicación |
| Jenkinsfile en siete servicios | ❌ | Solo Empleados y Vacaciones |
| Lint y análisis de seguridad | ❌ | No existe cobertura uniforme de ambos controles |
| Package Docker | 🟡 | Dos pipelines construyen imágenes locales |
| Push a Registry | ❌ | No hay Registry ni publicación versionada |
| Deploy automatizado | ❌ | No hay etapa final con smoke/rollback |
| Trigger por webhook | 🟡 | Hay polling/hook local; falta webhook final verificable |
| `docker compose up --build` completo | ✅ | Siete servicios construyen, BDD pasa y Jenkins arranca tras normalizar el entrypoint |
| Seis redes del PDF | ❌ | Hoy existe una sola red `microservices-network` |
| Puertos del PDF sin colisión | ❌ | Jenkins en 9090 (PDF: 8090) y Prometheus en 9091 (PDF: 9090); Registry inexistente (PDF: 5001) |
| Solo el Gateway publica puerto de negocio | ❌ | Departamentos 8081, Perfiles 8083, Notificaciones 8084 y Vacaciones 8085 publicados por el commit `b28c220` |
| Etapa E2E serializada en pipelines | ❌ | Necesaria al tener siete pipelines compartiendo el stack |
| Escaneo de dependencias e imágenes | ❌ | Ningún pipeline ejecuta SCA ni Trivy |

## Observabilidad

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Logs JSON centralizados | ✅ | Loki/Promtail y correlación evidenciados en Reto 8 |
| Prometheus/Grafana | ✅ | Siete targets y dashboard versionado en el último estado de Reto 8 |
| Métricas técnicas y de negocio | 🟡 | Revalidar que todas las métricas de negocio exigidas están visibles |
| Dashboards | ✅ | Dashboard aprovisionado; falta regresión en stack final |
| Alertas externas | 🟡 | Reglas/contact point existen; falta webhook local y evidencia de entrega final |
| OTel/Zipkin W3C | ✅ | Trazas HTTP/AMQP de onboarding y vacaciones evidenciadas |
| Correlación log ↔ traza | ✅ | `traceId` y enlace Loki→Zipkin existentes |

## Seguridad y configuración

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Cero secretos versionados | ✅ | Plantilla con marcadores; Gitleaks sin hallazgos en árbol e historial |
| `.env` y `secrets/` ignorados | ✅ | `.env`, `secrets/`, llaves y certificados privados excluidos; plantilla sin valores utilizables |
| Compose Secrets/Vault | ✅ | Quince secretos montados por archivo; bases, broker, servicios, Jenkins, Sonar y Grafana validados |
| Script de generación local | ✅ | Scripts PowerShell y POSIX generan 15 secretos criptográficos; prueba aislada correcta |
| Rotación documentada | ✅ | `SEGURIDAD-SECRETOS.md` cubre creación, rotación y revocación específica |
| RBAC y propiedad | 🟡 | Implementados parcialmente; falta auditoría completa |
| IdP OIDC/JWKS | ⭐ | Bonificación no implementada |
| Escaneo de secretos/SAST/dependencias/imágenes | 🟡 | Gitleaks reproducible pasa árbol e historial; falta integrarlo a CI y añadir SAST/dependencias/imágenes |

## Escalabilidad y resiliencia

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Circuit breaker con fallback | 🟡 | Resilience4j en Empleados→Departamentos; el Gateway no tiene circuit breaker hacia los backends |
| Timeouts y reintentos | 🟡 | Completos en Empleados→Departamentos; el Gateway solo tiene un timeout de 35 s, sin reintentos |
| Publicación de eventos post-commit | 🟡 | Sin auditar en Empleados, Auth y Vacaciones |
| Servicios stateless | 🟡 | Auditar scheduler, consumers y estado temporal por instancia |
| Load balancer | ❌ | No implementado |
| Réplicas horizontales | ❌ | No existe prueba con múltiples instancias |
| Health-aware routing | ❌ | No existe balanceador que retire instancias enfermas |
| Continuidad al caer una instancia | ❌ | No demostrada |

## Documentación y entrega

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| README final mínimo | 🟡 | README amplio por retos; falta consolidación final y secretos seguros |
| Arquitectura final | 🟡 | Diagramas parciales; falta documento final |
| `docs/eventos.md` | ❌ | Falta catálogo implementado en formato final |
| Guías de terceros | 🟡 | Hay guías parciales; falta conjunto final |
| Scripts/seed idempotente | ❌ | Falta estructura final requerida |
| README por servicio con plantilla común | 🟡 | Existen siete README de distinta profundidad; falta la plantilla C6 |
| Equivalencia con la estructura de referencia del PDF | ❌ | Documentar `services/`↔`microservicios/` y `observability/`↔`observabilidad/` |
| Paquete `.zip`/`.tar.gz` limpio | ❌ | Se genera únicamente en Fase 14 |

## Condiciones detectadas del entorno de baseline

- Rama local: `proyecto-final`.
- Punto estable: etiqueta local `reto8-completo` sobre `b28c220`.
- Node, Java, Go y Python no están instalados en el host actual.
- Las pruebas del baseline se ejecutarán en contenedores reproducibles.
- La rama `proyecto-final` y la etiqueta `reto8-completo` ya están publicadas en el remoto (verificado el 2026-11-11).
