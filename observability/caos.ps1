<#
.SYNOPSIS
    Caos controlado y reversible del Reto 8 (Fase 6).

.DESCRIPTION
    Escenario 'caida': genera tráfico, detiene departamentos-service (sin borrarlo), espera a que
    Grafana dispare «Servicio caído» y luego lo vuelve a iniciar y espera la resolución.

    Escenario 'latencia': recrea departamentos-service con CHAOS_LATENCY_MS (retardo fijo solo en
    /departamentos), genera tráfico, imprime el ranking de latencia de Prometheus y restaura el
    servicio con el retardo en 0.

    Ambos escenarios restauran el sistema en un bloque finally, aunque se interrumpa el script.
    Ninguno imprime tokens ni contraseñas.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario caida
    powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario latencia -LatenciaMs 1500
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('caida', 'latencia')][string]$Escenario,
    [int]$LatenciaMs = 1500,
    [int]$TraficoSegundos = 240,
    [int]$EsperaAlertaSegundos = 240,
    [string]$EnvFile = '.env.example',
    [string]$Servicio = 'departamentos-service'
)

$ErrorActionPreference = 'Stop'

# Docker puede existir solo dentro de WSL (sin Docker Desktop). En ese caso se delega en wsl.exe;
# WSL traduce el directorio actual, así que las rutas relativas de Compose siguen funcionando.
if (-not (Get-Command docker -CommandType Application -ErrorAction SilentlyContinue)) {
    function docker { wsl.exe -e docker @args }
    # wsl.exe solo exporta a Linux las variables listadas en WSLENV: sin esto, el retardo de caos
    # definido abajo nunca llegaría a `docker compose` y el escenario mediría un sistema sin caos.
    $env:WSLENV = (@('DEPARTAMENTOS_CHAOS_LATENCY_MS') + @($env:WSLENV -split ':' | Where-Object { $_ })) -join ':'
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
Set-Location $root

function Get-EnvValue([string]$Name, [string]$Default) {
    if (Test-Path $EnvFile) {
        $line = Get-Content $EnvFile | Where-Object { $_ -match "^\s*$Name\s*=" } | Select-Object -Last 1
        if ($line) {
            $value = ($line -split '=', 2)[1].Trim().Trim('"')
            if ($value) { return $value }
        }
    }
    return $Default
}

$prometheus = "http://localhost:$(Get-EnvValue 'PROMETHEUS_PORT' '9091')"
$grafana = "http://localhost:$(Get-EnvValue 'GRAFANA_PORT' '3000')"
$grafanaUser = Get-EnvValue 'GRAFANA_ADMIN_USER' 'admin'
$grafanaPass = Get-EnvValue 'GRAFANA_ADMIN_PASSWORD' 'GrafanaAdmin123!'
$credentials = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${grafanaUser}:${grafanaPass}"))
$grafanaHeaders = @{ Authorization = "Basic $credentials" }

function Write-Paso([string]$Texto) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Texto) }

# Estado agregado de una regla para un servicio. La API devuelve también las series en Normal, así
# que se busca cualquier serie Alerting/Pending. $Patron es ASCII con comodines (p. ej. 'Servicio ca*do')
# porque PowerShell 5 decodifica la respuesta como Latin-1 y las tildes no coincidirían.
function Get-AlertState([string]$Patron, [string]$Job) {
    $alerts = Invoke-RestMethod "$grafana/api/prometheus/grafana/api/v1/alerts" -Headers $grafanaHeaders
    $states = @($alerts.data.alerts | Where-Object { $_.labels.alertname -like $Patron -and $_.labels.job -eq $Job } | ForEach-Object { $_.state })
    if ($states | Where-Object { $_ -like 'Alerting*' }) { return 'Alerting' }
    if ($states | Where-Object { $_ -like 'Pending*' }) { return 'Pending' }
    return 'Normal'
}

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$Descripcion) {
    $limite = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $limite) {
        if (& $Condition) { Write-Paso "OK: $Descripcion"; return $true }
        Start-Sleep -Seconds 5
    }
    Write-Paso "TIEMPO AGOTADO: $Descripcion"
    return $false
}

function Wait-Healthy([string]$Name) {
    Wait-Until {
        $id = docker compose --env-file $EnvFile ps -q $Name
        $id -and ((docker inspect -f '{{.State.Health.Status}}' $id) -eq 'healthy')
    } 120 "$Name healthy" | Out-Null
}

$mailhog = "http://localhost:$(Get-EnvValue 'MAILHOG_UI_PORT' '8025')"

# Correos de alerta en Mailhog con el estado indicado (FIRING o RESOLVED) que mencionan al servicio.
function Get-AlertMailCount([string]$Estado, [string]$Job) {
    $r = Invoke-RestMethod "$mailhog/api/v2/search?kind=containing&query=$Estado&limit=200"
    return @($r.items | Where-Object { $_.Raw.Data -match [regex]::Escape($Job) }).Count
}

function Get-TargetHealth([string]$Job) {
    $targets = Invoke-RestMethod "$prometheus/api/v1/targets"
    return @($targets.data.activeTargets | Where-Object { $_.labels.job -eq $Job })[0].health
}

