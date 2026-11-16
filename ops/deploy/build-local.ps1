param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
try {
    Set-Location (Join-Path $root 'experiments/yjs-private-engine')
    if (-not $SkipInstall) { & npm ci; if ($LASTEXITCODE) { throw 'Engine dependency installation failed' } }
    & npm run build; if ($LASTEXITCODE) { throw 'Engine build failed' }
    Set-Location (Join-Path $root 'frontend')
    if (-not $SkipInstall) { & npm ci; if ($LASTEXITCODE) { throw 'Frontend dependency installation failed' } }
    & npm run build; if ($LASTEXITCODE) { throw 'Frontend build failed' }
    Set-Location (Join-Path $root 'backend')
    # Do not run Maven clean: target may also contain an isolated recovery cluster or private backup.
    foreach ($name in @('classes', 'test-classes')) {
        $output = [IO.Path]::GetFullPath((Join-Path $root "backend/target/$name"))
        $expected = [IO.Path]::GetFullPath((Join-Path $root 'backend/target')) + [IO.Path]::DirectorySeparatorChar
        if (-not $output.StartsWith($expected, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build output escaped target directory' }
        if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
    }
    & mvn -B package -Pbundled-ui -DskipTests
    if ($LASTEXITCODE) { throw 'Bundled application build failed' }
    Write-Output 'Built backend/target/collaborative-workspace-0.0.1-SNAPSHOT.jar with the frontend. The private engine runs separately.'
} finally { Set-Location $root }
