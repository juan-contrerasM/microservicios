# Operación y verificación — Reto 8

## Arranque

```powershell
Copy-Item .env.example .env
docker compose config -q
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File observability/verify.ps1
```

Interfaces: Grafana `http://localhost:3000`, Prometheus `http://localhost:9090` y Zipkin
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
| `grafana/provisioning/alerting/reglas.yml` | «Servicio caído» (1 m), «Tasa de errores 5xx superior al 10 %» (2 m), «Latencia p95 superior a 2 s» (2 m), «Mensajes enviados a una DLQ» (inmediata), «Memoria del proceso superior al 85 %» (5 m) |
| `grafana/provisioning/alerting/notificaciones.yml` | Contact point `equipo-microservicios`: Discord (webhook como secreto) + correo a Mailhog; política por `alertname`/`job` |

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

## Proyecto final — Fase 11: negocio, eventos y canal externo

### Métricas de negocio (E20)

Cada servicio publica sus contadores en su ruta de métricas (`/actuator/prometheus` en Java,
`/metrics` en el resto). Etiquetas de baja cardinalidad: nunca ids, emails ni trace ids.

| Servicio | Métricas | Dónde se cuentan |
|---|---|---|
| empleados | `empleados_creados_total`, `empleados_actualizados_total`, `empleados_retirados_total` | Tras el commit, junto a la publicación del evento |
| departamentos | `departamentos_creados_total`, `departamentos_consultas_total` | Respuestas 201 / 200 del handler |
| auth | `logins_total{resultado}`, `cuentas_activadas_total`, `cuentas_desactivadas_total{tipo}`, `recuperaciones_solicitadas_total` | Login y eventos salientes confirmados |
| perfiles | `perfiles_creados_total`, `perfiles_actualizados_total`, `perfiles_archivados_total` | Al confirmar la transacción |
| vacaciones | `vacaciones_programadas_total`, `vacaciones_iniciadas_total`, `vacaciones_finalizadas_total` | Tras el commit (API y scheduler) |
| notificaciones | `notificaciones_enviadas_total{tipo}`, `notificaciones_fallidas_total{tipo}` | Resultado real del envío SMTP |
| gateway | `gateway_upstream_errors_total{servicio,causa}`, `gateway_circuit_breaker_state{servicio}` | Proxy y circuito (Fase 6) |
| consumidores (auth, perfiles, vacaciones, notificaciones) | `eventos_procesados_total{tipo}`, `eventos_duplicados_total{tipo}`, `eventos_dlq_total{tipo}` | Manejador del mensaje: ack, duplicado por id o reject a DLQ |

`prometheus/rules/proyecto-final.yml` agrega estas series por `job` y define
`job:process_memory:ratio`: heap usado/máximo en Java; en Node, Python y Go (sin techo propio)
el RSS frente a un presupuesto de 512 MiB por proceso.

El dashboard `pf-negocio-eventos` («Proyecto final — Negocio, eventos y resiliencia») muestra
estado de los servicios, totales de negocio, logins y correos, eventos procesados/duplicados/DLQ
y los dos circuit breakers (Gateway → backends y Empleados → Departamentos).

### Logs (contrato C5)

Los siete servicios escriben una línea JSON con `timestamp`, `level`, `service`, `message`,
`traceId` y `spanId`. En Java se renombran los campos del formato logstash con
`logging.structured.json.*`; en Go, `slog` renombra `time`/`msg`. Promtail publica `service` y
`level` como labels (nunca `traceId`):

```logql
{service="auth-service", level="ERROR"} | json
{service=~".+"} | json | traceId = "TRACE_ID"
```

### Canal externo de alertas: Discord

El contact point `equipo-microservicios` envía a Discord y, como respaldo local, a Mailhog. La URL
del webhook es un secreto (`secrets/discord_webhook_url.txt`, montado en Grafana y leído con
`$__file{...}`); nunca se escribe en Git ni en `.env`:

```bash
DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/<id>/<token>' bash scripts/setup-secrets.sh --force
# o, sin rotar los demás secretos:
printf '%s' 'https://discord.com/api/webhooks/<id>/<token>' > secrets/discord_webhook_url.txt
docker compose up -d --force-recreate grafana
```

Sin webhook real queda un marcador que Grafana acepta pero que no entrega; las alertas siguen
llegando a `http://localhost:8025`.

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
