# Haifa Agent CLI

`haifa-agent-cli` 是用于验证 Haifa Agent 现有 Runtime、OpenAI-compatible 模型和受控本地文件工具的最小 Coding Agent 命令行入口。

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
approval:
  mode: ask
runtime:
  maxIterations: 50
  maxToolCalls: 32
  maxWallTimeMillis: 300000
```

写文件、删除文件和移动文件默认要求控制台确认。`--approval auto` 仅适用于受信任的本地工作区；`--approval deny` 会拒绝这些操作。

本期不包含 Terminal UI、Git/命令工具装配、跨进程会话恢复或持久化运行记录。
