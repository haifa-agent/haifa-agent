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
| L3 | 2 | 同一 Git SHA 的最终门禁 | 仅显式指定 |

常用命令按工作量选择层级，不要把所有 `test` 都当作 L1：

| 场景 | 推荐入口 | 原因 |
| --- | --- | --- |
| 单个测试类/Contract | L1，`-Dtest=...` | 串行、低资源、最短编辑反馈 |
| 一个模块及 `-am` 的完整测试 | L2 | 固定 T4，适合模块闭环 |
| 全仓增量 `test` | L2 | 固定 T4，避免裸 Wrapper 单线程串行 55 个项目 |
| Spotless | L0 | 串行且记录指标；格式任务不需要模块并发 |
| `ci-fast clean verify` | L3 | 最终同 SHA 门禁，固定 T2 |

普通 Surefire 运行默认排除类级 `@Tag("slow")`。当前慢测集合只包含
`PersonalAssistantRestartTest`、`PersonalAssistantWebFluxTest`、`SqliteRuntimeRecoveryTest` 和
`LocalCodingAgentTest`；它们不会进入 L1/L2 的普通测试、L3 `ci-fast` 或调用 `ci-fast` 的 Fast CI。
测试源码和断言全部保留，使用 `slow-tests` Profile 显式运行：

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 '--' `
  -Pslow-tests test
```

```bash
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- \
  -Pslow-tests test
```

精确运行其中一个类时也必须添加 `-Pslow-tests`；仅使用 `-Dtest=<SlowTestClass>` 仍会被默认标签过滤。

Windows 下 Surefire/Failsafe 的 fork JVM 显式允许 manifest-only JAR 引用不同盘符上的绝对
classpath。该设置只进入测试 JVM，用于避免系统临时盘与 worktree 分盘时并行 fork 丢失 Reactor 类。

最终门禁按同一 Git SHA 聚合，而不是在一个命令中重复执行：

| Gate | Profile | 测试范围 | 前置条件 |
| --- | --- | --- | --- |
| Unit | `ci-fast` | 非 slow 的 Unit、Contract、Architecture；Spotless | 无，必须先执行 |
| Slow Unit | `slow-tests` | 四个类级 `slow` 测试 | 独立慢测入口，不属于日常门禁 |
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
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L1 -- `
  -pl :haifa-agent-personal-assistant-server -am `
  '-Dtest=MissionDispatcherTest,SqliteMissionStoreTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 -- `
  -pl :haifa-agent-personal-assistant-server -am test
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
.\build-support\scripts\summarize-maven-build.ps1 --layer L1 --limit 20
```

```bash
./build-support/scripts/summarize-maven-build.sh --layer L1 --limit 20
```

性能比较必须绑定相同 Git SHA、dirty 状态、JDK、OS、Profile 和 clean/incremental 条件。睡眠污染样本
保留原始墙钟，但不进入 P50/P95。

## Issue 29 文件 Tool 延迟基线

CLI 的 `--trace jsonl --trace-file <path>` 会为完成的 Tool 写入安全、单调计时字段；它们只包含
Tool 名称和阶段毫秒数，不包含路径、参数、文件内容、Prompt 或 Credential。将一次实际 Coding Run 的
Trace 汇总为 Markdown：

```powershell
java -jar .\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar `
  --terminal --trace jsonl --trace-file .\local-tmp\issue-29\runtime-trace.jsonl

python .\build-support\scripts\summarize_tool_latency.py `
  .\local-tmp\issue-29\runtime-trace.jsonl `
  --output .\local-tmp\issue-29\baseline-report.md
```

报告按 Tool 输出端到端、环境获取、Provider 调用、输出校验、Journal、归一化、外部化和终态持久化的
P50/P95/Max。`provider invocation` 覆盖本地 Project Tool 操作；若 `terminal persistence` 异常，必须与
同次运行的 `sqlite.uow` 阶段日志交叉核对，再讨论耐久性或恢复语义的调整。

## Java Language Server 开关

在本地 Maven 构建需要独占各模块 `target/` 输出目录时，可以暂停当前工作区由 Red Hat Java 扩展启动的
Eclipse JDT Language Server。控制器只匹配当前 VS Code 工作区对应的 JDT LS，不会操作 Maven、Surefire
或应用 Java 进程；Windows 使用进程挂起/恢复，macOS 与 Linux 使用 `SIGSTOP`/`SIGCONT`。

Windows PowerShell：

```powershell
.\build-support\scripts\set-java-language-server.ps1 status
.\build-support\scripts\set-java-language-server.ps1 stop
.\build-support\scripts\set-java-language-server.ps1 start
.\build-support\scripts\set-java-language-server.ps1 status --workspace D:\workspace\haifa-agent --verbose
```

macOS / Linux：

```bash
./build-support/scripts/set-java-language-server.sh status
./build-support/scripts/set-java-language-server.sh stop
./build-support/scripts/set-java-language-server.sh start
```

公共控制逻辑位于 `scripts/java_language_server.py`，只依赖 Python 3 标准库。暂停状态写入被 Git 忽略的
`local-tmp/java-language-server-control.json`；重复执行 `stop`/`start` 是幂等的。PowerShell 与 Shell
入口使用相同的小写位置动作和 `--kebab-case` 长参数。
