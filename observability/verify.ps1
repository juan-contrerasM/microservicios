param(
    [string]$EnvFile = '.env.example',
    [string]$TraceId = ''
)

$ErrorActionPreference = 'Stop'

# Docker puede existir solo dentro de WSL (sin Docker Desktop). En ese caso se delega en wsl.exe;
# WSL traduce el directorio actual, así que las rutas relativas de Compose siguen funcionando.
if (-not (Get-Command docker -CommandType Application -ErrorAction SilentlyContinue)) {
    function docker { wsl.exe -e docker @args }
}

$expectedServices = @(
    'api-gateway',
    'empleados-service',
    'departamentos-service',
    'perfiles-service',
    'notificaciones-service',
    'vacaciones-service',
    'auth-service'
)

docker compose --env-file $EnvFile config -q
docker compose --env-file $EnvFile ps --status running

$targets = Invoke-RestMethod 'http://localhost:9091/api/v1/targets'
$businessTargets = @($targets.data.activeTargets | Where-Object { $_.labels.job -in $expectedServices })
$missing = @($expectedServices | Where-Object { $_ -notin $businessTargets.labels.job })
$down = @($businessTargets | Where-Object { $_.health -ne 'up' })

if ($missing.Count -gt 0) {
    throw "Prometheus no enumera estos servicios: $($missing -join ', ')"
}
if ($down.Count -gt 0) {
    throw "Targets DOWN: $(($down.labels.job | Sort-Object -Unique) -join ', ')"
}

$grafana = Invoke-RestMethod 'http://localhost:3000/api/health'
$zipkin = Invoke-WebRequest 'http://localhost:9411/health' -UseBasicParsing
if ($grafana.database -ne 'ok' -or $zipkin.StatusCode -ne 200) {
    throw 'Grafana o Zipkin no están saludables.'
}

$credentials = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin:GrafanaAdmin123!'))
$headers = @{ Authorization = "Basic $credentials" }
$datasources = @(Invoke-RestMethod 'http://localhost:3000/api/datasources' -Headers $headers)
$datasourceNames = @($datasources.name)
if ('Prometheus' -notin $datasourceNames -or 'Loki' -notin $datasourceNames) {
    throw 'Grafana no tiene aprovisionados los datasources Prometheus y Loki.'
}

# Fase 5: recording rules, dashboard, reglas de alerta y contact point aprovisionados desde Git.
$rules = Invoke-RestMethod 'http://localhost:9091/api/v1/rules?type=record'
$recording = @($rules.data.groups | ForEach-Object { $_.rules })
$unhealthy = @($recording | Where-Object { $_.health -ne 'ok' -and $_.health -ne 'unknown' })
if ($recording.Count -lt 8 -or $unhealthy.Count -gt 0) {
    throw "Prometheus cargó $($recording.Count) recording rules; con error: $($unhealthy.name -join ', ')"
}

$null = Invoke-RestMethod 'http://localhost:3000/api/dashboards/uid/reto8-observabilidad' -Headers $headers
# PowerShell 5 no enumera el arreglo JSON dentro de @(); se enumera con ForEach-Object.
$alertRules = @(Invoke-RestMethod 'http://localhost:3000/api/v1/provisioning/alert-rules' -Headers $headers | ForEach-Object { $_ })
$missingRules = @('reto8-servicio-caido', 'reto8-errores-5xx', 'reto8-latencia-p95' | Where-Object { $_ -notin $alertRules.uid })
if ($missingRules.Count -gt 0) {
    throw "Faltan reglas de alerta aprovisionadas: $($missingRules -join ', ')"
}
# PowerShell 5 no enumera el arreglo JSON dentro de @(); se enumera con ForEach-Object.
$contactPoints = @(Invoke-RestMethod 'http://localhost:3000/api/v1/provisioning/contact-points' -Headers $headers | ForEach-Object { $_ })
$email = @($contactPoints | Where-Object { $_.name -eq 'email-reto8' -and $_.type -eq 'email' })
if ($email.Count -eq 0) {
    throw 'Grafana no tiene el contact point email-reto8.'
}

# Con tráfico reciente (observability/trafico.ps1) las series normalizadas deben cubrir los siete.
$query = [uri]::EscapeDataString('job:http_requests:rate1m')
$traffic = Invoke-RestMethod "http://localhost:9091/api/v1/query?query=$query"
$withTraffic = @($traffic.data.result.metric.job | Sort-Object -Unique)
$sinTrafico = @($expectedServices | Where-Object { $_ -notin $withTraffic })
if ($sinTrafico.Count -gt 0) {
    Write-Warning "Sin tráfico reciente en: $($sinTrafico -join ', '). Ejecutar observability/trafico.ps1 antes de capturar el dashboard."
}
Write-Host "Grafana: dashboard reto8-observabilidad, $($alertRules.Count) reglas de alerta y contact point email aprovisionados; $($recording.Count) recording rules OK."

if ($TraceId) {
    # Windows PowerShell 5 conserva el arreglo JSON como un único objeto si se
    # envuelve directamente en @(); dejar que Invoke-RestMethod lo enumere.
    $trace = Invoke-RestMethod "http://localhost:9411/api/v2/trace/$TraceId"
    $spanIds = @($trace.id)
    $roots = @($trace | Where-Object { -not $_.parentId })
    $orphans = @($trace | Where-Object { $_.parentId -and $_.parentId -notin $spanIds })
    if ($trace.Count -eq 0 -or $roots.Count -ne 1 -or $orphans.Count -ne 0) {
        throw "La traza $TraceId no forma un árbol único y completo."
    }

    $end = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) * 1000000
    $start = $end - ([long]3600 * 1000000000)
    $logql = "{service=~`".+-service|api-gateway`"} | json | traceId = `"$TraceId`""
    $encoded = [uri]::EscapeDataString($logql)
    $lokiUrl = "http://localhost:3000/api/datasources/proxy/uid/loki/loki/api/v1/query_range?query=$encoded&start=$start&end=$end&limit=1000"
    $loki = Invoke-RestMethod $lokiUrl -Headers $headers
    $lines = @($loki.data.result | ForEach-Object { $_.values } | ForEach-Object { $_[1] })
    if ($lines.Count -eq 0) {
        throw "Loki no contiene logs correlacionados con $TraceId."
    }
    $sensitive = @($lines | Where-Object { $_ -match 'token=eyJ|Bearer |Admin1234|cambia-esta-clave' })
    if ($sensitive.Count -gt 0) {
        throw "Loki contiene información sensible para la traza $TraceId."
    }
    Write-Host "Traza ${TraceId}: $($trace.Count) spans, $($lines.Count) logs correlacionados, sin secretos."
}

Write-Host 'Reto 8: stack saludable, datasources listos y siete targets Prometheus UP.'
