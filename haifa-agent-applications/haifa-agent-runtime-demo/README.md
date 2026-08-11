# Haifa Agent Runtime Demo

用于演示如何通过 `RuntimeCoreBuilder` 直接装配 Runtime、模型、原始 Tool SPI、MCP 与 Skill。它是
明确的 Example Application，不属于 Live Test Catalog，也不是 CP-01～CP-11 的测试事实源。普通
SDK 使用者应先阅读 `haifa-agent-sdk-example`；这里的类属于不发布的底层参考代码，不是 SDK API。

该 Demo 会访问真实外部 Provider 并可能计费，但执行结果不能替代 Adapter 相邻的 Live Probe 或
产品 E2E。真实测试仍由 Suite Runner、Case 和对应自动化 Oracle 管理。

## 目录与场景

`io.haifa.example.runtime.DeepSeekRuntimeDemo` 只负责解析参数、读取密钥和选择场景。公共的
DeepSeek Snapshot、`RuntimeCoreBuilder` 与 Run 等待逻辑位于 `support`，具体能力分别位于以下包：

| 包 | 示例 | 展示内容 |
| --- | --- | --- |
| `io.haifa.example.runtime.scenario` | `ModelOnlyRuntimeScenario` | 只注册模型，不开放 Tool、MCP 或 Skill |
| `io.haifa.example.runtime.scenario` | `RawToolRuntimeScenario` | 直接构造 `ToolDefinition`、Provider 和冻结 Catalog |
| `io.haifa.example.runtime.scenario` | `McpRuntimeScenario` | 将审核后的 MCP Tool 接入普通 Runtime Tool Pipeline |
| `io.haifa.example.runtime.scenario` | `SkillRuntimeScenario` | 冻结 Skill，并通过 `skill_load` 渐进激活 |
| `io.haifa.example.runtime.mcp` | `UtilityMcpRuntimePlatform` | MCP 连接、发现、allowlist/review 与本地 Alias |
| `io.haifa.example.runtime.skill` | `CounterfactualNewsroomSkillPlatform` | Classpath Skill 发现、Catalog 与 Activation Tool |

默认场景通过 `RuntimeCoreBuilder` 装配内存 Runtime，冻结 `deepseek-v4-pro` Model Snapshot，并执行一次
无 Tool 的 Agent Run。所有入口都可能访问真实 DeepSeek 服务并计费；它们不会从文件读取密钥，也不会
输出密钥。

入口优先读取 `DEEPSEEK_API_KEY`。环境变量缺失时，交互式终端使用隐藏输入；若当前启动方式没有
可用的 Java Console，则回退到可见的标准输入并明确警告。

Windows PowerShell：

```powershell
.\mvnw.cmd -pl :haifa-agent-runtime-demo -am package

$jar = ".\haifa-agent-applications\haifa-agent-runtime-demo\target\haifa-agent-runtime-demo-0.1.0-SNAPSHOT-deepseek-runtime.jar"
$env:DEEPSEEK_API_KEY = "<secret>"
java -jar $jar

# 也可把参数作为自定义 Agent objective
java -jar $jar "用一句中文介绍 Haifa Agent"

# 注册进程内 demo_echo Tool；默认目标会触发 LLM -> Tool -> LLM
java -jar $jar --with-tool

# 自定义目标下 Tool 只是可用能力，是否调用仍由模型决策
java -jar $jar --with-tool "调用 demo_echo 返回 hello，然后总结结果"

# 连接 Utility MCP，只导入 unit_convert；默认目标会触发 LLM -> MCP -> LLM
java -jar $jar --with-mcp

# 默认地址为 http://127.0.0.1:20002/mcp，也可通过环境变量覆盖
$env:HAIFA_UTILITY_MCP_URL = "http://127.0.0.1:20002/mcp"
java -jar $jar --with-mcp "调用 utility_unit_convert 把 2 km 转换为 m，然后总结结果"

# 冻结并渐进激活平行世界新闻编辑部 Skill；默认生成三代新闻和因果连续性审计
java -jar $jar --with-skill

# 自定义反事实前提和年代
java -jar $jar --with-skill "如果晶体管直到 1990 年才被发明，出版 1991、2010、2040 三期中文新闻并审计因果链"
```

未设置环境变量时可直接执行 `java -jar`，入口会提示输入。不要把 API Key 放入命令行参数、仓库
配置、日志或测试报告。

`--with-tool` 选择 `RawToolRuntimeScenario`，只注册一个 `PURE + LOW + NEVER approval` 的进程内
Echo Tool，没有文件、进程、网络或 Credential 副作用。它特意展示底层 Tool SPI；SDK 业务代码应优先
采用 `haifa-agent-sdk-example` 中的类型化 `JavaTool`。默认目标要求模型调用一次 `demo_echo`；Runtime
写入关联 Tool Result 后进入下一轮模型调用。Profile 最多允许 1 次 Tool Call 和 3 次 Model Call，
避免示例意外循环。

`--with-mcp` 与 `--with-tool` 互斥。`McpRuntimeScenario` 在启动时连接固定协议 `2025-11-25` 的
Streamable HTTP MCP，只将本地审核通过的 `unit_convert` 以 `utility_unit_convert` 暴露给模型，
然后冻结到与普通 Tool 相同的 Catalog 和 Runtime Tool Pipeline。默认 MCP 目标要求模型调用一次单位
转换并在收到结果后返回固定文本；Demo 结束时会关闭 MCP 连接，但不会负责启动或停止外部 Utility MCP
服务。

`--with-skill` 与另外两个能力参数互斥。`SkillRuntimeScenario` 从 Classpath 发现
`run-counterfactual-newsrooms`，冻结到 Run 配置，但第一轮只向模型披露 Skill 元数据和
`skill_load`。模型调用一次 `skill_load` 后，Runtime 在下一轮把完整 Skill 指令加入
`PromptLayer.SKILL`。该 Skill 根据一个历史或技术分歧点出版至少三个年代的新闻，并在末尾审计因果
机制、延迟、矛盾、薄弱环节和替代分支。
