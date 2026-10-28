# Respaldo cada 30 s en Windows (Jenkins pollSCM mínimo ~1 min).
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
Write-Host 'CI: vigilando main cada 30 s (Ctrl+C para parar).'
while ($true) {
    Start-Sleep -Seconds 30
    $env:JENKINS_CI_FORCE = '0'
    & (Join-Path $Root 'jenkins/trigger-ci-on-commit.ps1')
}
