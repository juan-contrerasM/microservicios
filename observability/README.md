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

## Requisito del host

Docker Desktop usa el backend WSL2. Si `docker info` indica que el backend no está activo, abrir
PowerShell como administrador, ejecutar `wsl --install --no-distribution`, reiniciar Windows y volver
a ejecutar los comandos de arranque. Esta instalación no se puede completar desde una sesión sin
elevación porque habilita características de Windows y requiere reinicio.
