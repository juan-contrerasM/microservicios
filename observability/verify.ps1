param(
    [string]$EnvFile = '.env.example',
    [string]$TraceId = ''
)

$ErrorActionPreference = 'Stop'

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
