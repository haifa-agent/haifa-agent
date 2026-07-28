# Haifa Coding Terminal

Coding Agent 的 tui4j 交互产品层。布局、信息层级和交互以
`docs/prd/pi-coding-agent-terminal-low-fi-prototype` 评审原型及
`docs/prompts/19-coding-agent-terminal-ui-ux-refactor-prompt.md` 为准，不自行发明 Sidebar、
Dashboard、Scenario toolbar 或另一套产品 UI。

## 模块定位

三个模块的职责固定如下：

```text
haifa-agent-cli
  最高层生产装配、参数/配置、稳定 Workspace 身份、唯一 shaded 可执行 JAR
        |
        v
haifa-agent-coding-terminal
  tui4j Program/Model、Viewport、Textarea、Selector、Reducer、View
  只通过 CodingSessionClient 读取和提交产品事实
        |
        v
haifa-agent-coding-agent
  CodingSessionService、Project/Workspace、Session/Queue/Cursor、Policy、
  MyBatis/SQLite 产品持久化
```

Terminal 不装配第二套 Runtime，不直接访问 SQLite、文件系统或进程，不依赖 Runtime Core、
SQLite Mapper、Sandbox Provider、`ProcessBuilder` 或 CLI 包，也不产生第二个胖 JAR。最终可执行制品
只有：

```text
haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0-SNAPSHOT.jar
```

## tui4j 迁移状态

迁移采用三阶段和两次人工门禁。人工已批准在动态 Resize 延期的前提下进入 Stage B；当前生产
`CodingTerminalApplication` 和 CLI Terminal 路由只使用 tui4j `Program / Model / update / view`。
旧 JLine 生命周期、编辑器、KeyMap、Completer、Display、Renderer、测试和直接依赖均已删除。
项目代码不得导入 `org.jline`；架构测试和 Maven Enforcer 会阻止旧实现回流。

tui4j `0.3.3` 由本模块直接依赖。该第三方库内部仍使用 `jline-terminal-jni` 作为跨平台终端后端，
项目依赖管理仅把这一传递后端收敛到 `3.30.0`；这不是项目保留的 JLine UI 实现。

Windows ConPTY Spike 已证明 `Program / Model / update / view`、viewport、textarea、
`Program.send()`、alternate screen、Unicode 粘贴及正常/Escape/Ctrl+C/异常退出可以运行；但动态
Resize 在三次调整后仍会丢失 Header/Diagnostics/Transcript 区域，已标记
`SKIPPED_AFTER_3_ATTEMPTS` 并延期。当前没有旧实现回退路径。

非 TTY 自定义流会被 tui4j 内部终端后端报告为 `1x1`，导致多帧 Renderer 输出被截断。该自动化路径
连续三轮调整仍未通过，已按规则跳过；输入语义由 Model/Reducer 测试覆盖，真实显示与退出恢复留给
Windows ConPTY Gate B。

## 原型映射与交互

Phase A 将原型映射收敛为固定单列顺序：

```text
Header
Resources summary（仅有真实资源时）
Transcript
Pending Messages（仅非空）
Active Status / Recoverable Error（仅需要感知时）
Editor or Selector
Footer
```

不再常驻渲染 Diagnostics、空 Pending、`Widgets above/below none` 或 `Footer` 标签。Footer 只显示
已有真实来源的 Project、Git（存在安全 Read Model 时）、Session、Context/Queue 和 Run 状态；
`git: via safe read model`、`provider/model: frozen`、`sandbox: frozen profile` 等实现占位字段不进入
产品界面。

Theme 使用 tui4j Lip Gloss 的 Adaptive Color 表达 Accent、Muted、User、Success、Pending、
Error、Queued 和 Focus。TrueColor 参考色会按明暗背景自适应；NoColor 环境仍依靠稳定标题、状态文字、
边界和顺序区分：

- User 使用低对比消息块，便于定位用户意图；
- Assistant 正文直接进入对话流，不使用厚卡片；
- Tool/Execution 根据 `requested/started/succeeded/failed/cancelled` 使用状态色，并在折叠时显示
  `ctrl+o expand`；
- Approval 使用 Pending 语义，Error 使用 Error 语义，Resource 保持中性；
- Editor/Selector 的当前操作提示使用 Focus 语义。

Editor hint 根据当前事实变化：Idle 显示 `enter send`；活动 Run 显示
`enter steer · alt+enter follow-up · esc interrupt`。macOS 的 Option 对应 Terminal Alt；
`Shift+Enter` 和 `Ctrl+J` 都用于换行。Phase C 会从终端能力白名单中识别 Windows Terminal、
WezTerm、Alacritty、Apple Terminal 和常见受限终端：存在修饰 Enter 冲突时显示可执行 remap 或
`Ctrl+J` fallback，但不读取秘密、不写入用户终端配置。非交互输入或 `TERM=dumb` 在装配产品 Runtime
前以稳定 `TUI_UNAVAILABLE` 失败。

Phase B 的工作流反馈只投影稳定产品 DTO 和 Runtime 事件：

- Tool 与 Execution 按稳定 ID 原位更新，不为同一调用重复创建卡片；显示明确 lifecycle、Target、
  Workdir、Stream、Exit、Result Ref 和 FileChangeSet Ref，缺失的 Duration 不伪造；
- Approval 从 `InteractionView` 显示 Action、Target、Risk、Scope、Network、Reason 与允许动作；
  `InteractionLifecycle.actionOrReason` 等自由文本不参与 UI 解析。Selector 接管输入期间以及响应回执后，
  原有 editor buffer/cursor 均保持不变；
- `RunInputLifecycle.ACCEPTED` 将 Steer 放入 Pending，`APPLIED` 后移除；持久 Follow-up 与 Steer
  合并展示且按稳定 ID 去重，Alt+Up 仍从产品队列恢复原文；
- 错误按 Retryable、User action required、Interrupted、Terminal capability、Terminal failure
  五类给出稳定错误码和下一步操作；失败和 Selector 都不清空草稿；
- viewport 不在底部时不抢滚动位置，只显示 `new output below`；回到底部后恢复自动跟随。

终端采用 tui4j `Program`、`Model`、`Viewport` 和 `Textarea`。Runtime 回调只写入有界 Action Queue；
50ms tick 在 Program 事件循环中排空队列，再由既有 Reducer 归约到唯一 `TerminalUiState` 并生成
View。空闲输入期间仍可归约 Runtime 事件和刷新界面。队列溢出或订阅意外关闭时，Controller 会从
权威 Session View 重新对账并按持久 Cursor 重建订阅，避免界面永久停留在 `Working/RUNNING`。
事件 Cursor 在每个 UI tick 合并为一次最新进度写入；瞬时持久化失败只保留待确认 Cursor 并在后续
tick 重试，不会终止渲染轮询或截断后续回复。

启动 UI 时进入 alternate screen 并清空独立屏幕缓冲区，因此启动命令和初始化日志不占用 TUI 行；
正常退出或异常关闭时退出 alternate screen，并恢复主屏内容、Attributes、Signal Handler、回显、
keypad 和光标。

Phase C 的 Textarea 适配层以 grapheme boundary 保存权威光标：CJK、surrogate pair、emoji ZWJ
序列和 combining mark 的左右移动、退格与删除不会拆分可见字符；多行上下移动按终端 cell width
对齐。Transcript 和固定区域同样按 cell width 截断，并在渲染前移除 ESC、控制字符和 Tab 注入。
颜色语义覆盖 TrueColor、ANSI256、ANSI16 和 NoColor；无色模式仍保留相同文字、状态和顺序。

- 普通首条消息创建真实 Coding Session/Run；
- Idle Enter 提交新 Turn，Active Enter 发送 Steer；
- Run 进入 `COMPLETED`、`FAILED`、`CANCELLED` 或 `TIMEOUT` 后立即回到 Idle；同一已结束 Run 随后
  到达的 Checkpoint/Resource 事件不得把它重新标记为 Active，下一次 Enter 必须提交新 Turn；
- Active Alt+Enter 写入持久 Follow-up Queue，Alt+Up 选择并恢复待发消息；
- 第二轮输入遇到 Run 刚结束/刚激活的状态竞态时，Terminal 先 reconcile 再按最新状态重试一次；
  其他产品错误只显示稳定错误码并保留草稿，不退出进程；
- Escape 是活动 Run 的全局取消键：裸 Escape 不得被当作 EOF；即使 Selector 打开或
  本地 Run 状态尚未刷新，也先关闭 Selector、reconcile 后向产品取消接口发送命令并显示 Cancelling。
  空闲时 Escape 只关闭 Selector；Ctrl+C 仍先清空非空 Editor，空 Editor 时取消活动 Run；
- `/resume` 选择并打开真实 Session；
- `/resume <query>` 搜索 Session，`/rename [name]`（兼容 `/name`）使用 revision 重命名，
  `/archive` 与 `/delete`
  在同一内联 Selector 中确认；删除只隐藏产品 Session，不删除 Runtime/Artifact 事实；
- `/compact` 复用 Runtime 的 ConversationSummary/Compression，保留原始消息；自定义压缩指令
  当前返回 `COMPACTION_INSTRUCTION_NOT_SUPPORTED`；
- `/reload` 重新发现 Workspace 根 `AGENTS.md`，只影响后续新 Run；当前 Run 不热替换；
- `!command` 与 `!!command` 都通过产品 Shell 服务进入同一 Policy/Approval/ExecutionBroker/
  Sandbox 链；前者的安全结果进入后续模型 Context，后者只保留内部 Session 事实和审计；
- `/export <workspace-relative-path>` 新建版本化、脱敏 JSONL，不覆盖文件、不跟随符号链接；
- 输入 `/` 或 `@` 后按 Tab 打开可见候选选择器；方向键选择、Enter 回填；空闲时 Escape 关闭并
  保留草稿，活动 Run 时 Escape 优先取消任务；
- `/command` 与 `/commands` 打开同一命令选择器；`@file` 候选来自受限 Workspace 文件目录，
  不列出敏感路径、版本库元数据和常见生成目录；
- pending Approval 在同一 tui4j Program input owner 中 approve/reject；
- `/settings`、`/trust`、`/model`、`/login`、`/tree`、`/fork`、`/clone` 在没有真实 API 时返回
  `CAPABILITY_NOT_IMPLEMENTED`，不显示装饰性选择器；
- `/quit` 退出；活动 Run 下 EOF 显示明确的退出选择。

## 构建与启动

```powershell
java -version # 必须是 Java 21
.\mvnw.cmd -pl :haifa-agent-cli -am package

$jar = ".\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar"
java -jar $jar --help
java -jar $jar --terminal `
  --workspace D:\haifa-agent-config\workspaces\terminal-manual `
  --config D:\haifa-agent-config\haifa-coding-terminal.yaml
```

无 `-m` 时默认进入同一 Terminal 路径。`D:\haifa-agent-config` 位于源码仓库之外；建议使用：

```text
D:\haifa-agent-config\
  haifa-coding-terminal.yaml
  data\
    coding-terminal.db
    transcripts\
  workspaces\
    terminal-manual\
```

Windows 上需要真实运行编译、包管理器或联网工具时，仅对明确检查和信任的测试 Workspace 配置
`host-guarded + network allow`。它以当前 OS 用户权限执行，不是容器或虚拟机；Approval 也不等于强
隔离。持久模式使用稳定的 `env://HAIFA_CONTINUATION_KEY`，其值必须是 Base64 编码的 32 字节 AES
key，并在所有重启间保持不变。

## Phase B 人工验收

进入 Phase 3 前人工验证 Phase 2，不等到最后统一验证：

1. 启动后确认原型规定的 Header/Transcript/Editor/Footer 单列顺序；
2. 不提交模型 Turn，验证普通输入、退格、方向键和 `/quit`，确认退出后主屏、echo 与 cursor 恢复；
3. 经用户明确授权后再执行一个真实联网 coding Turn：读取文件、修改代码、运行相邻测试；
4. 活动 Run 分别验证 Enter Steer、Alt+Enter Follow-up、Escape/Ctrl+C Cancel；
5. `/resume` 打开真实 Session，Selector 关闭后 editor buffer/cursor 恢复；
6. 重启后确认 Session、Queue、Cursor 不重复分派或渲染；
7. 默认 `approval=ask` 下验证实际 approve/reject；
8. 检查大输出有界，且不显示 Credential、完整 Tool 参数、Provider 原文或 reasoning。
9. 输入 `/` 和 `@` 后分别按 Tab，确认候选可见、可选择并正确回填；输入 `/command` 确认命令面板可见。
10. Terminal 模式使用 `--trace detail` 但不提供 `--trace-file`，确认 Trace 不写入 TUI；提供
    `--trace-file` 后确认诊断仅进入文件。
11. 用 Stub/Fake Tool 走通 requested → started → succeeded/failed/cancelled，确认同一 Tool/Execution
    只更新一张卡片，Ctrl+O 可折叠/展开最近项。
12. 用 Stub/Fake Approval 检查结构化字段、approve/reject 回执与 editor 草稿恢复；审批期间输入只由
    Selector 消费。
13. Active Enter 后观察 Steer 从 accepted 保持到 applied；Alt+Enter 后观察持久 Follow-up Queue，
    Alt+Up 恢复且重启后不重复。
14. PageUp 离开底部后产生新输出，确认 viewport 不跳动且出现 `new output below`；PageDown 回到底部
    后提示消失。

真实模型和 Web Provider 可能产生费用；未经单独授权保持 **NOT RUN**。自动化验证只使用 Stub/Fake：

```powershell
.\mvnw.cmd -pl :haifa-agent-coding-agent,:haifa-agent-coding-terminal,:haifa-agent-cli -am test
```
