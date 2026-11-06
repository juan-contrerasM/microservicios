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
| 5 | Dashboard aprovisionado, cuatro paneles, alertas y contact point externo | Persona 1 | ✅ | Dashboard `reto8-observabilidad` (11 paneles) con datos de los siete servicios; 8 recording rules; 3 reglas (caído, 5xx, p95); contact point `email-reto8`. Grafana reconstruido desde volumen vacío sin tocar la UI. |
| 6 | Tráfico, caos controlado, capturas y análisis del servicio más lento | Persona 2 + validación conjunta | ✅ | Caída: alerta por email a los 82 s, RESOLVED al restaurar; también dispararon 5xx (21 %) y p95 del Gateway (9,1 s). Latencia 1500 ms: departamentos p95 1,87 s, 99 % de la traza. 8 capturas y análisis en `EVIDENCIAS.md`; retardo restaurado a 0. |
| 7 | README final, regresión de Retos 1–7, evidencias y cierre | Ambas | ✅ | README completo. BDD 18/18 en 3 de 4 corridas (1 intermitencia previa documentada); onboarding `7f88f6d6bef73ce982c7bc875291f2dd`: 67 spans, 7 servicios, 0 huérfanos; `verify.ps1` final verde. |

## Punto actual

**Reto 8 cerrado: las fases 0 a 7 están verificadas en el stack integrado** (2026-11-03). Resultados,
tiempos de alerta, análisis del servicio más lento y capturas en [`EVIDENCIAS.md`](EVIDENCIAS.md);
comandos para repetirlo en [`observability/README.md`](../../observability/README.md).

Pendiente fuera del alcance de observabilidad: la intermitencia del paso BDD `empleadoConCredenciales`
(toma el primer aviso `SEGURIDAD` con token) pertenece a los Retos 5/6 y debe corregirse en un cambio
separado con su prueba.

## Reglas para marcar una fase como hecha

1. Compilar o levantar un contenedor no basta: debe existir la verificación descrita en el plan.
2. Los siete servicios cuentan; no se cierra instrumentación con una muestra de lenguajes.
3. Una traza asíncrona solo es válida si aparece como un único árbol en Zipkin.
4. Dashboard, datasources y alertas deben reconstruirse desde archivos versionados.
5. La alerta solo se cierra con evidencia de recepción en el canal externo.
6. Toda simulación de caos debe ser reversible y dejar el sistema saludable.
7. No registrar ni capturar JWT, contraseñas, tokens o webhooks.

## Decisiones tomadas

| Decisión | Estado |
|---|---|
| Zipkin como backend de trazas, almacenamiento en memoria acotado (100 000 spans, heap 512 MB) | Implementada y verificada |
| Prometheus publicado en `9091` para no chocar con Jenkins | Implementada |
| Canal externo: **email por SMTP a Mailhog** en lugar de Discord. El enunciado lo admite para pruebas, Mailhog ya estaba en el stack y no requiere webhooks ni secretos | Implementada y verificada (9 emails FIRING/RESOLVED) |
| 100 % de sampling solo en desarrollo/demostración | Implementada |
| Métricas heterogéneas normalizadas con recording rules en vez de renombrar métricas en el código | Implementada |
| Retardo de caos por variable `CHAOS_LATENCY_MS` (fijo, solo `/departamentos`, 0 por defecto) | Implementada y verificada |
