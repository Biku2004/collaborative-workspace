# Shared by local launch and recovery drill. Child arguments use ArgumentList, never shell interpolation.
function Start-WorkspaceProcess([string]$Executable, [string[]]$Arguments, [string]$Directory, [string]$Log) {
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    $output = [IO.FileStream]::new($Log, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $errorOutput = [IO.FileStream]::new($Log + '.err', [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $copy = $process.StandardOutput.BaseStream.CopyToAsync($output)
    $errorCopy = $process.StandardError.BaseStream.CopyToAsync($errorOutput)
    return @{ Process = $process; Output = $output; ErrorOutput = $errorOutput; Copy = $copy; ErrorCopy = $errorCopy }
}
function Stop-WorkspaceProcess($Child) {
    if ($null -eq $Child) { return }
    if (-not $Child.Process.HasExited) { $Child.Process.Kill($true); $Child.Process.WaitForExit(10000) | Out-Null }
    $null = $Child.Copy.GetAwaiter().GetResult(); $null = $Child.ErrorCopy.GetAwaiter().GetResult()
    $Child.Output.Dispose(); $Child.ErrorOutput.Dispose(); $Child.Process.Dispose()
}
function Wait-WorkspaceHttp([string]$Url, [int]$Status, [int]$Seconds = 45) {
    $client = [Net.Http.HttpClient]::new(); $client.Timeout = [TimeSpan]::FromSeconds(2)
    try {
        $until = [DateTime]::UtcNow.AddSeconds($Seconds)
        while ([DateTime]::UtcNow -lt $until) {
            try { $response = $client.GetAsync($Url).GetAwaiter().GetResult(); $code = [int]$response.StatusCode; $response.Dispose(); if ($code -eq $Status) { return } } catch { }
            Start-Sleep -Milliseconds 200
        }
        throw "Local service did not become ready at $Url"
    } finally { $client.Dispose() }
}
