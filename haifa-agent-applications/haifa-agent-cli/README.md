# Haifa Agent CLI

`haifa-agent-cli` 是用于验证 Haifa Agent 现有 Runtime、OpenAI-compatible 模型、受控本地文件工具和 Streamable HTTP MCP Tool 的最小 Coding Agent 命令行入口。

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
  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, file.delete, file.move]
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
runtime:
  maxIterations: 50
  maxToolCalls: 32
  maxWallTimeMillis: 300000
```

`tools.enabled` 使用内部点号名称；CLI 向模型披露时会映射为 `file_list`、`file_read`、`file_write` 等 Provider-safe function name。

`mcp.servers` 在 CLI 启动时连接并发现远端工具。每个 Server 必须使用稳定的小写 `id`、显式 `allowedTools` 和唯一 `aliasNamespace`；示例工具向模型披露为 `utility_time_now`、`utility_calculate`。发现不到、Schema 不兼容或不在本地审核策略中的配置工具会使启动失败，不会静默降级。

`policyProfile: conservative` 可用于任意显式 allowlist，但默认按高风险、未知幂等性和始终审批处理。`policyProfile: utility` 只接受 `CodingAgentMcpProfile` 已审核的 Utility 子集。生产 Server 必须使用 HTTPS；`allowLoopbackHttp: true` 只允许 `127.0.0.1` 或 `localhost` 开发端点。当前 CLI MCP 装配只支持无认证 Streamable HTTP，Credential 注入和 stdio 尚未开放为 CLI 配置。

写文件、删除文件和移动文件默认要求控制台确认。`--approval auto` 仅适用于受信任的本地工作区；`--approval deny` 会拒绝这些操作。

本期不包含 Terminal UI、Git/命令工具装配、跨进程会话恢复或持久化运行记录。
