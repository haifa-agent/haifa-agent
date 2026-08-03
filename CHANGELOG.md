# Changelog

## 0.1.0-SNAPSHOT

- 修复 Windows Host Guarded PowerShell 将命令不存在或复合原生命令失败误报为成功的问题；Autonomous
  Delivery 生成配置同步显式声明当前 CLI 所需的 DeepSeek dialect/version/streaming 字段，并在迁移
  窗口关闭后统一拒绝测试资产 Schema 1 台账。
- 新增标准 OpenAI Chat Completions dialect，并通过显式 dialect/version/streaming 配置与受信
  Endpoint 治理，为 Coding Agent 和 Personal Assistant 配置第二个 Provider `gpt-5.6-luna`；
  严格兼容该协议的 HTTPS 厂商可使用任意 Provider ID，仅通过配置接入，HTTP 仍只允许显式启用的
  loopback。

- Coding Agent 改用产品拥有的版本化短 Prompt，移除 CLI 中按 Case 累积的方法论字符串；动态 Context
  只披露预算、实际修改、验证、Diff 和缺失证据等事实，生产完成门禁不再依赖关键词验证计划或模型
  自报的语义覆盖标签。
- 新增 macOS/Linux Local Coding Agent 可搬运发行目录：打包脚本生成 shaded JAR、无密钥安全配置和
  `haifa-coding` 启动器；将发行目录加入 `PATH` 后可从任意项目目录启动，并默认以当前目录作为
  Workspace。
- 修复 Coding Agent 的 `execution.run` 失败链：macOS 启动器会物理解析符号链接 `JAVA_HOME`；
  Provider 异常会持久化终态 Tool Call、失败 Step 和关联 Tool Result，避免后续对话因孤立 Tool Call
  被模型 Provider 以 HTTP 400 拒绝，同时继续禁止 `OUTCOME_UNKNOWN` 自动重放。
- Coding Terminal Phase C 新增 grapheme/cell-width 安全编辑、完整 Resize 状态保持、ANSI16/256/
  TrueColor/NoColor 回归、修饰 Enter 兼容诊断，以及在产品 Runtime 装配前执行的非 TTY fail-closed
  门禁。
- 修复 Coding Terminal 在 Run 已进入 `COMPLETED`、`FAILED`、`CANCELLED` 或 `TIMEOUT` 后仍保留旧
  active Run 的问题；同一 Run 随后到达的 Checkpoint/Resource 事件不会重新激活它，下一次 Enter
  会创建新 Turn，而不是向已结束的 Run 发送 Steer。
- macOS Coding Terminal 人工测试启动器在 `/quit` 后禁用 Git/通用交互分页器，并对离线 Git 验收
  显式使用 `--no-pager`，避免主屏恢复后被空白的 `less (END)` 页面覆盖。
- Coding Terminal Phase B 新增稳定 ID 的 Tool/Execution lifecycle 原位更新、结构化 Approval 卡片与
  回执、Steer accepted/applied 和持久 Follow-up 反馈、viewport 新输出提示，以及带下一步操作的五类
  恢复诊断；UI 不再解析 Interaction 自由文本或按事件名后缀猜测状态，并在审批/错误时保留编辑草稿。
- Coding Terminal Phase A 按 Pi 参考体验重构单列信息层级：移除常驻 Diagnostics、空 Pending、
  Widgets 和假冻结 Footer，新增明暗自适应的 User/Tool/Execution/Approval/Error 状态 Theme、
  Send/Steer 动态提示、跨平台 Alt/Option 与 Ctrl+J 帮助，并保持 NoColor、60×16 和既有
  CodingSessionClient/Reducer 边界。
- Coding Agent Phase 3 新增 Session 搜索、CAS 重命名、Core 权威归档与逻辑删除、手动线性历史
  Compaction、根 `AGENTS.md` 安全发现和仅影响后续 Run 的 `/reload`、经既有
  Policy/Approval/ExecutionBroker/Sandbox 的 `!`/`!!`，以及脱敏且不覆盖目标的版本化 JSONL
  Session 导出；Tree、Fork、Clone、PTY 与后台 Job 继续延期。
- 新增 `local-native` Sandbox Provider：macOS 使用 Seatbelt、Linux 使用 bubblewrap，作为显式可选
  严格模式兑现一次性命令的 Workspace 文件策略、`NetworkPolicy.DENY` 和进程树收敛；Windows
  明确不支持且不伪装成具有同等级严格隔离。
- CLI 的 `execution.run`、Live E2E 和三端交付 Profile 默认统一为
  `host-guarded + allow + shell auto` 可信本地开发基线；命令保留真实可用路径并支持同一命令内的
  临时 loopback Server，不宣称外部网络隔离。三端 fast CI 与独立 Local Native/Windows unsupported
  门禁分别验证默认体验和可选严格能力。
