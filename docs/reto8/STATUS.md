# Estado de implementación — Reto 8

Fuente de verdad del avance real del equipo. Actualizar este archivo en el mismo commit que avance o
cierre una fase. Ver el detalle y la distribución para dos personas en
[`PLAN-RETO8.md`](PLAN-RETO8.md). Enunciado normativo: [`reto8.pdf`](reto8.pdf). Material de apoyo:
[`observabilidad.pdf`](observabilidad.pdf).

El Reto 7 está cerrado; su estado histórico vive en
[`docs/reto7/STATUS.md`](../reto7/STATUS.md). Este reto agrega observabilidad sin cambiar contratos
de negocio.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Fase | Descripción | Responsable | Estado | Notas |
|---|---|---|---|---|
| 0 | Enunciados, plan, distribución para dos personas y estado | Conjunta | ✅ | Los dos PDF están en `docs/reto8/` y el plan quedó aprobado antes de iniciar la implementación. |
| 1 | Stack base: Prometheus, Grafana, Loki, Promtail, Zipkin, provisioning y diagrama previo | Persona 1 | ✅ | Stack saludable; siete targets `UP`; Grafana aprovisionó Prometheus y Loki. Prometheus usa `:9091` porque Jenkins ocupa `:9090`. |
| 2A | Métricas, salud, OTel y JSON logs en empleados, perfiles y departamentos | Persona 1 | ✅ | Maven y Go pasan; los tres targets están `UP`; logs JSON y propagación comprobados en ejecución. |
| 2B | Métricas, salud, OTel y JSON logs en Gateway, vacaciones, auth y notificaciones | Persona 2 | ✅ | Pasan 21, 64, 31 y 12 pruebas; los cuatro targets están `UP`; métricas, salud, JSON y trazas comprobados. |
| 3 | Trazabilidad HTTP + RabbitMQ; onboarding y scheduler como trazas completas | Persona 2 + apoyo Persona 1 | ✅ | Onboarding: traza `434a2652025a4a142b17fcd44b199cc4`, 66 spans, siete servicios, una raíz y cero huérfanos. Scheduler: traza `b8d8cc298c80d4264a52fcfc89ff1a9e`, 30 spans, vacaciones/auth/notificaciones. |
| 4 | Loki/Promtail, LogQL y correlación `traceId` entre logs y Zipkin | Ambas | ✅ | Consulta por `service` y `traceId` comprobada; enlace Loki→Zipkin aprovisionado, retención de 7 días y regresión contra tokens en logs. |
| 5 | Dashboard aprovisionado, cuatro paneles, dos alertas y contact point de Discord | Persona 1 | ⬜ | El webhook real no entra a Git. |
| 6 | Tráfico, caos controlado, capturas y análisis del servicio más lento | Persona 2 + validación conjunta | ⬜ | No dejar retardos ni fallos deliberados activos. |
| 7 | README final, regresión de Retos 1–7, evidencias y cierre | Ambas | ⬜ | Pendiente. |

## Punto actual

Las **Fases 0 a 4 están cerradas y verificadas en el stack integrado**. El siguiente trabajo empieza
en la Fase 5: dashboard, paneles y alertas. Los identificadores y comandos reproducibles de las
pruebas están en `EVIDENCIAS.md` y `observability/verify.ps1`.

## Reglas para marcar una fase como hecha

1. Compilar o levantar un contenedor no basta: debe existir la verificación descrita en el plan.
2. Los siete servicios cuentan; no se cierra instrumentación con una muestra de lenguajes.
3. Una traza asíncrona solo es válida si aparece como un único árbol en Zipkin.
4. Dashboard, datasources y alertas deben reconstruirse desde archivos versionados.
5. La alerta solo se cierra con evidencia de recepción en el canal externo.
6. Toda simulación de caos debe ser reversible y dejar el sistema saludable.
7. No registrar ni capturar JWT, contraseñas, tokens o webhooks.

## Decisiones pendientes de aprobación del equipo

| Decisión propuesta | Estado |
|---|---|
| Zipkin como backend de trazas | Propuesta en el plan |
| Prometheus publicado en `9091` para no chocar con Jenkins | Necesaria por la arquitectura actual |
| Discord como canal externo, configurado mediante `.env` local | Propuesta en el plan |
| 100 % de sampling solo en desarrollo/demostración | Propuesta en el plan |
