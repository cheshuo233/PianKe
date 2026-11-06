# Runs every automatic check; the first failing step ends the script with a
# non-zero exit code, which is what makes it usable as a pre-push hook.
#
#   scripts\check.ps1

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$python = "$repo\tools\.venv\Scripts\python.exe"

function Invoke-Step {
    param([string]$Name, [scriptblock]$Command)
    Write-Host "==> $Name"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        Write-Host "check failed: $Name (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

Invoke-Step 'pytest tests/engine tests/assets' {
    & $python -m pytest tests/engine tests/assets
}

# tests/bake only matters when the bake inputs changed relative to upstream.
# Without an upstream branch the diff fails, and then it always runs.
$changed = & git diff --name-only '@{u}...HEAD' 2>$null
$runBake = ($LASTEXITCODE -ne 0) -or [bool]($changed | Where-Object { $_ -eq 'films/films.toml' -or $_ -like 'tools/*' })
if ($runBake) {
    Invoke-Step 'pytest tests/bake' {
        & $python -m pytest tests/bake
    }
} else {
    Write-Host '==> pytest tests/bake (skipped: films.toml and tools/ unchanged)'
}

# JAVA_HOME for this process only: keep the one already set, else Android Studio's JBR.
if (-not $env:JAVA_HOME) {
    $studio = (Get-ItemProperty 'HKLM:\SOFTWARE\Android Studio' -ErrorAction SilentlyContinue).Path
    $jbr = if ($studio) { Join-Path $studio 'jbr' }
    if ($jbr -and (Test-Path "$jbr\bin\java.exe")) {
        $env:JAVA_HOME = $jbr
    } else {
        Write-Host 'check failed: no JDK found. Set JAVA_HOME or install Android Studio.' -ForegroundColor Red
        exit 1
    }
}

Invoke-Step 'gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest' {
    & "$repo\gradlew.bat" :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
}

Write-Host 'check passed' -ForegroundColor Green
exit 0
