# Estado de implementación — Proyecto Final

Fuente de verdad del avance del proyecto final. El detalle técnico, responsables y criterios de
cierre están en [`PLAN-PROYECTO-FINAL.md`](PLAN-PROYECTO-FINAL.md).

Leyenda: ✅ terminado · 🔄 en progreso · ⬜ pendiente · ⛔ bloqueado

| Fase | Descripción | Responsable | Estado | Evidencia/nota |
|---|---|---|---|---|
| 0 | Punto de restauración, rama y documentos normativos | Conjunta | ✅ | Etiqueta local `reto8-completo`, rama `proyecto-final`, PDF, plan y estado creados. Push pendiente por HTTP 403 de permisos. |
| 1 | Matriz normativa y baseline ejecutable | Ambas | ✅ | Siete suites verificadas, stack construido, BDD 18/18 y brechas registradas en `EVIDENCIAS.md`. |
| 2 | Cero secretos y configuración segura | Persona 2 | ✅ | Docker Secrets y `*_FILE` validados desde volúmenes limpios; Gitleaks sin hallazgos y BDD 18/18. |
| 3A | Funcionalidad faltante Java/Go | Persona 1 | ⬜ | CRUD Departamentos y modelo completo de Perfiles. |
| 3B | Funcionalidad faltante Node/Python | Persona 2 | ⬜ | CRUD Vacaciones, plantillas y ciclo de cuenta. |
| 4 | Catálogo de eventos e idempotencia integral | Ambas | ⬜ | Contratos, DLQ, duplicados y headers W3C. |
| 5 | APIs, health, Swagger y borde único | Ambas | ⬜ | Auditoría de siete contratos y Gateway. |
| 6 | Pruebas, cobertura y aceptación funcional | Ambas | ⬜ | Umbral ≥70 % por cada servicio. |
| 7 | Pipelines Jenkins para siete servicios | Persona 1 | ⬜ | Build/test/quality/package/push/deploy. |
| 8 | Registry, artefactos y despliegue | Persona 1 | ⬜ | Registry, tags inmutables, smoke y rollback. |
| 9 | Observabilidad completa | Persona 2 | ⬜ | Dashboards, alertas Discord, caos y trazas. |
| 10 | Escalabilidad, balanceo y alta disponibilidad | Ambas | ⬜ | Réplicas, health-aware routing y caída de instancia. |
| 11 | Seguridad integral | Persona 2 | ⬜ | RBAC, propiedad, escaneos y hardening. |
| 12 | Orquestación reproducible y redes | Persona 1 | ⬜ | Un comando desde entorno limpio. |
| 13 | Documentación y datos de demostración | Ambas | ⬜ | README, arquitectura, eventos, guías y seeds. |
| 14 | Validación final, caos y paquete | Conjunta | ⬜ | Matriz completa y entrega reproducible. |

## Punto actual

Las fases 0, 1 y 2 están cerradas. El trabajo activo pasa a las fases 3A y 3B: completar las brechas
funcionales detectadas en Departamentos, Perfiles, Vacaciones y Notificaciones.

## Decisiones que deben quedar registradas antes de Fase 2

| Decisión | Propuesta inicial | Estado |
|---|---|---|
| Rama de trabajo | `proyecto-final` | Creada localmente; push pendiente por permisos del remoto |
| Punto de restauración | etiqueta `reto8-completo` | Creada sobre `b28c220`; push pendiente por permisos del remoto |
| Gestión de secretos | Docker Compose Secrets | Implementada y validada desde volúmenes limpios |
| Registry | Registry Docker local persistente | Pendiente de validación |
| Load balancer | Traefik o Nginx, según prueba técnica | Pendiente de selección |
| Auth obligatoria | Conservar JWT HS256 y endurecerlo | Propuesta |
| IdP OIDC/JWKS | Bonificación posterior | Opcional |
| Canal de alertas | Discord mediante secreto local | Propuesta |
| Health global | Ruta separada opcional | Opcional |

## Reglas de actualización

1. No marcar fases por porcentaje subjetivo.
2. Una fase cerrada debe enlazar pruebas y evidencias.
3. Los componentes heredados se marcan completos solo después de revalidarlos.
4. Un bloqueo debe indicar causa, responsable y siguiente acción.
5. La actualización del estado viaja en el mismo commit que el avance demostrado.
