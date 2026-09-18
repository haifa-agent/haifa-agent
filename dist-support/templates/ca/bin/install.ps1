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
    Write-Host "[成功] 已成功将 Haifa Coding Agent 添加至当前用户的 PATH 环境变量：" -ForegroundColor Green
    Write-Host "       $binPath" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "[提示] 请关闭并重新打开您的终端窗口（推荐 Windows Terminal），输入以下命令验证：" -ForegroundColor Yellow
    Write-Host "       haifa --help" -ForegroundColor White
} else {
    Write-Host "[信息] 环境变量 PATH 中已存在该目录，无需重复添加：" -ForegroundColor Cyan
    Write-Host "       $binPath"
}

Write-Host ""
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Warning "[提醒] 未在系统 PATH 中检测到 'git' 命令。"
    Write-Warning "       Coding Agent 执行代码操作需要 Git 支持，建议安装 Git for Windows (https://git-scm.com/)。"
} else {
    Write-Host "[检测] 系统已安装 Git，可正常使用 Coding Agent 全部功能。" -ForegroundColor Green
}
