param([Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9_]{0,62}$')][string]$Database, [Parameter(Mandatory)][string]$Archive)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'postgres.ps1')
Assert-WorkspacePgConnection
$destination = [IO.Path]::GetFullPath($Archive)
if (Test-Path -LiteralPath $destination) { throw 'Backup destination already exists; choose a new archive path' }
if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($destination)))) { throw 'Create the backup directory first' }
$partial = $destination + '.' + [Guid]::NewGuid().ToString('N') + '.partial'
$startedUtc = [DateTime]::UtcNow.ToString('o')
try {
    Invoke-WorkspacePg 'pg_dump' @('--no-password', '--format=custom', '--no-owner', '--no-acl', '--lock-wait-timeout=5s', '--file', $partial, '--dbname', $Database)
    Invoke-WorkspacePg 'pg_restore' @('--list', $partial) | Out-Null
    Move-Item -LiteralPath $partial -Destination $destination -ErrorAction Stop
    $manifest = @{ version = 1; database = $Database; host = $env:PGHOST; port = [int]$env:PGPORT; startedUtc = $startedUtc; completedUtc = [DateTime]::UtcNow.ToString('o'); bytes = (Get-Item -LiteralPath $destination).Length; sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath ($destination + '.json') -Encoding utf8
    Write-Output 'Backup and checksum manifest created. The archive contains document data and password hashes; keep it private.'
} finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial } }
