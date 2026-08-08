# Changelog

## 0.1.0-SNAPSHOT

- Personal Assistant adds durable Mission execution and an explicit Deep Research mode: frozen
  briefs and Skill coordinates, isolated Task Runs, bounded Search/Fetch, strict source and claim
  validation, partial synthesis, restart-safe final messages, five immutable Artifacts, and a
  shared Web workspace for plan confirmation and research delivery.
- Coding Agent 新增顶层 `haifa-coding resume` 基础能力：支持选择、最近及指定 Session，并可在打开后
  提交首条 Prompt；Terminal 从权威 Session Message Store 恢复最近 100 条安全用户可见历史，活动
  Run 仅只读打开且禁止自动接管、恢复或重复提交。
- 新增默认 `file.patch` 1.1：支持 Codex 风格上下文定位、多文件新增/删除/更新/移动，以及大文件流式
  转换、提交前哈希复核、同目录临时文件和原子替换；`file.write` 仅保留给整体替换的小文件。
- CLI 执行审计从每条命令前后全量 Workspace Manifest 改为一次基线加 WatchService 候选增量哈希；仅在
  事件溢出或观察器失效时全量重建，并保持预执行/执行后两类失败语义不变。
- 修复 Coding Agent 执行失败链：Python 缓存/虚拟环境默认不进入 Manifest，`.gitignore` 否定规则只
  撤销相交目录；Manifest 预检失败明确为 `WORKSPACE_MANIFEST_UNAVAILABLE`，OS 进程启动后才标记
  DISPATCHED；Runtime 保留具体 Tool 错误、取消未启动兄弟调用，并将 Diagnostic ID 落盘为可查询诊断。
- Coding Terminal 现在按 `os.name` / `os.version` / `os.arch` 识别宿主，并为 macOS 使用与真实
  Control/Option 输入一致的 `MAC_SPECIAL` 快捷键符号（如 `⌃O`、`⌥↩`、`⌥↑`）；界面标签与按键判定
  由同一 Shortcut Profile 生成，不把无法从当前终端协议可靠接收的 Command 键显示成可用快捷键。
- Coding Agent 默认不再向模型披露 Java `file.search`；仓库级文件发现和内容搜索改走通用
  `execution_run` OS CLI 主路径，优先使用当前 Shell `PATH` 中的 `rg --files` / `rg`，不可用时由模型
  选择平台适配的替代命令。`file.search` 仍可显式启用以兼容既有配置，产品代码不拼接搜索命令选项。
- Haifa Coding Agent 本地发行包默认改为 `SQLITE_WITH_JSONL + protection=NONE`：数据位于发行目录
  `data/`，无需 continuation key；可显式切换 `AES_GCM + env://HAIFA_CONTINUATION_KEY`。
- Personal Assistant 真实环境的 PowerShell、POSIX、Python 生命周期脚本及单测统一迁移至根目录
  `scripts/`，并同步启动、停止和环境搭建文档中的调用路径。
- 修复 Coding Terminal 未启用鼠标报告而将滚轮退化为输入历史方向键、以及内容超过一屏后因使用
  上一帧 Viewport 尺寸而无法回翻的问题；滚动现在于当前帧布局完成后生效，到达底部后恢复新输出
  自动跟随，并保持 Editor 草稿和输入历史不变。
- Coding Terminal 的 `@path` 补全现在过滤任意点号开头的文件或目录；活动状态显示累计耗时与
  `esc to interrupt`，Editor hint 下方显示当前模型、Workspace 绝对路径及可用的 Git 分支。
- Coding Agent 向模型冻结披露 `execution_run` 使用的宿主 Shell 方言，避免 Windows PowerShell
  环境生成 POSIX 混合命令；执行前后 Manifest 现在按冻结的生成目录/根 `.gitignore` 目录策略过滤，
  避免大型构建产物导致可信结果退化为 `OUTCOME_UNKNOWN`；新增真实批准后执行回归。
- Coding Agent 的普通交互不再因缺少工具或交付证据进入完成修复：未声明可信任务模式且没有权威
  Workspace 修改时，文本回答可正常结束；显式 CHANGE/CREATE/ANALYZE/REVIEW 及已观察到的修改
  继续执行既有证据门禁，真实 Provider 故障仍按原错误终止。
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
- 新增 macOS/Linux Haifa Coding Agent 可搬运发行目录：打包脚本生成 shaded JAR、无密钥安全配置和
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
