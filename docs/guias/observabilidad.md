# Observabilidad: Grafana, Prometheus, Loki y Zipkin

| Herramienta | URL | Credenciales |
|---|---|---|
| Grafana | http://localhost:3000 | `GRAFANA_ADMIN_USER` / `secrets/grafana_admin_password.txt` |
| Prometheus | http://localhost:9090 | — |
| Zipkin | http://localhost:9411 | — |
| Loki | interno; se consulta desde Grafana → Explore | — |

## Dashboards (carpeta «Microservicios», aprovisionados desde Git)

- **Reto 8 — Observabilidad de microservicios:** estado, CPU, memoria, tráfico, latencia, errores y logs.
- **Proyecto final — Negocio, eventos y resiliencia:** totales de negocio, logins, correos, eventos
  procesados, duplicados y en DLQ, y estado de los circuit breakers.

## Consultas útiles

```promql
up                                                   # una serie por réplica (descubrimiento DNS)
job:http_requests_5xx:ratio_rate1m                   # proporción de errores por servicio
job:http_request_duration_seconds:p95_5m             # latencia p95
sum by (job, tipo) (increase(eventos_dlq_total[1h])) # mensajes enviados a DLQ
gateway_circuit_breaker_state                        # 0 CLOSED, 1 HALF_OPEN, 2 OPEN
```

```logql
{service="auth-service", level="ERROR"} | json
{service=~".+"} | json | traceId = "<traceId>"
```

En Grafana, el campo `traceId` de una línea de log enlaza a la traza en Zipkin. En Zipkin, buscar por
`serviceName=api-gateway` y abrir un `POST /empleados`: la traza de onboarding recorre los siete
servicios. Si el stack de observabilidad cae, el negocio sigue funcionando (verificado con la BDD).
