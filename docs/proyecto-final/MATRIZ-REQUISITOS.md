# Matriz de requisitos — Proyecto Final

Auditoría inicial sobre la rama `proyecto-final`. Esta matriz se completa con resultados de
ejecución durante la Fase 1. Los estados iniciales no sustituyen pruebas ni evidencias.

Leyenda: ✅ reutilizable/verificado · 🟡 parcial o pendiente de revalidación · ❌ faltante · ⭐ opcional

## Funcionalidad

| Requisito | Estado inicial | Evidencia actual / brecha |
|---|---|---|
| Siete servicios y Gateway | ✅ | Empleados, Departamentos, Auth, Perfiles, Vacaciones, Notificaciones y Gateway existen |
| CRUD de Empleados | 🟡 | Crear, listar, consultar, actualizar y retirar existen; falta revalidación final |
| Estados `ACTIVO`, `EN_VACACIONES`, `RETIRADO` | 🟡 | Modelo y flujos existen; falta revalidar transición completa |
| Retiro lógico con `fechaRetiro` | 🟡 | Implementado; falta prueba de auditoría final |
| Auditoría de retirados por rango | 🟡 | Contrato existente; falta prueba integrada |
| Unicidad email/número de empleado | 🟡 | Implementada; falta revalidación con base real |
| CRUD de Departamentos | ❌ | Solo POST, GET lista y GET por ID; faltan PUT y DELETE |
| Validación Empleados → Departamentos | 🟡 | Cliente HTTP, timeout, retry y circuit breaker existentes |
| Auth sin `/register` público | ✅ | No se encontró endpoint público de registro |
| Cuenta inactiva y token de activación | 🟡 | Flujo existente; falta revalidación E2E desde correo |
| Login y JWT | 🟡 | JWT HS256 implementado; falta hardening final |
| Cambio y recuperación de contraseña | 🟡 | Endpoints y eventos existentes |
| BCrypt y ausencia de contraseña temporal | 🟡 | BCrypt implementado; falta auditoría de eventos/correos |
| Suspensión temporal y baja permanente | 🟡 | Auth consume eventos de vacaciones/retiro; falta regresión integrada |
| Perfil propio y 403 sobre perfil ajeno | 🟡 | Autorización existente; falta completar modelo y revalidar |
| Perfil con todos los campos normativos | ❌ | Faltan apellido, código postal, cargo, área, número, foto y redes |
| Archivado por retiro | 🟡 | Consumidor existente; falta prueba integrada |
| CRUD de Vacaciones | 🟡 | Crear/listar/consultar/cancelar; falta actualización explícita |
| Validaciones de vacaciones | 🟡 | Fechas, pasado y solapamiento implementados; falta baseline |
| Scheduler inicio/fin | 🟡 | Implementado; falta validar escalado seguro |
| Correos de eventos obligatorios | 🟡 | Mailhog y eventos implementados; falta revalidar catálogo completo |
| Plantillas configurables | ❌ | Mensajes definidos como constantes en código |
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
| Imágenes optimizadas/no-root | 🟡 | Requiere auditoría de cada Dockerfile |
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
| Circuit breaker con fallback | ✅ | Resilience4j en Empleados→Departamentos |
| Timeouts y reintentos | ✅ | Implementados en la comunicación síncrona crítica |
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
| Paquete `.zip`/`.tar.gz` limpio | ❌ | Se genera únicamente en Fase 14 |

## Condiciones detectadas del entorno de baseline

- Rama local: `proyecto-final`.
- Punto estable: etiqueta local `reto8-completo` sobre `b28c220`.
- Node, Java, Go y Python no están instalados en el host actual.
- Las pruebas del baseline se ejecutarán en contenedores reproducibles.
- GitHub rechazó el push con 403 porque `SebasGalleS` no tiene permiso sobre el remoto actual.
- La publicación remota de rama/etiqueta queda pendiente de permiso del propietario.
