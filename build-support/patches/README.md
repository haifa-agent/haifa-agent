# 外部依赖补丁

## tui4j 0.3.3：Windows 鼠标跟踪

`tui4j-0.3.3-track-mouse.patch` 让 tui4j 在鼠标模式变化时调用 jline 的
`Terminal.trackMouse(...)`（启动、运行期 `enableMouseCellMotion`/`enableMouseAllMotion`/
`disableMouse`、退出清理）。

Windows 控制台的鼠标输入由 `ENABLE_MOUSE_INPUT` 控制；tui4j 只写 `?1002h`/`?1006h`
不会让 jline 的 reader 收到鼠标事件。不应用该补丁时，Coding Terminal 的滚轮回溯与
拖选复制在 Windows 上不工作（PageUp/PageDown 仍可用）。

### 应用与本地安装

版本保持 `0.3.3`，覆盖本地 Maven 仓库的同名坐标（先备份原 jar）：

```powershell
git clone https://github.com/WilliamAGH/tui4j.git ..\tui4j
git -C ..\tui4j checkout v0.3.3
git -C ..\tui4j apply <本目录>\tui4j-0.3.3-track-mouse.patch
Push-Location ..\tui4j
.\gradlew.bat '-Pversion=0.3.3' jar --no-daemon
Pop-Location
# 将 ..\tui4j\build\libs\tui4j-0.3.3.jar 覆盖到
# <localRepository>\com\williamcallahan\tui4j\0.3.3\tui4j-0.3.3.jar
```

### 注意

CI 从 Maven Central 解析 `tui4j:0.3.3`，不会自动获得该补丁。合入前需要上游修复并升级版本，
或在流水线中应用本补丁并发布到内部仓库；否则 Windows 上的应用内鼠标捕获在 CI/其他开发机上不可用。
