<#
.SYNOPSIS
    Genera tráfico HTTP realista a través del API Gateway para alimentar el dashboard del Reto 8.

.DESCRIPTION
    Inicia sesión como administrador y recorre en bucle los listados de negocio de los cinco
    backends expuestos por el Gateway. Cada ciclo agrega también un 404 controlado y un 401 sin
    token, para que los paneles de 4xx tengan datos. No crea ni borra datos.
    El token nunca se imprime.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File observability/trafico.ps1 -Segundos 180
#>
param(
    [int]$Segundos = 120,
    [string]$EnvFile = '.env.example',
    [string]$GatewayUrl = 'http://localhost:8080',
    [int]$PausaMs = 200
)

$ErrorActionPreference = 'Stop'

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

function Invoke-Gateway([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null) {
    $params = @{ Uri = "$GatewayUrl$Path"; Method = $Method; Headers = $Headers; UseBasicParsing = $true; TimeoutSec = 40 }
    if ($Body) {
        $params.Body = ($Body | ConvertTo-Json -Compress)
        $params.ContentType = 'application/json'
    }
    try {
        $response = Invoke-WebRequest @params
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0  # sin respuesta: timeout o conexión rechazada
    }
}

$usuario = Get-EnvValue 'AUTH_ADMIN_USUARIO' 'admin'
$contrasena = Get-EnvValue 'AUTH_ADMIN_PASSWORD' 'Admin1234!'

function Get-Token {
    $body = @{ usuario = $usuario; contrasena = $contrasena } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod "$GatewayUrl/auth/login" -Method Post -Body $body -ContentType 'application/json'
    return $login.token
}

$token = Get-Token
$auth = @{ Authorization = "Bearer $token" }
$rutas = @('/empleados', '/departamentos', '/perfiles', '/notificaciones', '/vacaciones')
$conteo = @{}
$fin = (Get-Date).AddSeconds($Segundos)
$ciclo = 0

Write-Host "Generando tráfico contra $GatewayUrl durante $Segundos s..."
while ((Get-Date) -lt $fin) {
    $ciclo++
    foreach ($ruta in $rutas) {
        $status = Invoke-Gateway 'GET' $ruta $auth
        if ($status -eq 401) {
            # El token expiró: renovar una vez y seguir.
            $token = Get-Token
            $auth = @{ Authorization = "Bearer $token" }
            $status = Invoke-Gateway 'GET' $ruta $auth
        }
        $conteo["$ruta $status"] = 1 + [int]$conteo["$ruta $status"]
    }
    # 4xx controlados: recurso inexistente (id fijo: el Gateway usa la ruta como label) y sin token.
    $status = Invoke-Gateway 'GET' '/departamentos/no-existe' $auth
    $conteo["/departamentos/{id} $status"] = 1 + [int]$conteo["/departamentos/{id} $status"]
    $status = Invoke-Gateway 'GET' '/empleados'
    $conteo["/empleados (sin token) $status"] = 1 + [int]$conteo["/empleados (sin token) $status"]
    # Cada 10 ciclos se renueva la sesión: así auth-service también recibe tráfico de negocio.
    if ($ciclo % 10 -eq 0) {
        $token = Get-Token
        $auth = @{ Authorization = "Bearer $token" }
        $conteo['/auth/login 200'] = 1 + [int]$conteo['/auth/login 200']
    }
    Start-Sleep -Milliseconds $PausaMs
}

Write-Host "Ciclos: $ciclo. Respuestas por ruta y código (0 = sin respuesta):"
$conteo.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0,-40} {1,6}" -f $_.Name, $_.Value) }
