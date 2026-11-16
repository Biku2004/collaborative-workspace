param([Parameter(Mandatory)][string]$Archive, [Parameter(Mandatory)][ValidatePattern('^collaborative_workspace_restore_[a-f0-9]{16}$')][string]$Database,
    [ValidatePattern('^[a-z][a-z0-9_]{0,62}$')][string]$OwnerRole)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'postgres.ps1')
Assert-WorkspacePgConnection
$source = (Resolve-Path -LiteralPath $Archive).Path
$manifest = Get-Content -LiteralPath ($source + '.json') -Raw | ConvertFrom-Json
if ($manifest.version -ne 1 -or $manifest.sha256 -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -or $manifest.bytes -ne (Get-Item -LiteralPath $source).Length) { throw 'Backup manifest/checksum verification failed' }
# Never overwrite/drop a database. createdb itself atomically rejects an existing name.
$create = @('--no-password', '--template=template0')
$restore = @('--no-password', '--no-owner', '--no-acl', '--single-transaction', '--exit-on-error', '--dbname', $Database)
if ($OwnerRole) { $create += @('--owner', $OwnerRole); $restore += @('--role', $OwnerRole) }
Invoke-WorkspacePg 'createdb' ($create + @($Database))
Invoke-WorkspacePg 'pg_restore' ($restore + @($source))
Invoke-WorkspacePg 'psql' @('-X', '--no-password', '--set=ON_ERROR_STOP=1', '--dbname', $Database, '-c', 'ANALYZE;') | Out-Null
Write-Output 'Restore finished in a new isolated database. Verify the application before any cutover.'