- 新增 SQLite Runtime 全量持久化与跨进程恢复装配，覆盖 Session、Run、Attempt、Checkpoint、Interaction、Tool Journal、Event/Outbox、配置与扩展状态；Project Application/CLI 支持 `MEMORY`、`SQLITE`、`SQLITE_WITH_JSONL`。
- 新增安全 JSONL Transcript 投影，使用提交后 Outbox、fsync 后确认、eventId 去重、截断/中间损坏诊断、跨进程锁和原子轮转；JSONL 不参与恢复。
- 持久化文件统一执行 POSIX `0700/0600` 或 Windows 当前用户独占 ACL，并补齐事务故障注入、busy 有界重试、磁盘秘密负样本和真实 SQLite 重启测试。
- 新增端到端模型 SSE 输出、稳定 Runtime output cursor/replay/listener，并保持同步 `AgentChatModel` 兼容。
- DeepSeek 默认启用 thinking/high；reasoning 通过受保护 continuation 与 Checkpoint 引用完成 Tool Call 续接，公共输出不包含推理原文。
- 新增阿里云百炼与火山方舟 OpenAI Chat dialect、受治理 Provider factory/profile；方舟显式区分 Model ID 与 Endpoint ID。
- 初始化 Maven 多模块工程、BOM、基础领域模型、Runtime API、架构约束和 CI 基线。
- 新增纯 Java Credential API/Core 与 Tool API/Core，提供 AES-GCM Secret Store、scope 解析、短生命周期 Lease、内容寻址 Tool Catalog、Draft 2020-12 Schema 校验和 Provider 路由。
- Runtime 配置快照改为冻结精确 `FrozenToolBinding`，模型规格由冻结定义派生，并将 Tool 审批改为释放 Worker、Checkpoint 后由新 Attempt 恢复的异步协议。
- Project Application 的 14 个 File/Git/Execution Tool 迁入唯一 Tool Catalog/Provider，同时保留 Workspace、capability 与 ExecutionBroker 边界。
- 新增固定协议 `2025-11-25` 的 MCP Client Integration，使用 MCP Java SDK 2.0.0 支持 Streamable HTTP 与 ExecutionBroker-backed stdio，并将远端 Tool 通过内容寻址 binding 接入唯一 Runtime Tool Pipeline。
- 修复模型工具名的 OpenAI-compatible 协议兼容性：内部点号身份保持不变，模型披露 Alias 改用 `file_read`、`git_status` 等 1-64 位安全名称，并恢复首个模型集成约定的非 strict 工具 Schema 默认值。
- 修复 Tool Result 下一轮模型消息只包含摘要的问题：Runtime 从权威 ToolCall 重建结构化结果与裁剪状态，OpenAI-compatible Adapter 将其编码为关联 Tool Message 内容。
- CLI 新增受控的 Streamable HTTP MCP Server 配置、启动期工具发现与 allowlist/profile 审查，并把已审核远端工具接入现有 Catalog、Runtime Policy 和结构化 Tool Result 链路。
- 新增通用 `execution.run` / `execution_run` Shell Tool：明确区分 DIRECT argv 与 SHELL 文本，由可信 Host 配置选择 Bash/PowerShell，经唯一 ExecutionBroker 执行并关联 FileChangeSet。
- CLI 默认启用 ask 审批的本地 Shell 能力，支持 auto/deny disclosure、实时有界脱敏输出、Output Ref、timeout/Runtime/Ctrl+C 取消和受限环境名称继承；具体 CLI 命令均走同一实现，不含逐命令适配。
- 新增纯 Java Skill API/Core/Base，兼容 `SKILL.md`，提供 SDK/Product/Tenant/User/Project 分层发现、确定性解析、内容寻址冻结、渐进披露、Run 级受控激活、资源读取和两个无外部依赖的基础 Skill。
- Runtime 新增最弱 `SKILL` Context 层与 `skill.load` / `skill.resource.read` Tool；Skill 激活和精确内容引用进入 Checkpoint/Resume，脚本只索引审查而不执行。
- CLI 支持从可信配置装配绝对路径的只读本地用户 Skill 目录，并以显式 `skills.allowed` 控制冻结、摘要披露和激活范围。
- CLI 新增 `--trace summary|detail|jsonl` 与 `--trace-file <path>`，实时输出现有 Runtime 安全 Trace，并按 Provider 区分模型、普通 Tool、MCP 与 Skill 调用；Trace 不包含 Prompt、Tool 原始参数、Credential、reasoning 原文或供应商原始响应。
