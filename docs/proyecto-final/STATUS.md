# Estado de implementación — Proyecto Final

Fuente de verdad del avance del proyecto final. El detalle técnico, paquetes de trabajo y criterios de
cierre están en [`PLAN-PROYECTO-FINAL.md`](PLAN-PROYECTO-FINAL.md); el cumplimiento por servicio, en
[`MATRIZ-REQUISITOS.md`](MATRIZ-REQUISITOS.md).

Leyenda: ✅ terminado · 🧪 implementado y probado sin Docker, falta validación integrada · 🔄 en progreso ·
⬜ pendiente · ⛔ bloqueado

## Asignación de frentes

| Frente | Alcance | Persona asignada |
|---|---|---|
| A — Dominio Java/Go | empleados, perfiles, departamentos | _por asignar_ |
| B — Dominio Node/Python | api-gateway, vacaciones, auth, notificaciones | _por asignar_ |
| C — Plataforma | Compose, redes, Docker, Jenkins, SonarQube, Registry, balanceador | _por asignar_ |
| D — Observabilidad, seguridad y docs | observability/, seguridad, docs/, scripts/ | _por asignar_ |

Con dos personas: Persona 1 = A + C, Persona 2 = B + D.

## Fases

| Fase | Ola | Descripción | Frentes | Estado | Evidencia/nota |
|---|---|---|---|---|---|
| 0 | — | Punto de restauración, rama y documentos normativos | Todos | ✅ | Etiqueta `reto8-completo`, rama `proyecto-final`, PDF, plan y estado |
| 1 | — | Matriz normativa y baseline ejecutable | Todos | ✅ | Siete suites verificadas, BDD 18/18, brechas en `EVIDENCIAS.md` |
| 2 | — | Cero secretos y configuración segura | D | ✅ | Docker Secrets y `*_FILE`; Gitleaks sin hallazgos; BDD 18/18 |
| 3A | — | Funcionalidad faltante Java/Go | A | ✅ | CRUD Departamentos y modelo normativo de Perfiles |
| 3B | — | Funcionalidad faltante Node/Python | B | ✅ | Vacaciones modificables, plantillas externas, ciclo de cuenta |
| 4 | 1 | Eventos, idempotencia, retry y DLQ (E11, E12) | A + B | ✅ | RabbitMQ real: dedup atómica, poison en cuatro DLQ, recuperación transitoria y agotamiento de tres reintentos sin re-fan-out. |
| 5 | 1 | Contrato HTTP: OpenAPI, errores, health, migraciones (E05, E07–E09) | A + B | ✅ | Siete health 200; Empleados 503 en 2,13 s con PostgreSQL caído; siete contratos Swagger 200 y BDD 18/18. |
| 6 | 2 | Resiliencia síncrona: CB, timeout y retry (E10) | A + B | ✅ | Gateway con circuito por backend, timeout por servicio, retry GET 1–2–4 s y métricas; Empleados→Departamentos revalidado. |
| 7 | 1 | Contenerización multi-stage y no root (E01, E02) | C + A + B | ✅ | Construcción limpia correcta; siete aplicaciones healthy como UID/GID `10001:10001`. |
| 8 | 2 | Pruebas, cobertura ≥70 % por servicio y BDD (E13–E17) | A + B + D | ✅ | Siete servicios ≥70 % con umbral automático; integración real en los siete (`scripts/test-integration.sh`); un mutante eliminado por servicio; BDD 23/23 tres veces seguidas. |
| 9 | 3 | Jenkinsfile y SonarQube en los siete servicios (E18, E19) | C | ✅ | Siete pipelines generados desde la plantilla, todos SUCCESS con 14 etapas; siete proyectos Sonar con gate ≥70 %; los cuatro fallos simulados se detienen en su etapa. |
| 10 | 3 | Registry, artefactos, despliegue y rollback | C | ✅ | Tag `<commit>-<build>` + digest en `:5001`; etapa Deploy con smoke test y rollback automático (`scripts/deploy.sh`); commit → imagen → contenedor trazable. |
| 11 | 2 | Observabilidad uniforme: métricas, logs y trazas (E20–E22) | D + A + B | ✅ | Métricas de negocio y de eventos en los siete, logs C5 uniformes, traza de onboarding por los siete servicios, cinco alertas y dashboard nuevo. Alerta real de DLQ entregada en Discord y por correo. |
| 12 | 3 | Escalabilidad, balanceo y alta disponibilidad (E23) | C + A + B | ✅ | Traefik con health checks activos; 2 réplicas, reparto 30/30 y 0 errores al caer una; lock del scheduler; los siete escalan. |
| 13 | 3 | Seguridad integral (E24) | D | ✅ | Cabeceras, CORS, 413, rate limiting, JWT ≥32 bytes, logs sin secretos probado; 0 CVE CRITICAL/HIGH; Gitleaks árbol + historial limpio. |
| 14 | 2 | Orquestación, redes, puertos y estructura (E03, E04, E06) | C | ✅ | Seis redes con aislamiento verificado; solo Gateway y UIs publican puerto (8080, 8090, 9090, 5001…); Registry en el `up` principal; imágenes fijadas; semilla idempotente. |
| 15 | 4 | Documentación final y README por servicio (E25) | D + A + B | ✅ | README raíz con las 7 secciones del PDF, `docs/arquitectura.md`, ocho guías y ficha C6 en los siete README. |
| 16 | 4 | Validación final, caos y paquete | Todos | ✅ | Paquete limpio validado desde otra ruta (26 contenedores healthy, BDD 23/23); caos con verificación automática; matriz sin celdas rojas ni amarillas. |

