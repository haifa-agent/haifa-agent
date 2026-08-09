# Build Support

该聚合区域包含依赖管理和构建治理支持，不承载产品业务代码。

## 分层 Maven 入口

本地开发使用 `scripts/invoke-haifa-maven.ps1` 或 `scripts/invoke-haifa-maven.sh`。入口不会改变 Maven
生命周期或吞掉退出码，只补充资源感知的默认线程数和一份脱敏 JSON 指标：

| 层级 | 默认线程 | 用途 | 是否 clean |
| --- | ---: | --- | --- |
| L0 | 1 | 静态预检、格式或编译边界 | 否 |
| L1 | 1 | 精确测试类及必要消费者编译 | 否 |
| L2 | 4 | 受影响产品完整测试 | 否 |
| L3 | 4 | 同一 Git SHA 的最终门禁 | 仅显式指定 |

Windows 下 Surefire/Failsafe 的 fork JVM 显式允许 manifest-only JAR 引用不同盘符上的绝对
classpath。该设置只进入测试 JVM，用于避免系统临时盘与 worktree 分盘时并行 fork 丢失 Reactor 类。

最终门禁按同一 Git SHA 聚合，而不是在一个命令中重复执行：

| Gate | Profile | 测试范围 | 前置条件 |
| --- | --- | --- | --- |
| Unit | `ci-fast` | Unit、Contract、Architecture；Spotless | 无，必须先执行 |
| Integration | `ci-integration-only` | Failsafe `*IT`、`*LiveIT`、`*E2E` | 同一 SHA 的 Unit PASS |
| Artifact | `release-artifacts` | 编译、打包、Source、Javadoc、制品 smoke | 同一 SHA 的 Unit PASS |

`ci-integration` 继续保留给独立或旧调用方，语义仍是 Unit + Integration。`integration-only` 和
`release-artifacts` 不能脱离同 SHA 聚合门禁单独证明交付完成。

## 只读影响范围建议

`scripts/suggest_maven_scope.py` 读取当前 worktree（含未跟踪文件）与 `HEAD` 的 Git diff，并从 Maven
POM 构建内部依赖/消费者图，输出直接模块、上游编译依赖、下游消费者、高风险扩大原因和 L1/L2
建议命令：

```powershell
python .\build-support\scripts\suggest_maven_scope.py --pretty
python .\build-support\scripts\suggest_maven_scope.py --base HEAD~1 --head HEAD --pretty
```

输出固定标记 `advisoryOnly=true`。根 POM/Wrapper/Workflow、公共 API、Core/Runtime、SQLite、安全、
Architecture/Contract 或测试选择变化会保守扩大到全量最终门禁。脚本不执行 Maven、不修改文件、
不自动跳过同 SHA 的最终 Gate；独立 `docs/`、`test-config/` 仓库不会进入根仓模块图。

Windows 示例：

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 -Layer L1 -MavenArguments @( `
  '-pl', ':haifa-agent-personal-assistant-server', '-am', `
  '-Dtest=MissionDispatcherTest,SqliteMissionStoreTest', `
  '-Dsurefire.failIfNoSpecifiedTests=false', 'test')

.\build-support\scripts\invoke-haifa-maven.ps1 -Layer L2 -MavenArguments @( `
  '-pl', ':haifa-agent-personal-assistant-server', '-am', 'test')
```

Unix 示例：

```bash
./build-support/scripts/invoke-haifa-maven.sh --layer L1 -- \
  -pl :haifa-agent-runtime-core -am \
  -Dtest=RuntimeCoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

指标默认写入被 Git 忽略的 `local-tmp/maven-build-metrics/`。默认不保留 Maven 原始日志；只有显式
`-KeepLog`/`--keep-log` 才保留。参数中疑似 Key、Token、Password、Secret 或 Credential 的值会被脱敏。
分类明确区分测试失败、编译失败、构建配置失败、外层超时、宿主 OOM、睡眠污染和取消。

Maven 输出默认只排入临时 UTF-8 日志，不向控制台持续回放，避免有界 AI/CI 输出采集器或非 UTF-8
Windows 控制台阻塞 Surefire。只有交互终端会持续消费全部输出时才使用 PowerShell `-StreamOutput`
或 Python/Shell `--stream-output`。调用方外层进程超时必须长于 `-TimeoutSeconds`；超时分类和精确
进程树清理由包装器负责。

汇总最近样本：

```powershell
.\build-support\scripts\summarize-maven-build.ps1 -Layer L1 -Limit 20
```

性能比较必须绑定相同 Git SHA、dirty 状态、JDK、OS、Profile 和 clean/incremental 条件。睡眠污染样本
保留原始墙钟，但不进入 P50/P95。
