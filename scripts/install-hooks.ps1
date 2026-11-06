# Installs scripts\check.ps1 as this repository's pre-push hook, so a push that
# would break the tests or the app build is refused.
#
#   scripts\install-hooks.ps1

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$hooksDir = & git -C $repo rev-parse --git-path hooks
if ($LASTEXITCODE -ne 0) {
    throw 'not inside a git repository'
}
if (-not [System.IO.Path]::IsPathRooted($hooksDir)) {
    $hooksDir = Join-Path $repo $hooksDir
}

# Git runs hooks through its own sh, so the hook itself is a small shell script
# that starts check.ps1.
$hook = @'
#!/bin/sh
root="$(git rev-parse --show-toplevel)"
if command -v pwsh >/dev/null 2>&1; then
    shell=pwsh
else
    shell=powershell
fi
exec "$shell" -NoProfile -ExecutionPolicy Bypass -File "$root/scripts/check.ps1"
'@

$path = Join-Path $hooksDir 'pre-push'
[System.IO.File]::WriteAllText($path, ($hook -replace "`r`n", "`n") + "`n",
    (New-Object System.Text.UTF8Encoding($false)))
Write-Host "installed pre-push hook: $path"
