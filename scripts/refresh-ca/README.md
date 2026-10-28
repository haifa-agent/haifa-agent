# 本地 Coding Agent 更新脚本

入口：`scripts/refresh-ca/update-coding-agent.ps1`。
需要 Windows、Python 3.11+、Git、PowerShell、Maven 和 JDK 21（`java` / `jlink` 在 PATH 中）。

```powershell
$updater = '.\scripts\refresh-ca\update-coding-agent.ps1'

# 按分支
& $updater update --branch feat-terminal-markdown-rendering

# 按 worktree 目录名
& $updater update --worktree terminal-markdown-rendering

# 按 worktree 绝对路径
& $updater update 'D:\workspace\haifa-agent-worktrees\terminal-markdown-rendering'

# 也可以直接传分支名或 worktree 名；重名有歧义时使用显式参数
& $updater update feat-terminal-markdown-rendering

# 只查看源目录、版本和安装计划，不创建 worktree、不构建、不安装、不改 PATH
& $updater update --branch feat-terminal-markdown-rendering --dry-run
```

每次执行的流程：

1. 在主仓登记的 worktree 中定位源码。已检出的分支使用现有目录，包含该目录的未提交修改；
   未检出的本地分支在同级 `haifa-agent-worktrees/ca-build-<分支>-<SHA>` 创建 detached
   worktree，保留用于后续复用，不切换现有目录或分支。不自动拉取远端，先自行 `git fetch` / 更新分支。
2. 从所选源码的根 POM 读取版本，用该目录的 Maven 入口执行
   `--layer L2 -- -pl :haifa-agent-cli -am -DskipUnitTests=true clean package`。
   这是本地安装构建，跳过测试，不发布 SDK、不执行 `mvn install`，不能代替 PR 验证。
3. 调用**所选源码目录**的 `dist-support\scripts\build-distributions.ps1 ca` 生成带 JRE 的完整 CA ZIP，
   校验包内 JAR 与构建产物一致，再用隔离数据目录运行 `haifa.bat --help`；不调用 Provider。
4. 目标为 `<install-root>\haifa-coding-agent-v<根 POM 版本>`，例如 `haifa-coding-agent-v0.1.4-SNAPSHOT`。
   `--install-root` 优先；否则取环境变量 `HAIFA_CA_INSTALL_ROOT`；两者都没有时退回本机约定的
   `D:\dev\haifa-agent`。
   目录已有时只备份并替换 `lib\haifa-agent-cli-<版本>.jar`，保留配置、启动器和 JRE；替换前也验证旧启动器/JRE/配置
   能加载新 JAR。目录不存在时安装完整包，旧版本目录保留。新版本使用发行包默认配置，共用启动器约定的用户数据目录；
   旧版本安装目录内的自定义 YAML 不自动迁移。
5. 更新**用户 PATH**：将安装根目录内旧 CA 的 `bin` 替换为新版本，去掉重复 CA 项；无对应项则追加。
   保留其他 PATH 项、注册表值类型和系统 PATH，并广播环境变化。重新打开终端后运行 `haifa`。
   如果系统 PATH 含旧版 CA，会先报错要求移除该项，避免它优先于用户 PATH。

JAR 被运行中的 CA 锁定时会报错，退出 CA 后重新执行；脚本不结束现有进程。
即使替换成功，运行中的进程也需要重新启动才能加载新代码。

## 产物去向

ZIP、旧 JAR 备份和 `report.json`（含源码 SHA、是否有未提交修改、校验和及更新前的用户 PATH）写入被 Git
忽略的 `local-tmp/artifacts/ca-update-<时间>-<ID>/`。

每次成功后删除 `local-tmp/artifacts/` 下更早的 `ca-update-*` 目录，只保留最新一份，被删除的目录名记录在
`report.json` 的 `pruned_artifacts`。这类分发包可随时重建，重复保存会让本地工作区以 GB 级增长。

失败时以非零退出码结束，报告记录已进行的操作；例如 JAR 已更新但 PATH 并发变化时会保留该更新并报错，
不覆盖他人刚修改的 PATH。最新一份产物保留到下一次成功更新为止，便于从失败的构建中取回上一版包。

## 自测

脚本自测使用临时目录，不修改真实安装、注册表或 PATH：

```powershell
python -m pytest scripts/tests/test_update_coding_agent.py -q
```

`update_coding_agent.py` 承载逻辑，`.ps1` 只原样转发参数。
