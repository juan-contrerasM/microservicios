# Operación y verificación — Reto 8

## Arranque

```powershell
Copy-Item .env.example .env
docker compose config -q
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File observability/verify.ps1
```

Interfaces: Grafana `http://localhost:3000`, Prometheus `http://localhost:9091` y Zipkin
`http://localhost:9411`. Prometheus debe mostrar exactamente siete servicios de negocio `UP`.

## Consultas LogQL de la Fase 4

```logql
{service="api-gateway"} | json
{service=~".+-service|api-gateway"} | json | traceId != ""
{service=~".+-service|api-gateway"} | json | traceId = "TRACE_ID"
```

El datasource Loki incluye un campo derivado que convierte `traceId` en un enlace a la traza de
Zipkin. Loki conserva siete días. Los logs no deben contener JWT, contraseñas ni webhooks.

Para verificar además una operación ya generada:

```powershell
powershell -ExecutionPolicy Bypass -File observability/verify.ps1 -TraceId TRACE_ID
```

## Dashboard, recording rules y alertas (Fase 5)

Todo se aprovisiona al arrancar; no hay pasos manuales en la UI:

| Archivo | Contenido |
|---|---|
| `prometheus/rules/reto8-recording.yml` | Series comunes por `job` para Java, Go, Node y Python (tráfico, 4xx/5xx, p95, media, CPU, memoria) |
| `grafana/dashboards/reto8-observabilidad.json` | Dashboard `reto8-observabilidad`: Resumen, Tráfico y Logs |
| `grafana/provisioning/alerting/reglas.yml` | «Servicio caído» (1 m), «Tasa de errores 5xx superior al 10 %» (2 m), «Latencia p95 superior a 1 s» (1 m) |
| `grafana/provisioning/alerting/notificaciones.yml` | Contact point `email-reto8` (SMTP a Mailhog) y política por `alertname`/`job` |

Las alertas llegan por email a la bandeja de Mailhog: `http://localhost:8025`. El destinatario se
cambia con `GRAFANA_ALERT_EMAIL`; no hay secretos involucrados.

Docker sin Docker Desktop: si `docker` solo existe dentro de WSL, `verify.ps1` y `caos.ps1` lo invocan
como `wsl docker` automáticamente. Si WSL no resuelve DNS al descargar imágenes, se puede asignar un
DNS temporal (no persistente): `wsl -u root resolvectl dns eth0 <gateway-de-WSL>`.

`verify.ps1` comprueba recording rules, dashboard, las tres reglas y el contact point. Para que los
paneles de tráfico tengan datos, generar tráfico antes de mirarlos o capturarlos:

```powershell
powershell -ExecutionPolicy Bypass -File observability/trafico.ps1 -Segundos 180
```

## Caos controlado (Fase 6)

```powershell
powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario caida
powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario latencia -LatenciaMs 1500
```

- `caida`: tráfico de fondo, `docker compose stop departamentos-service` (no borra contenedor ni
  volumen), espera `DOWN` en Prometheus y la alerta en `Alerting`, y en el bloque `finally` vuelve a
  iniciarlo y espera la resolución.
- `latencia`: recrea `departamentos-service` con `CHAOS_LATENCY_MS`, que retrasa solo
  `/departamentos` (nunca `/health` ni `/metrics`), imprime el ranking de latencia y lo recrea con el
  valor del `.env` (`0`). El retardo es fijo y determinista; con `0` el middleware no existe.

Después de cualquier escenario, `verify.ps1` debe volver a pasar.

## Requisito del host

Docker Desktop usa el backend WSL2. Si `docker info` indica que el backend no está activo, abrir
PowerShell como administrador, ejecutar `wsl --install --no-distribution`, reiniciar Windows y volver
a ejecutar los comandos de arranque. Esta instalación no se puede completar desde una sesión sin
elevación porque habilita características de Windows y requiere reinicio.
