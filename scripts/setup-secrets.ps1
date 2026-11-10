[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$template = Join-Path $root '.env.example'
$envPath = Join-Path $root '.env'
$secretDir = Join-Path $root 'secrets'
$e2eEnvPath = Join-Path $root 'e2e-tests/.env'

if ((Test-Path $envPath) -and -not $Force) {
    throw '.env ya existe. Use -Force únicamente si desea rotar todos los secretos.'
}

function New-RandomSecret([int]$Bytes = 32) {
    $buffer = [byte[]]::new($Bytes)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return 'Aa1!' + [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

$secretNames = @(
    'EMPLEADOS_DB_PASSWORD', 'DEPARTAMENTOS_DB_PASSWORD',
    'DEPARTAMENTOS_DB_ROOT_PASSWORD', 'RABBITMQ_PASSWORD',
    'NOTIFICACIONES_DB_PASSWORD', 'PERFILES_DB_PASSWORD',
    'VACACIONES_DB_PASSWORD', 'AUTH_DB_PASSWORD', 'JWT_SECRET',
    'AUTH_ADMIN_PASSWORD', 'JENKINS_ADMIN_PASSWORD', 'SONAR_ADMIN_PASSWORD',
    'SONAR_JDBC_PASSWORD', 'E2E_USER_PASSWORD', 'GRAFANA_ADMIN_PASSWORD'
)

$values = @{}
foreach ($name in $secretNames) { $values[$name] = New-RandomSecret }

$content = Get-Content -LiteralPath $template -Raw
foreach ($name in $secretNames) {
    $content = $content -replace "(?m)^$([regex]::Escape($name))=__GENERATE__$", "$name=$($values[$name])"
}

New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
[System.IO.File]::WriteAllText($envPath, $content, [System.Text.UTF8Encoding]::new($false))
foreach ($name in $secretNames) {
    $fileName = $name.ToLowerInvariant() + '.txt'
    [System.IO.File]::WriteAllText((Join-Path $secretDir $fileName), $values[$name], [System.Text.UTF8Encoding]::new($false))
}

$e2eContent = @"
BASE_URL=http://localhost:8080
ADMIN_USUARIO=admin
ADMIN_PASSWORD=$($values['AUTH_ADMIN_PASSWORD'])
USER_PASSWORD=$($values['E2E_USER_PASSWORD'])
POLL_INTENTOS=15
POLL_INTERVALO_MS=2000
"@
[System.IO.File]::WriteAllText($e2eEnvPath, $e2eContent, [System.Text.UTF8Encoding]::new($false))

Write-Host "Configuración local creada en .env y secrets/ ($($secretNames.Count) secretos)."