## Punto actual

**Proyecto completo: las cuatro olas (Fases 0–16) están cerradas y validadas con Docker.**

- Siete pipelines de Jenkins en verde con 14 etapas, despliegue desde el Registry y rollback.
- Balanceador Traefik, siete servicios escalables y demostración de continuidad ante la caída de una réplica.
- Seguridad del borde, 0 CVE CRITICAL/HIGH y secretos limpios en árbol e historial.
- Documentación final (README, arquitectura, guías y fichas C6) y paquete reproducible (`scripts/empaquetar.sh`).

Pendiente opcional: bonificaciones del plan (IdP OIDC/JWKS, `/empleados/{id}/completo`, `/health/services`).

## Decisiones

| Decisión | Elección | Estado |
|---|---|---|
| Rama de trabajo | `proyecto-final` + ramas cortas `proyecto-final/fN-<tema>` | Vigente |
| Punto de restauración | Etiqueta `reto8-completo` sobre `b28c220` | Publicada en remoto |
| Gestión de secretos | Docker Compose Secrets | Implementada (Fase 2) |
| Registry | `registry:2.8.3` local persistente en `:5001` | Implementado; publicación y despliegue desde Jenkins (Fase 10) |
| Load balancer | Traefik 3.6 (health checks activos, reintento, ruta de respaldo 502) | Implementado (Fase 12) |
| Auth obligatoria | JWT HS256 propio, endurecido | Vigente |
| IdP OIDC/JWKS | Bonificación posterior | Opcional |
| Canal de alertas | Discord con webhook como secreto (`$__file`) + correo a Mailhog como respaldo local | Implementado y evidenciado (Fase 11) |
| Redes | Seis redes del PDF, cada contenedor solo en las que usa | Implementada (Fase 14) |
| Puertos de host | Gateway 8080; Jenkins 8090, Prometheus 9090, Registry 5001, Grafana 3000, Zipkin 9411, SonarQube 9000, RabbitMQ UI 15672, Mailhog 8025 | Implementada (Fase 14) |
| Estructura de entrega | Conservar `services/` y `observability/`; equivalencia en `docs/estructura-entrega.md` | Vigente (Fase 14) |
| Pruebas por servicio | Unitarias con umbral 70 % + `scripts/test-integration.sh` + `scripts/test-mutation.sh` + `scripts/test-bdd.sh` | Vigente (Fase 8) |
| Health global | `/health/services` separado | Opcional |

## Reglas de actualización

1. No marcar fases por porcentaje subjetivo.
2. Una fase cerrada enlaza pruebas y evidencias, y su parte del estándar está en verde en los siete
   servicios de la matriz.
3. Los componentes heredados se marcan completos solo después de revalidarlos.
4. Un bloqueo indica causa, responsable y siguiente acción.
5. La actualización del estado viaja en el mismo PR que el avance demostrado.
