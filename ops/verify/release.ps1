param([string]$SettingsPath, [switch]$SkipInstall, [switch]$SkipRecovery)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $SettingsPath) { $SettingsPath = Join-Path $root 'backend/.env.local.ps1' }
if (-not (Test-Path -LiteralPath $SettingsPath -PathType Leaf)) { throw 'Provide private native development/test database settings with -SettingsPath.' }
$output = Join-Path $root ('backend/target/release-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$lock = $null
$changed = @('WORKSPACE_RUN_DB_TESTS','WORKSPACE_RUN_YJS_ENGINE_TESTS','WORKSPACE_RUN_BROWSER_TESTS','WORKSPACE_RUN_CLUSTER_TESTS','WORKSPACE_RUN_LOAD_TESTS','WORKSPACE_RUN_OVERLOAD_TESTS','WORKSPACE_RUN_SEARCH_BROWSER_TESTS','WORKSPACE_BROWSER_TEST_GREP','WORKSPACE_E2E_STRUCTURAL_EDITS')
$previous = @{}
foreach ($key in $changed) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$report = [ordered]@{ startedAt = [DateTimeOffset]::UtcNow.ToString('o'); status = 'running'; target = 'single Windows LAN host, native PostgreSQL, private Node engine'; phases = @() }
function Invoke-Phase([string]$Name, [string]$Directory, [scriptblock]$Action, [switch]$Native) {
    $log = Join-Path $output ($Name + '.log')
    Write-Output "Verifying $Name..."
    Push-Location -LiteralPath $Directory
    try {
        & $Action *> $log
        if ($Native -and $LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE. See $log" }
        $report.phases += $Name
    } finally { Pop-Location }
}
try {
    # Serialize release experiments: concurrent recovery and indexing can distort the workload.
    $lock = [IO.File]::Open((Join-Path $root 'backend/target/release-verification.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    . $SettingsPath
    foreach ($key in @('WORKSPACE_DB_URL','WORKSPACE_DB_USER','WORKSPACE_DB_PASSWORD','WORKSPACE_TEST_DB_URL','WORKSPACE_TEST_DB_USER','WORKSPACE_TEST_DB_PASSWORD')) {
        if (-not [Environment]::GetEnvironmentVariable($key, 'Process')) { throw "Missing $key; settings values are never printed." }
    }
    # TestDatabaseGuard also checks actual connected database names before test writes.
    if (-not $SkipInstall) {
        Invoke-Phase 'engine-install' (Join-Path $root 'experiments/yjs-private-engine') { npm ci --ignore-scripts } -Native
        Invoke-Phase 'frontend-install' (Join-Path $root 'frontend') { npm ci --ignore-scripts } -Native
    }
    Invoke-Phase 'engine-tests' (Join-Path $root 'experiments/yjs-private-engine') { npm test } -Native
    Invoke-Phase 'frontend-build' (Join-Path $root 'frontend') { npm run build } -Native
    foreach ($key in @('WORKSPACE_RUN_DB_TESTS','WORKSPACE_RUN_YJS_ENGINE_TESTS','WORKSPACE_RUN_BROWSER_TESTS','WORKSPACE_RUN_LOAD_TESTS','WORKSPACE_RUN_OVERLOAD_TESTS','WORKSPACE_RUN_SEARCH_BROWSER_TESTS')) { [Environment]::SetEnvironmentVariable($key, 'true', 'Process') }
    $env:WORKSPACE_RUN_CLUSTER_TESTS = 'false'
    $env:WORKSPACE_E2E_STRUCTURAL_EDITS = '500'
    Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
    Invoke-Phase 'combined-regression' (Join-Path $root 'backend') { mvn -B '-Dtest=!MultiInstanceIntegrationTest' test } -Native
    $lines = Get-Content -LiteralPath (Join-Path $output 'combined-regression.log')
    $summary = @($lines | Select-String '^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$')[-1]
    if (-not $summary -or [int]$summary.Matches[0].Groups[2].Value -ne 0 -or [int]$summary.Matches[0].Groups[3].Value -ne 0 -or [int]$summary.Matches[0].Groups[4].Value -ne 0) { throw 'Release regression must finish with zero failures, errors and skips.' }
    $report.javaTests = [int]$summary.Matches[0].Groups[1].Value
    $measurement = @($lines | Select-String '^STRUCTURAL_EDIT_MEASUREMENT (\{.*\})$')[-1]
    if (-not $measurement) { throw 'Missing structural workload measurement.' }
    $report.structuralWorkload = $measurement.Matches[0].Groups[1].Value | ConvertFrom-Json
    if ($report.structuralWorkload.accepted -ne 500 -or $report.structuralWorkload.pending -ne 0 -or $report.structuralWorkload.records -ne 0 -or $null -ne $report.structuralWorkload.rejection) { throw 'Structural workload did not satisfy acceptance.' }
    $report.browserSummaries = @($lines | Where-Object { $_ -match '^(BROWSER_SCENARIOS|OFFLINE_SCENARIOS) ' })
    $insideBrowser = $false
    $durations = [Collections.Generic.List[double]]::new()
    $rejections = @{}
    foreach ($line in $lines) {
        if ($line.Contains('Started BrowserWorkspaceIntegrationTest')) { $insideBrowser = $true }
        if ($line -match 'Tests run:.* -- in .*BrowserWorkspaceIntegrationTest$') { $insideBrowser = $false }
        if (-not $insideBrowser -or -not $line.StartsWith('{')) { continue }
        $event = $line | ConvertFrom-Json -ErrorAction SilentlyContinue
        if ($event.stage -ne 'accept') { continue }
        if ($event.outcome -eq 'success') { $durations.Add([double]$event.durationMs) }
        else { $reason = [string]$event.reason; $rejections[$reason] = 1 + [int]$rejections[$reason] }
    }
    $ordered = @($durations | Sort-Object)
    if ($ordered.Count -eq 0) { throw 'Missing browser acceptance latency evidence.' }
    $report.browserAcceptance = @{ successful = $ordered.Count; p50Ms = $ordered[[math]::Floor(($ordered.Count - 1) * 0.50)]; p95Ms = $ordered[[math]::Floor(($ordered.Count - 1) * 0.95)]; maxMs = $ordered[-1]; rejectedAttemptsByReason = $rejections; scope = 'server acceptance, complete browser group; includes permission/failure scenarios, not network end-to-end latency' }
    Invoke-Phase 'packaged-build' $root { & (Join-Path $root 'ops/deploy/build-local.ps1') -SkipInstall }
    $report.jarSha256 = (Get-FileHash -LiteralPath (Join-Path $root 'backend/target/collaborative-workspace-0.0.1-SNAPSHOT.jar') -Algorithm SHA256).Hash
    if (-not $SkipRecovery) {
        Invoke-Phase 'packaged-recovery' $root { & (Join-Path $root 'ops/recovery/verify-local.ps1') -SkipBuild }
        if (-not (Select-String -LiteralPath (Join-Path $output 'packaged-recovery.log') -SimpleMatch 'Recovery drill passed')) { throw 'Missing recovery completion evidence.' }
    }
    $report.recoveryIncluded = -not $SkipRecovery
    $report.status = if ($SkipRecovery) { 'functional-passed-recovery-excluded' } else { 'passed' }
} catch {
    $report.status = 'failed'
    $report.failure = $_.Exception.Message
    throw
} finally {
    $report.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'results.json') -Encoding utf8
    foreach ($key in $changed) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    if ($lock) { $lock.Dispose() }
    Write-Output "Release verification evidence: $output"
}
