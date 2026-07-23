# Haifa Agent CLI

`haifa-agent-cli` 是用于验证 Haifa Agent 现有 Runtime、OpenAI-compatible 模型、受控本地文件工具、通用本地 Shell Tool 和 Streamable HTTP MCP Tool 的最小 Coding Agent 命令行入口。

## 构建与运行

```powershell
.\mvnw.cmd -pl :haifa-agent-cli -am package
java -jar .\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar -m "分析当前项目并修复一个小问题"
```

构建后也可以将 `bin` 目录加入 `PATH`，使用 `haifa-cli.ps1` 启动。

## 配置

配置优先级为命令行参数、环境变量、工作区 `.haifa-agent/config.yaml`、用户目录 `~/.haifa-agent/config.yaml` 和内置默认值。密钥只允许通过凭据引用提供：

```powershell
$env:DEEPSEEK_API_KEY = "<secret>"
```

```yaml
model:
  providerId: deepseek
  modelId: deepseek-v4-pro
  endpoint: https://api.deepseek.com
  credentialRef: env://DEEPSEEK_API_KEY
tools:
  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, file.delete, file.move, execution.run]
mcp:
  servers:
    - id: utility
      displayName: Haifa Utility MCP
      endpoint: http://127.0.0.1:8091/mcp
      allowLoopbackHttp: true
      allowedTools: [time_now, calculate]
      aliasNamespace: utility
      policyProfile: utility
approval:
  mode: ask
execution:
  shell: auto
  defaultTimeoutMillis: 120000
  maxTimeoutMillis: 1800000
  maxOutputLines: 2000
  maxOutputBytes: 51200
  maxProcesses: 8
  inheritEnvironment: [PATH, HOME, USERPROFILE, TMP, TEMP, SystemRoot, JAVA_HOME, MAVEN_OPTS, GRADLE_USER_HOME]
runtime:
  maxIterations: 50
  maxToolCalls: 32
  maxWallTimeMillis: 300000
```

`tools.enabled` 使用内部点号名称；CLI 向模型披露时会映射为 `file_list`、`file_read`、`file_write`、`execution_run` 等 Provider-safe function name。`execution.run` 接收完整命令文本、Workspace 相对工作目录和 timeout；任何本机已安装且可由配置 Shell 解析的普通 CLI 都走同一生产路径，文档中的具体命令仅是非穷举示例。

CLI 的 DeepSeek 冻结配置强制关闭 thinking，并通过 Runtime output listener 实时打印安全的 answer delta；
reasoning 原文不会进入终端。使用 `--verbose` 时只会打印供应商报告的 reasoning token 计数，不记录或展示
reasoning 内容。

`mcp.servers` 在 CLI 启动时连接并发现远端工具。每个 Server 必须使用稳定的小写 `id`、显式 `allowedTools` 和唯一 `aliasNamespace`；示例工具向模型披露为 `utility_time_now`、`utility_calculate`。发现不到、Schema 不兼容或不在本地审核策略中的配置工具会使启动失败，不会静默降级。

`policyProfile: conservative` 可用于任意显式 allowlist，但默认按高风险、未知幂等性和始终审批处理。`policyProfile: utility` 只接受 `CodingAgentMcpProfile` 已审核的 Utility 子集。生产 Server 必须使用 HTTPS；`allowLoopbackHttp: true` 只允许 `127.0.0.1` 或 `localhost` 开发端点。当前 CLI MCP 装配只支持无认证 Streamable HTTP，Credential 注入和 stdio 尚未开放为 CLI 配置。

写文件、删除文件、移动文件和 Shell 命令默认要求控制台确认。Shell 审批显示完整 command、逻辑 workdir、timeout、Shell 类型及 Host 非强隔离提示。`--approval auto` 仅适用于用户明确信任的本地工作区，仍经过 Broker、Workspace capability、Profile、环境和审计；`--approval deny` 会在 Catalog freeze 前移除 `execution.run`，模型不可见，底层授权仍 fail closed。

`execution.shell` 支持 `auto`、`bash` 和 `powershell`。自定义 Shell 必须通过本地配置中的绝对 `shellPath` 提供，不能来自 Tool 参数。环境配置只保存允许继承的名称；默认不继承 API Key、`*_TOKEN`、`*_SECRET`、云凭据或代理凭据。命令输出实时脱敏展示，最终模型结果默认限制为最后 2000 行且最多 50KB；较大分通道输出通过 Output Ref 访问。CLI timeout 与 Ctrl+C 会发送 Runtime CANCEL，并有界等待 Broker 收敛进程树。

本期不包含 Terminal UI、PTY/交互式命令、后台守护进程、跨进程会话恢复或持久化运行记录。Host Provider 不是容器或虚拟机，不能阻止当前 OS 用户本来可访问的 Workspace 外文件、网络或系统资源。

## 真实模型 Coding E2E

CLI 模块包含 9 个真实 DeepSeek 驱动的编程 E2E，覆盖单文件修复、多文件功能、回归测试、Maven
配置、等价重构、文件迁移、脏工作区保护、失败恢复和审批拒绝。用例清单及初始工程位于
`src/test/resources/coding-e2e/`，每次执行都复制到新的隔离 Workspace；不会回放 Stub 或历史模型响应。

普通 `test` 和 `ci-fast` 不会访问真实模型。Live 批次必须显式提供以下环境：

```text
HAIFA_CLI_LIVE_E2E_TEST=true
HAIFA_FT_ENABLED=true
HAIFA_FT_MODE=LIVE
HAIFA_FT_RUN_ID=<unique-batch-id>
HAIFA_FT_ROOT=<new-empty-absolute-directory>
DEEPSEEK_API_KEY=<secret-manager-injected-value>
```

`HAIFA_FT_ROOT` 必须包含 `.haifa-cli-live-e2e-root`，内容与 `HAIFA_FT_RUN_ID` 完全相同，且除该
sentinel 外初始为空。测试不会永久删除批次目录；执行者应将其作为 CI Artifact 隔离并按 TTL 清理。

```bash
./mvnw -pl :haifa-agent-cli -am -Pci-integration -DskipITs=false \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=CodingAgentLiveE2E clean verify
```

每个通过的用例会在 `target/coding-agent-live-e2e-evidence/` 生成脱敏 JSON，包含模型调用 ID、
Provider/模型/Adapter 版本、Usage、Tool Call 统计、耗时、Fixture digest、修改逻辑路径和 Oracle 结果；
不包含 API Key、任务全文、模型原始响应、reasoning 原文或真实 Workspace 路径。测试题不依赖 Web
Search，除真实模型 Endpoint 外不需要外部信息服务。

当前硬门禁是 Run 正常完成、独立 Oracle 通过、受保护文件和批次边界未变化、真实模型证据完整且无敏感
信息泄漏。耗时、Token、模型调用数、工具调用数和失败工具结果作为二级趋势指标记录，用于后续比较模型与
Prompt 效率；现阶段除“失败后恢复”专用用例外，不因单次效率波动判失败。
