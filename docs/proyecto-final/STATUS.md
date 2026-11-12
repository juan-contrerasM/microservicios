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
| 9 | 2–3 | Jenkinsfile y SonarQube en los siete servicios (E18, E19) | C | 🟡 | Plantilla 9.TPL validada por el linter de Jenkins (`jenkins/plantillas/`); los siete Jenkinsfile y proyectos Sonar quedan para la Ola 3. |
| 10 | 3 | Registry, artefactos, despliegue y rollback | C | ⬜ | Registry en `:5001` |
| 11 | 2 | Observabilidad uniforme: métricas, logs y trazas (E20–E22) | D + A + B | 🟡 | Métricas de negocio y de eventos en los siete, logs C5 uniformes, traza de onboarding por los siete servicios, cinco alertas y dashboard nuevo. Alerta real entregada por correo; **falta solo la evidencia en Discord**, que requiere el webhook del equipo. |
| 12 | 3 | Escalabilidad, balanceo y alta disponibilidad (E23) | C + A + B | ⬜ | Balanceador, réplicas, lock del scheduler |
| 13 | 3 | Seguridad integral (E24) | D | ⬜ | RBAC, rate limiting, cabeceras, escaneos |
| 14 | 2 | Orquestación, redes, puertos y estructura (E03, E04, E06) | C | ✅ | Seis redes con aislamiento verificado; solo Gateway y UIs publican puerto (8080, 8090, 9090, 5001…); Registry en el `up` principal; imágenes fijadas; semilla idempotente. |
| 15 | 4 | Documentación final y README por servicio (E25) | D + A + B | ⬜ | README raíz, arquitectura, eventos, guías |
| 16 | 4 | Validación final, caos y paquete | Todos | ⬜ | Paquete reproducible y matriz sin celdas rojas |

## Punto actual

**Ola 1 cerrada y validada con Docker**: Fases 4, 5 y 7, stack completo, RabbitMQ real y BDD 18/18.
**Ola 2 cerrada salvo una evidencia externa:** Fases 6, 8 y 14 cerradas; plantilla de la Fase 9
validada; Fase 11 implementada y verificada en los siete servicios.

**Bloqueo único de la Ola 2** — causa: la evidencia «alerta real recibida en Discord» necesita la
URL del webhook del canal del equipo, que no puede generarse. Responsable: Frente D. Acción: crear
el webhook en Discord, escribirlo en `secrets/discord_webhook_url.txt`, recrear `grafana` y
provocar una alerta (p. ej. publicar un mensaje inválido; ver `observability/README.md`). La entrega
por correo del mismo contact point ya está demostrada.

**Siguiente:** Ola 3 — Fase 9 completa (siete Jenkinsfile desde la plantilla), Fase 10 (Registry,
tags inmutables, despliegue y rollback), Fase 12 (balanceo y réplicas) y Fase 13 (seguridad).

## Decisiones

| Decisión | Elección | Estado |
|---|---|---|
| Rama de trabajo | `proyecto-final` + ramas cortas `proyecto-final/fN-<tema>` | Vigente |
| Punto de restauración | Etiqueta `reto8-completo` sobre `b28c220` | Publicada en remoto |
| Gestión de secretos | Docker Compose Secrets | Implementada (Fase 2) |
| Registry | `registry:2.8.3` local persistente en `:5001` | En el `up` principal (Fase 14); publicación desde Jenkins en Fase 10 |
| Load balancer | Traefik o Nginx | Pendiente de selección (Fase 12) |
| Auth obligatoria | JWT HS256 propio, endurecido | Vigente |
| IdP OIDC/JWKS | Bonificación posterior | Opcional |
| Canal de alertas | Discord con webhook como secreto (`$__file`) + correo a Mailhog como respaldo local | Implementado; falta la evidencia con el webhook real (Fase 11) |
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
