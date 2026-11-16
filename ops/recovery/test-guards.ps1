param([Parameter(Mandatory)][string]$Archive)
$ErrorActionPreference = 'Stop'
$env:PGHOST = '127.0.0.1'; $env:PGPORT = '1'; $env:PGUSER = 'guard_fixture'; $env:PGPASSWORD = 'unused-guard-password'
function Expect-Refusal([scriptblock]$Action, [string]$Reason) {
    $caught = $null
    try { & $Action } catch { $caught = $_ }
    if ($null -eq $caught -or $caught.ToString() -notmatch $Reason) { throw "Expected refusal matching '$Reason'" }
}
$source = (Resolve-Path -LiteralPath $Archive).Path
$bad = $source + '.' + [Guid]::NewGuid().ToString('N') + '.corrupt'
try {
    Copy-Item -LiteralPath $source -Destination $bad
    Copy-Item -LiteralPath ($source + '.json') -Destination ($bad + '.json')
    $bytes = [IO.File]::ReadAllBytes($bad); $bytes[100] = $bytes[100] -bxor 1; [IO.File]::WriteAllBytes($bad, $bytes)
    Expect-Refusal { & (Join-Path $PSScriptRoot 'restore-isolated.ps1') -Archive $bad -Database 'collaborative_workspace_restore_0123456789abcdef' } 'checksum verification failed'
    Expect-Refusal { & (Join-Path $PSScriptRoot 'restore-isolated.ps1') -Archive $source -Database 'collaborative_workspace_dev' } 'does not match'
    Expect-Refusal { & (Join-Path $PSScriptRoot 'backup.ps1') -Archive $source -Database 'collaborative_workspace_drill_source' } 'destination already exists'
    $env:PGHOST = 'example.com'
    Expect-Refusal { & (Join-Path $PSScriptRoot 'backup.ps1') -Archive ($bad + '.other') -Database 'collaborative_workspace_drill_source' } 'Set PGHOST=127'
    Write-Output 'Four recovery guards passed: corrupt archive, protected target, existing archive and non-loopback connection.'
} finally {
    Remove-Item -LiteralPath $bad, ($bad + '.json') -ErrorAction SilentlyContinue
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
