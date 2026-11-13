$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$settings = Join-Path $projectRoot 'backend/.cluster.local.ps1'
if (-not (Test-Path -LiteralPath $settings)) {
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $key = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $content = "# Local cluster credentials. Ignored by Git; never print or commit this file.`n" +
        "`$env:WORKSPACE_CLUSTER_REDIS_PASSWORD = '$password'`n`$env:WORKSPACE_CLUSTER_KEY = '$key'`n"
    [IO.File]::WriteAllText($settings, $content)
}
. $settings
& docker compose -f (Join-Path $PSScriptRoot 'compose.yaml') up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Local Redis did not become healthy' }
Write-Output 'Local Redis ready on 127.0.0.1:6379. Dot-source backend/.cluster.local.ps1 in each backend shell.'
