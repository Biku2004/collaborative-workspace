function Get-WorkspacePostgresBin {
    if ($env:WORKSPACE_PG_BIN) { $bin = $env:WORKSPACE_PG_BIN } else { $bin = 'C:/Program Files/PostgreSQL/18/bin' }
    if (-not (Test-Path -LiteralPath (Join-Path $bin 'pg_dump.exe'))) { throw 'Set WORKSPACE_PG_BIN to your PostgreSQL 18 bin directory' }
    return (Resolve-Path -LiteralPath $bin).Path
}
function Invoke-WorkspacePg([string]$Tool, [string[]]$Arguments) {
    if ($Tool -eq 'pg_ctl') {
        # PowerShell's native-process tracking can wait for the server that pg_ctl deliberately detaches.
        $info = [Diagnostics.ProcessStartInfo]::new((Join-Path (Get-WorkspacePostgresBin) 'pg_ctl.exe'))
        $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::Start($info)
        try {
            if (-not $process.WaitForExit(30000)) { throw 'PostgreSQL control operation timed out' }
            if ($process.ExitCode -ne 0) { throw "PostgreSQL pg_ctl failed (exit $($process.ExitCode))" }
        } finally { $process.Dispose() }
        return
    }
    & (Join-Path (Get-WorkspacePostgresBin) ($Tool + '.exe')) @Arguments
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL $Tool failed (exit $LASTEXITCODE)" }
}
function Assert-WorkspacePgConnection {
    if ($env:PGHOST -ne '127.0.0.1' -or -not $env:PGPORT -or -not $env:PGUSER -or -not $env:PGPASSWORD) { throw 'Set PGHOST=127.0.0.1, PGPORT, PGUSER and PGPASSWORD in this shell; credentials must not be command arguments' }
    $env:PGCONNECT_TIMEOUT = '5'
}
