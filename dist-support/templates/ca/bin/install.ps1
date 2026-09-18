[CmdletBinding()]
param()

$binPath = Split-Path -Parent $MyInvocation.MyCommand.Definition
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")

if ($null -eq $userPath) {
    $userPath = ""
}

$pathEntries = $userPath -split ';' | Where-Object { $_ -ne "" }

if ($pathEntries -notcontains $binPath) {
    $newPath = if ($userPath.Trim().Length -gt 0) { "$userPath;$binPath" } else { $binPath }
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    Write-Host "[OK] Added Haifa Coding Agent to the current user PATH:" -ForegroundColor Green
    Write-Host "     $binPath" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "[Next] Close and reopen your terminal (Windows Terminal recommended), then run:" -ForegroundColor Yellow
    Write-Host "       haifa --help" -ForegroundColor White
} else {
    Write-Host "[Info] The user PATH already contains this directory, nothing to do:" -ForegroundColor Cyan
    Write-Host "       $binPath"
}

Write-Host ""
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Warning "[Warning] The 'git' command was not found in PATH."
    Write-Warning "          Coding Agent needs Git for code operations; install Git for Windows (https://git-scm.com/)."
} else {
    Write-Host "[Check] Git detected; all Coding Agent features are available." -ForegroundColor Green
}
