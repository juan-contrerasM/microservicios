# Dispara Jenkins tras commit en main (Windows / Git sin Bash).
$ErrorActionPreference = 'Stop'
$Root = git rev-parse --show-toplevel 2>$null
if (-not $Root) { exit 0 }
Set-Location $Root

$branch = git rev-parse --abbrev-ref HEAD 2>$null
if ($branch -ne 'main') { exit 0 }

$headRev = git rev-parse HEAD 2>$null
$gitDir = git rev-parse --git-dir 2>$null
$markerFile = Join-Path $gitDir 'jenkins-last-ci-rev'
if ($env:JENKINS_CI_FORCE -ne '1' -and (Test-Path $markerFile)) {
    $lastRev = (Get-Content $markerFile -Raw).Trim()
    if ($lastRev -eq $headRev) { exit 0 }
}

$JenkinsUrl = 'http://localhost:9090'
$JenkinsUser = 'admin'
$JenkinsPassword = 'admin'
$envFile = Join-Path $Root '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.TrimEnd("`r")
        if ($line -match '^(JENKINS_URL|JENKINS_ADMIN_ID|JENKINS_ADMIN_PASSWORD)=(.*)$') {
            Set-Variable -Name $Matches[1] -Value $Matches[2].Trim('"') -Scope Script
        }
    }
}

$pair = "${JenkinsUser}:${JenkinsPassword}"
$bytes = [Text.Encoding]::ASCII.GetBytes($pair)
$b64 = [Convert]::ToBase64String($bytes)
$headers = @{ Authorization = "Basic $b64" }

$jar = Join-Path $env:TEMP "jenkins-ci-cookies-$PID.txt"
Remove-Item $jar -ErrorAction SilentlyContinue

try {
    Invoke-WebRequest -Uri "$JenkinsUrl/login" -Headers $headers -SessionVariable session -UseBasicParsing | Out-Null
} catch {
    Write-Host "CI: Jenkins no responde en $JenkinsUrl; omitiendo disparo." -ForegroundColor Yellow
    exit 0
}

function Invoke-JenkinsBuild {
    param([string]$Job)
    $crumb = Invoke-RestMethod -Uri "$JenkinsUrl/crumbIssuer/api/json" -WebSession $session -Headers $headers
    $buildHeaders = @{ Authorization = "Basic $b64" }
    $buildHeaders[$crumb.crumbRequestField] = $crumb.crumb
    try {
        Invoke-WebRequest -Uri "$JenkinsUrl/job/$Job/build" -Method Post -WebSession $session -Headers $buildHeaders -UseBasicParsing | Out-Null
        Write-Host "CI: encolado $Job."
    } catch {
        Write-Host "CI: no se pudo encolar $Job." -ForegroundColor Red
    }
}

Invoke-JenkinsBuild 'empleados-service'
Invoke-JenkinsBuild 'vacaciones-service'

if ($headRev -and $markerFile) {
    Set-Content -Path $markerFile -Value $headRev -NoNewline
}
