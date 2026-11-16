param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'postgres.ps1')
. (Join-Path $PSScriptRoot '../deploy/processes.ps1')
function Get-DrillPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Invoke-DrillBrowser([string]$Phase) {
    $env:WORKSPACE_RECOVERY_PHASE = $Phase
    Push-Location (Join-Path $root 'frontend')
    try { & node node_modules/@playwright/test/cli.js test recovery-drill.spec.ts --reporter=line; if ($LASTEXITCODE) { throw "Recovery browser phase $Phase failed" } } finally { Pop-Location }
}
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot '../deploy/build-local.ps1'); if (-not $?) { throw 'Local build failed' } }
$run = Join-Path $root ('backend/target/recovery-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$data = Join-Path $run 'pgdata'; $passwordFile = Join-Path $run 'password.tmp'
$adminPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$appPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[IO.File]::WriteAllText($passwordFile, $adminPassword)
$pgPort = Get-DrillPort; $appPort = Get-DrillPort; $enginePort = Get-DrillPort
$env:PGHOST = '127.0.0.1'; $env:PGPORT = "$pgPort"; $env:PGUSER = 'drill_admin'; $env:PGPASSWORD = $adminPassword; $env:PGCONNECT_TIMEOUT = '5'
$source = 'collaborative_workspace_drill_source'; $restored = 'collaborative_workspace_restore_' + [Guid]::NewGuid().ToString('N').Substring(0,16)
$jar = Join-Path $root 'backend/target/collaborative-workspace-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the bundled JAR first' }
$started = $false; $engine = $null; $app = $null
try {
    Invoke-WorkspacePg 'initdb' @('-D', $data, '--username=drill_admin', '--pwfile', $passwordFile, '--auth=scram-sha-256', '--encoding=UTF8', '--no-locale') | Out-Null
    Remove-Item -LiteralPath $passwordFile
    Invoke-WorkspacePg 'pg_ctl' @('-D', $data, '-l', (Join-Path $run 'postgres.log'), '-o', "-h 127.0.0.1 -p $pgPort -c max_connections=30 -c shared_buffers=32MB", '-w', '-t', '20', 'start') | Out-Null
    $started = $true
    # A random Base64 password contains no SQL quote characters. Send SQL on stdin, never the command line.
    "CREATE ROLE workspace_app LOGIN PASSWORD '$appPassword';" | & (Join-Path (Get-WorkspacePostgresBin) 'psql.exe') -X -w -v ON_ERROR_STOP=1 -d postgres | Out-Null
    if ($LASTEXITCODE) { throw 'Could not create isolated application role' }
    Invoke-WorkspacePg 'createdb' @('-w', '--owner=workspace_app', '--template=template0', $source)
    $env:WORKSPACE_DB_USER = 'workspace_app'; $env:WORKSPACE_DB_PASSWORD = $appPassword
    $env:WORKSPACE_DB_URL = "jdbc:postgresql://127.0.0.1:$pgPort/$source"
    $env:WORKSPACE_DEV_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:SPRING_PROFILES_ACTIVE = 'dev'; $env:SERVER_ADDRESS = '127.0.0.1'; $env:SERVER_PORT = "$appPort"
    $env:SERVER_SERVLET_SESSION_COOKIE_NAME = 'RECOVERY_SESSION'; $env:WORKSPACE_LOCAL_UI_ENABLED = 'true'
    $env:WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN = "http://127.0.0.1:$appPort"
    $env:WORKSPACE_YJS_ENGINE_URL = "http://127.0.0.1:$enginePort"; $env:YJS_ENGINE_PORT = "$enginePort"
    $env:WORKSPACE_CLUSTER_ENABLED = 'false'; $env:WORKSPACE_RICH_ENABLED = 'false'
    $env:WORKSPACE_SEARCH_POLL_MS = '1000'; $env:WORKSPACE_DELIVERY_POLL_MS = '100'
    $env:WORKSPACE_SEARCH_INDEXING_ENABLED = 'true'
    $env:WORKSPACE_E2E_ORIGIN = "http://127.0.0.1:$appPort"; $env:WORKSPACE_RECOVERY_MANIFEST = Join-Path $run 'fixture.json'
    $chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    if (Test-Path -LiteralPath $chrome) { $env:PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH = $chrome }
    $engine = Start-WorkspaceProcess 'node' @('dist/src/server.js') (Join-Path $root 'experiments/yjs-private-engine') (Join-Path $run 'engine.log')
    Wait-WorkspaceHttp "http://127.0.0.1:$enginePort/" 404
    $app = Start-WorkspaceProcess 'java' @('-jar', $jar) $root (Join-Path $run 'source-app.log')
    Wait-WorkspaceHttp "http://127.0.0.1:$appPort/api/status" 200
    Invoke-DrillBrowser 'seed'
    $archive = Join-Path $run 'workspace.dump'; $timer = [Diagnostics.Stopwatch]::StartNew()
    $env:PGUSER = 'workspace_app'; $env:PGPASSWORD = $appPassword
    & (Join-Path $PSScriptRoot 'backup.ps1') -Database $source -Archive $archive
    $backupSeconds = $timer.Elapsed.TotalSeconds
    Invoke-DrillBrowser 'advance'
    Stop-WorkspaceProcess $app; $app = $null
    $env:PGUSER = 'drill_admin'; $env:PGPASSWORD = $adminPassword
    $timer.Restart()
    & (Join-Path $PSScriptRoot 'restore-isolated.ps1') -Archive $archive -Database $restored -OwnerRole workspace_app
    $restoreSeconds = $timer.Elapsed.TotalSeconds
    $env:WORKSPACE_DB_URL = "jdbc:postgresql://127.0.0.1:$pgPort/$restored"
    $app = Start-WorkspaceProcess 'java' @('-jar', $jar) $root (Join-Path $run 'restored-app.log')
    Wait-WorkspaceHttp "http://127.0.0.1:$appPort/api/status" 200
    Invoke-DrillBrowser 'verify'
    $verifiedSeconds = $timer.Elapsed.TotalSeconds
    $before = Invoke-WorkspacePg 'psql' @('-X', '-w', '-A', '-t', '-d', $restored, '-c', 'SELECT head_sequence FROM document_streams ORDER BY document_id;')
    $refused = $false
    try { & (Join-Path $PSScriptRoot 'restore-isolated.ps1') -Archive $archive -Database $restored -OwnerRole workspace_app } catch {
        if ($_.ToString() -notmatch 'createdb failed') { throw }; $refused = $true
    }
    if (-not $refused) { throw 'Existing database was not protected' }
    $after = Invoke-WorkspacePg 'psql' @('-X', '-w', '-A', '-t', '-d', $restored, '-c', 'SELECT head_sequence FROM document_streams ORDER BY document_id;')
    if ("$before" -ne "$after") { throw 'Existing database changed after refused restore' }
    Stop-WorkspaceProcess $app; $app = $null; Stop-WorkspaceProcess $engine; $engine = $null
    $settings = Join-Path $run 'launch-settings.ps1'
    $settingsText = "`$env:WORKSPACE_DB_URL = '$env:WORKSPACE_DB_URL'`n`$env:WORKSPACE_DB_USER = 'workspace_app'`n`$env:WORKSPACE_DB_PASSWORD = '$appPassword'`n`$env:WORKSPACE_DEV_PASSWORD = '$env:WORKSPACE_DEV_PASSWORD'`n"
    [IO.File]::WriteAllText($settings, $settingsText)
    try { & (Join-Path $PSScriptRoot '../deploy/start-local.ps1') -SettingsPath $settings -Port $appPort -EnginePort $enginePort -CheckOnly }
    finally { Remove-Item -LiteralPath $settings -ErrorAction SilentlyContinue }
    $report = @{ recordedUtc = [DateTime]::UtcNow.ToString('o'); backupSeconds = $backupSeconds; restoreSeconds = $restoreSeconds; restoreToVerifiedApplicationSeconds = $verifiedSeconds; archiveBytes = (Get-Item -LiteralPath $archive).Length; jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash; isolatedNativePostgres = $true; sourceDevelopmentDatabaseModified = $false; browserPhases = @('seed','advance','verify'); existingDatabaseRefusedWithoutChanges = $true; packagedLauncherCheckPassed = $true }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'results.json') -Encoding utf8
    Write-Output "Recovery drill passed; private archive, logs and measurements are in $run"
} finally {
    Stop-WorkspaceProcess $app; Stop-WorkspaceProcess $engine
    if ($started -or (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid'))) {
        $expectedRoot = [IO.Path]::GetFullPath((Join-Path $root 'backend/target')) + [IO.Path]::DirectorySeparatorChar
        if (-not ([IO.Path]::GetFullPath($data)).StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to stop a PostgreSQL instance outside the drill directory' }
        Invoke-WorkspacePg 'pg_ctl' @('-D', $data, '-m', 'fast', '-w', '-t', '20', 'stop') | Out-Null
    }
    if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    if (Test-Path -LiteralPath $env:WORKSPACE_RECOVERY_MANIFEST) { Remove-Item -LiteralPath $env:WORKSPACE_RECOVERY_MANIFEST }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