function Show-LatencyRanking {
    $q = [uri]::EscapeDataString('sort_desc(job:http_request_duration_seconds:mean5m)')
    $r = Invoke-RestMethod "$prometheus/api/v1/query?query=$q"
    Write-Host 'Latencia media 5 m (job:http_request_duration_seconds:mean5m), de mayor a menor:'
    foreach ($s in $r.data.result) {
        Write-Host ("  {0,-26} {1,8:N3} s" -f $s.metric.job, [double]$s.value[1])
    }
    $q = [uri]::EscapeDataString('sort_desc(job:http_request_duration_seconds:p95_5m)')
    $r = Invoke-RestMethod "$prometheus/api/v1/query?query=$q"
    Write-Host 'Latencia p95 5 m (job:http_request_duration_seconds:p95_5m):'
    foreach ($s in $r.data.result) {
        Write-Host ("  {0,-26} {1,8:N3} s" -f $s.metric.job, [double]$s.value[1])
    }
}

$trafico = $null
try {
    Write-Paso "Generando tráfico en segundo plano durante $TraficoSegundos s"
    $trafico = Start-Job -ScriptBlock {
        param($script, $seg, $envFile, $dir)
        Set-Location $dir
        & $script -Segundos $seg -EnvFile $envFile
    } -ArgumentList (Join-Path $here 'trafico.ps1'), $TraficoSegundos, $EnvFile, $root

    if ($Escenario -eq 'caida') {
        $script:firingInicial = Get-AlertMailCount 'FIRING' $Servicio
        $script:resolvedInicial = Get-AlertMailCount 'RESOLVED' $Servicio
        Start-Sleep -Seconds 30  # línea base con el servicio sano
        Write-Paso "Deteniendo $Servicio (docker compose stop; el contenedor y su volumen se conservan)"
        docker compose --env-file $EnvFile stop $Servicio
        $inicio = Get-Date
        Wait-Until { (Get-TargetHealth $Servicio) -eq 'down' } 60 "Prometheus marca $Servicio DOWN" | Out-Null
        $disparo = Wait-Until { (Get-AlertState 'Servicio ca*do' $Servicio) -like 'Alerting*' } $EsperaAlertaSegundos "alerta «Servicio caído» en Alerting para $Servicio (el email llega a Mailhog tras group_wait)"
        if ($disparo) { Write-Paso ("La alerta disparó {0:N0} s después de la caída" -f ((Get-Date) - $inicio).TotalSeconds) }
        $firingAntes = $script:firingInicial
        $recibido = Wait-Until { (Get-AlertMailCount 'FIRING' $Servicio) -gt $firingAntes } 120 "email FIRING de $Servicio recibido en Mailhog ($mailhog)"
        if ($recibido) { Write-Paso ("Email recibido {0:N0} s después de la caída" -f ((Get-Date) - $inicio).TotalSeconds) }
        Write-Paso 'Estado de «Tasa de errores 5xx» para api-gateway (las rutas /departamentos devuelven 5xx):'
        Write-Host "  $(Get-AlertState 'Tasa de errores 5xx*' 'api-gateway')"
    } else {
        Write-Paso "Recreando $Servicio con CHAOS_LATENCY_MS=$LatenciaMs"
        $env:DEPARTAMENTOS_CHAOS_LATENCY_MS = "$LatenciaMs"
        docker compose --env-file $EnvFile up -d --no-deps $Servicio
        Wait-Healthy $Servicio
        $id = docker compose --env-file $EnvFile ps -q $Servicio
        $activo = docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $id | Where-Object { $_ -eq "CHAOS_LATENCY_MS=$LatenciaMs" }
        if (-not $activo) { throw "El contenedor no recibió CHAOS_LATENCY_MS=$LatenciaMs; se aborta para no medir un sistema sin caos." }
        Write-Paso "Retardo confirmado en el contenedor: $activo"
        Write-Paso 'Esperando a que termine el tráfico para medir con ventanas completas'
        Wait-Job $trafico | Out-Null
        Receive-Job $trafico
        Show-LatencyRanking
        Write-Paso "Estado de «Latencia p95 superior a 1 s» para ${Servicio}: $(Get-AlertState 'Latencia p95*' $Servicio)"
    }
} finally {
    Write-Paso 'Restaurando el sistema'
    Remove-Item Env:\DEPARTAMENTOS_CHAOS_LATENCY_MS -ErrorAction SilentlyContinue
    if ($Escenario -eq 'latencia') {
        # Recrear sin la variable de shell: vuelve al valor de .env (0).
        docker compose --env-file $EnvFile up -d --no-deps $Servicio
    } else {
        docker compose --env-file $EnvFile start $Servicio
    }
    Wait-Healthy $Servicio
    $id = docker compose --env-file $EnvFile ps -q $Servicio
    $latencia = (docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $id | Where-Object { $_ -like 'CHAOS_LATENCY_MS=*' })
    Write-Paso "Variable activa en el contenedor: $latencia"
    if ($trafico) {
        Wait-Job $trafico -Timeout 300 | Out-Null
        if ($Escenario -eq 'caida') { Receive-Job $trafico }
        Remove-Job $trafico -Force
    }
}

if ($Escenario -eq 'caida') {
    Wait-Until { (Get-TargetHealth $Servicio) -eq 'up' } 90 "Prometheus marca $Servicio UP" | Out-Null
    Wait-Until { (Get-AlertState 'Servicio ca*do' $Servicio) -eq 'Normal' } 180 'alerta «Servicio caído» resuelta' | Out-Null
    Wait-Until { (Get-AlertMailCount 'RESOLVED' $Servicio) -gt $script:resolvedInicial } 180 "email RESOLVED de $Servicio recibido en Mailhog" | Out-Null
}
Write-Paso 'Caos finalizado; ejecutar observability/verify.ps1 para confirmar el estado saludable.'
