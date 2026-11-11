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
| 4 | 1 | Eventos, idempotencia, retry y DLQ (E11, E12) | A + B | 🧪 | Código y pruebas listos: reintento solo a la cola de origen (corrige el re-fan-out de `ad01d03`), validación de envelope, dedup atómica, correo tras commit, publicación post-commit, `docs/eventos.md`, fixtures `fase4-*`. Falta validar con RabbitMQ real |
| 5 | 1 | Contrato HTTP: OpenAPI, errores, health, migraciones (E05, E07–E09) | A + B | 🧪 | Health C2 con timeout (corrige el hallazgo de Empleados), errores C3, siete `openapi.yaml` con prueba de drift, `/swagger` agregado en el Gateway, duplicados → 409. Falta validar con dependencias reales caídas y BDD |
| 6 | 2 | Resiliencia síncrona: CB, timeout y retry (E10) | A + B | ⬜ | Gateway sin circuit breaker ni reintentos |
| 7 | 1 | Contenerización multi-stage y no root (E01, E02) | C + A + B | 🧪 | Siete Dockerfiles multi-stage, UID 10001, bases fijadas, `.dockerignore`; `setup-secrets` deja `secrets/*.txt` en 0644 dentro de `secrets/` 0700 (`--permisos`). Falta `docker compose build` |
| 8 | 2 | Pruebas, cobertura ≥70 % por servicio y BDD (E13–E17) | A + B + D | ⬜ | Gateway, Perfiles y Departamentos con pocas pruebas |
| 9 | 2–3 | Jenkinsfile y SonarQube en los siete servicios (E18, E19) | C | ⬜ | Hoy solo Empleados y Vacaciones |
| 10 | 3 | Registry, artefactos, despliegue y rollback | C | ⬜ | Registry en `:5001` |
| 11 | 2 | Observabilidad uniforme: métricas, logs y trazas (E20–E22) | D + A + B | ⬜ | Métricas de negocio por servicio, alertas Discord |
| 12 | 3 | Escalabilidad, balanceo y alta disponibilidad (E23) | C + A + B | ⬜ | Balanceador, réplicas, lock del scheduler |
| 13 | 3 | Seguridad integral (E24) | D | ⬜ | RBAC, rate limiting, cabeceras, escaneos |
| 14 | 2 | Orquestación, redes, puertos y estructura (E03, E04, E06) | C | ⬜ | Seis redes; quitar puertos 8081/8083/8084/8085; Jenkins 8090 |
| 15 | 4 | Documentación final y README por servicio (E25) | D + A + B | ⬜ | README raíz, arquitectura, eventos, guías |
| 16 | 4 | Validación final, caos y paquete | Todos | ⬜ | Paquete reproducible y matriz sin celdas rojas |

## Punto actual

Ola 1 implementada e integrada en `proyecto-final` (Fases 4, 5 y 7 en 🧪). Todas las suites pasan sin
Docker: Empleados y Perfiles (Maven), Departamentos (Go), Gateway 70/70, Vacaciones 109/109 (88 %),
Auth 76 (82 %), Notificaciones 42 (81 %), arnés E2E 16/16.

**Bloqueante para pasar a ✅:** alguien con Docker debe ejecutar la validación integrada descrita en
`EVIDENCIAS.md` (sección «Ola 1 — validación pendiente con Docker»). Mientras tanto puede empezar la
**Ola 2** (Fases 6, 8, 11, 14 y plantilla de la 9).

## Decisiones

| Decisión | Elección | Estado |
|---|---|---|
| Rama de trabajo | `proyecto-final` + ramas cortas `proyecto-final/fN-<tema>` | Vigente |
| Punto de restauración | Etiqueta `reto8-completo` sobre `b28c220` | Publicada en remoto |
| Gestión de secretos | Docker Compose Secrets | Implementada (Fase 2) |
| Registry | `registry:2` local persistente en `:5001` | Pendiente (Fase 10) |
| Load balancer | Traefik o Nginx | Pendiente de selección (Fase 12) |
| Auth obligatoria | JWT HS256 propio, endurecido | Vigente |
| IdP OIDC/JWKS | Bonificación posterior | Opcional |
| Canal de alertas | Discord con webhook como secreto | Pendiente de evidencia (Fase 11) |
| Redes | Seis redes del PDF | Pendiente (Fase 14) |
| Puertos de host | Los del PDF (Jenkins 8090, Prometheus 9090, Registry 5001) | Pendiente (Fase 14) |
| Estructura de entrega | Conservar `services/` y `observability/` y documentar la equivalencia | Propuesta |
| Health global | `/health/services` separado | Opcional |

## Reglas de actualización

1. No marcar fases por porcentaje subjetivo.
2. Una fase cerrada enlaza pruebas y evidencias, y su parte del estándar está en verde en los siete
   servicios de la matriz.
3. Los componentes heredados se marcan completos solo después de revalidarlos.
4. Un bloqueo indica causa, responsable y siguiente acción.
5. La actualización del estado viaja en el mismo PR que el avance demostrado.
