@echo off
setlocal
chcp 65001 >nul 2>&1

set "HAIFA_BIN=%~dp0"
echo Installing Haifa Coding Agent into the current user PATH...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$bin=$env:HAIFA_BIN.TrimEnd('\'); $user=[Environment]::GetEnvironmentVariable('Path','User'); if ($null -eq $user) { $user='' }; $entries=@($user -split ';' | Where-Object { $_ -ne '' }); if ($entries -notcontains $bin) { $new = if ($user.Trim().Length -gt 0) { $user.TrimEnd(';') + ';' + $bin } else { $bin }; [Environment]::SetEnvironmentVariable('Path',$new,'User'); Write-Host ('[OK] Added to user PATH: ' + $bin) -ForegroundColor Green } else { Write-Host ('[Info] Already in user PATH: ' + $bin) -ForegroundColor Cyan }; if (Get-Command git -ErrorAction SilentlyContinue) { Write-Host '[Check] Git detected.' -ForegroundColor Green } else { Write-Warning '[Warning] git not found in PATH; Coding Agent needs Git for Windows (https://git-scm.com/).' }"

echo.
echo Close and reopen your terminal, then run: haifa --help
pause
endlocal
