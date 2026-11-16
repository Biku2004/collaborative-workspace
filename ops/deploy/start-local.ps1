param([ValidateRange(1024,65535)][int]$Port = 8080, [ValidateRange(1024,65535)][int]$EnginePort = 8091,
    [string]$SettingsPath, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'processes.ps1')
$jar = Join-Path $root 'backend/target/collaborative-workspace-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run ops/deploy/build-local.ps1 first' }
if ($Port -eq $EnginePort) { throw 'Application and engine ports must differ' }
if (-not $SettingsPath) { $SettingsPath = Join-Path $root 'backend/.env.local.ps1' }
. (Resolve-Path -LiteralPath $SettingsPath).Path
$env:SPRING_PROFILES_ACTIVE = 'dev'; $env:SERVER_ADDRESS = '127.0.0.1'; $env:SERVER_PORT = "$Port"
$env:WORKSPACE_LOCAL_UI_ENABLED = 'true'; $env:WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN = "http://127.0.0.1:$Port"
$env:WORKSPACE_YJS_ENGINE_URL = "http://127.0.0.1:$EnginePort"; $env:YJS_ENGINE_PORT = "$EnginePort"
$env:WORKSPACE_CLUSTER_ENABLED = 'false'; $env:WORKSPACE_RICH_ENABLED = 'false'
$env:WORKSPACE_SEARCH_INDEXING_ENABLED = 'true'
$engine = $null; $backend = $null
try {
    $engine = Start-WorkspaceProcess 'node' @('dist/src/server.js') (Join-Path $root 'experiments/yjs-private-engine') (Join-Path $root 'backend/target/local-engine.log')
    Wait-WorkspaceHttp "http://127.0.0.1:$EnginePort/" 404
    $backend = Start-WorkspaceProcess 'java' @('-jar', $jar) $root (Join-Path $root 'backend/target/local-app.log')
    Wait-WorkspaceHttp "http://127.0.0.1:$Port/api/status" 200
    Wait-WorkspaceHttp "http://127.0.0.1:$Port/" 200
    if ($CheckOnly) { Write-Output 'Packaged application and engine readiness check passed.'; return }
    Write-Output "Workspace running at http://127.0.0.1:$Port . Press Ctrl+C to stop this launch's application and engine."
    while (-not $backend.Process.HasExited -and -not $engine.Process.HasExited) { Start-Sleep -Seconds 1 }
    throw 'An application process exited; inspect backend/target/local-*.log'
} finally { Stop-WorkspaceProcess $backend; Stop-WorkspaceProcess $engine }
