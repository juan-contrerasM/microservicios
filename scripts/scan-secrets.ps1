[CmdletBinding()]
param([switch]$History)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mode = if ($History) { 'historial completo' } else { 'árbol actual' }
$arguments = @('detect', '--source=/repo', '--config=/repo/.gitleaks.toml', '--redact', '--verbose', '--exit-code=1')
if (-not $History) { $arguments += '--no-git' }

Write-Host "Analizando secretos en $mode..."
docker run --rm -v "${root}:/repo" ghcr.io/gitleaks/gitleaks:v8.24.3 @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Gitleaks detectó posibles secretos en el $mode. Revise la salida redactada."
}
Write-Host "Sin secretos detectados en el $mode."
