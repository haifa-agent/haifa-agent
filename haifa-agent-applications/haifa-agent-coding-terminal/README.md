# Haifa Coding Terminal

Coding Agent 的 tui4j 交互产品层。布局、信息层级和交互以
`docs/prd/pi-coding-agent-terminal-low-fi-prototype` 评审原型及
`docs/prompts/19-coding-agent-terminal-ui-ux-refactor-prompt.md` 为准；模型连接流程同时遵守
`docs/prd/28-coding-agent-model-authentication-and-codex-subscription-login-prd.md`，不自行发明 Sidebar、
Dashboard、Scenario toolbar 或另一套产品 UI。

## 自主交付状态

Terminal 只消费 Runtime API 的 `DeliveryLifecycle` 安全 DTO，不读取 Runtime Store、SQLite、模型正文或
Tool 原始输出来推断交付状态。`completion.deferred` 显示 Recovering 或 Verifying，
`recovery.required` 显示 Recovering，`budget.threshold-reached` 显示 Budget threshold；卡片只包含
稳定 reason code、缺失 Evidence code、实际限制资源、当前用量/冻结上限、剩余百分比和纠偏次数，
不再要求用户从一个聚合百分比猜测限制来源。Run 到达终态后仍由既有生命周期归约
收起活动状态。NoColor 模式保留同样的稳定文字，不显示 Host Path、stderr、Fingerprint 或 Credential。

## 模块定位

三个模块的职责固定如下：

```text
haifa-agent-cli
  最高层生产装配、参数/配置、稳定 Workspace 身份、唯一 shaded 可执行 JAR
        |
        v
haifa-agent-coding-terminal
  tui4j Program/Model、Viewport、Textarea、Selector、Reducer、View
  只通过 coding-agent 模块公开的 CodingSessionClient 读取和提交产品事实
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

Terminal 默认启用 bracketed paste；完整 `PasteMessage` 中的 CRLF 会归一化为换行且不会提交。对于
没有发送 bracketed-paste 标记、而把剪贴板内容拆成普通按键的宿主，普通 Enter 经过 100ms 输入稳定
门禁：紧随其后的字符、CR 或 LF 会把该 Enter 归并为编辑器换行，只有独立 Enter 才提交。该门禁只
覆盖普通 Editor Enter；Shift/Ctrl+Enter、Alt+Enter 和 Selector/Approval Enter 保持各自既有语义。

生产 Model 初始化时主动请求一次真实 Window Size，避免在用户没有手工 Resize 时一直停留在
`80x24` 启动尺寸。tui4j `0.3.3` 不全局启用 Kitty keyboard protocol；该版本只为修饰 Enter
提供显式映射，全局启用会让部分 CSI-u 控制键残留字符进入编辑器。`Ctrl+O` 因此保持传统 `SI`
输入并稳定切换最近 Tool/Execution 卡片的展开状态。

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

不再常驻渲染 Diagnostics、空 Pending、`Widgets above/below none` 或 `Footer` 标签。Editor hint
下方显示已有真实来源的当前模型、Workspace 绝对路径和 Git 分支；后续状态行显示 Session、
Context/Queue 和 Run 状态；
Provider/Model 来自 Coding Product Facade 的脱敏投影，Terminal 不读取 Model Core、Provider 配置或
SQLite。`git: via safe read model`、`sandbox: frozen profile` 等实现占位字段不进入
产品界面。

Theme 使用 tui4j Lip Gloss 的 Adaptive Color 表达 Accent、Muted、User、Success、Pending、
Error、Queued 和 Focus。TrueColor 参考色会按明暗背景自适应；NoColor 环境仍依靠稳定标题、状态文字、
边界和顺序区分：

- User 使用低对比消息块，便于定位用户意图；
- Assistant 正文直接进入对话流，不使用厚卡片；高频 Markdown 子集只在 View 层转换为终端样式，
  `TranscriptItem`、Session 与持久化继续保留原始 Markdown；
- Tool/Execution 根据 `requested/started/succeeded/failed/cancelled` 使用状态色。成功或进行中的折叠项
  只占一行并把 `ctrl+o expand` 放在同一行；连续折叠项之间不插入空行。失败项在折叠状态额外保留
  最多两行安全原因，展开后才显示既有有界详情；
- Approval 使用 Pending 语义，Error 使用 Error 语义，Resource 保持中性；
- Editor/Selector 的当前操作提示使用 Focus 语义。

### Assistant Markdown

Terminal 当前支持标题、粗体、斜体、行内代码、围栏代码块、两级有序/无序列表、引用、普通链接、
段落和终端宽度换行。流式 Assistant 正文使用按 Item ID 隔离的增量状态：正常 append 只消费新增
delta，重复 frame 不重新解析；只有权威正文替换或 16 KB 有界正文发生尾部滚动、旧前缀已经消失时，
才从当前安全正文重建。控制字符在产生任何 Terminal Style 前移除，NoColor 保留相同文字和顺序。

延期 TODO（当前不应描述为已支持）：

- [ ] 代码语法高亮；
- [ ] Markdown 表格与三层以上嵌套列表；
- [ ] 终端图片渲染（当前降级为 `[image: alt] URL`）；
- [ ] 内嵌 HTML、脚注、定义列表和自动目录；
- [ ] Mermaid、数学公式与 KaTeX；
- [ ] GFM 删除线、任务复选框及其他扩展；
- [ ] OSC 8 可点击链接（当前显示 `label (URL)`）；
- [ ] Tool 专用预览器、跨 Tool 聚合和批量展开；首版保持每个稳定 Tool Call ID 可独立审计。

Editor hint 根据当前事实变化：Idle 显示 `enter send`；活动 Run 显示对应宿主的 Follow-up 与 Interrupt
快捷键。Windows/Linux 使用 `ctrl+o`、`alt+enter`、`alt+up` 等文本标签；macOS 使用 Apple 标准
`MAC_SPECIAL` 符号 `⌃O`、`⌥↩`、`⌥↑`、`⇧↩/⌃J`，并分别匹配终端实际产生的 Control/Option
事件，不把当前 tui4j 无法可靠接收的 Command 键标成可用快捷键。启动时只采集白名单内的
`os.name`、`os.version`、`os.arch`、`java.version`、`TERM_PROGRAM` 和 `TERM_PROGRAM_VERSION`，
用于平台选择与兼容性判断，不收集任意环境变量，也不持久化这些宿主事实。等待模型或其他活动 Run
场景从 `1s` 开始显示进程内计时；不足一分钟使用 `Ns`，达到一分钟后使用 `Nm Ns`。`RUNNING`、
`Working`、等待审批、恢复、验证和取消等活动状态共享同一 Run 计时，其中 `Working` 继续显示
`esc to interrupt`。计时使用单调时钟累计，不写入 Session 或持久化事实。Phase C 会从终端能力白名单中识别 Windows Terminal、
WezTerm、Alacritty、Apple Terminal 和常见受限终端：存在修饰 Enter 冲突时显示可执行 remap 或
`Ctrl+J` fallback，但不读取秘密、不写入用户终端配置。非交互输入或 `TERM=dumb` 在装配产品 Runtime
前以稳定 `TUI_UNAVAILABLE` 失败。

Phase B 的工作流反馈只投影稳定产品 DTO 和 Runtime 事件：

- Tool 与 Execution 按稳定 ID 原位更新，不为同一调用重复创建卡片；显示明确 lifecycle、Target、
  Workdir、Stream、Exit、Result Ref 和 FileChangeSet Ref，缺失的 Duration 不伪造；
- Runtime Checkpoint 继续持久化并推进事件 Cursor，但作为内部恢复事实不投影到 Transcript；
- Approval 从 `InteractionView` 显示 Action、Target、Risk、Scope、Network、Reason 与允许动作；
  `InteractionLifecycle.actionOrReason` 等自由文本不参与 UI 解析。Selector 接管输入期间以及响应回执后，
  原有 editor buffer/cursor 均保持不变；
- `RunInputLifecycle.ACCEPTED` 将 Steer 放入 Pending，`APPLIED` 后移除；持久 Follow-up 与 Steer
  合并展示且按稳定 ID 去重，Alt+Up 仍从产品队列恢复原文；
- 活动状态区分 `THINKING`（模型交互与处理工具结果）、`WORKING`（工具执行）和
  `WAITING FOR APPROVAL`（等待用户审批）。计时是当前活动阶段耗时，不是整轮 Run 总耗时：新 Run、
  工具开始、工具完成后返回模型、进入/离开审批、应用 Steer、恢复、验证和取消阶段时从 `1s` 重新开始；
  Assistant Delta、Execution 输出块、轮询、重复事件和 viewport 操作不重置。`WORKING` 只追加短 Tool
  名称，例如 `WORKING (12s) · execution.run`；内部仅以单调递增 revision 区分阶段，不展示 RunId 或
  ToolCallId；
- `coding.work-phase` 只投影派生的 `ORIENT/PLAN/CHANGE/VERIFY/REVIEW/DELIVER/BLOCKED`，终端显示
  `Work phase: <PHASE>`；同一阶段与缺失证据集合不变时不重复发事件，UI 不读取 Prompt、Host Path、
  命令或内部摘要来猜测阶段；
- 错误按 Retryable、User action required、Interrupted、Terminal capability、Terminal failure
  五类给出稳定错误码和下一步操作；失败和 Selector 都不清空草稿；

失败 Run 的 Transcript 使用 `[AgentErrorCode] 安全文案`，并在存在时显示 Diagnostic ID。
预算超限、模型限流/超时和 Tool Outcome Unknown 使用稳定 code 选择下一步；终端不解析英文
message，也不显示异常类或堆栈。
- viewport 只在用户主动 PageUp 后停止自动跟随并在新内容到达时显示 `new output below`；Run 状态引起的
  Header、Status 或 Editor 布局高度变化不会误判为用户滚动，PageDown 回到底部后恢复自动跟随；用户
  明确提交新消息或 Steer 时也会恢复自动跟随，避免上一轮回翻状态把新一轮输出持续藏在下方。
- 终端不启用鼠标事件上报，并在 Program 启动前和退出清理时显式关闭可能由旧进程遗留的鼠标模式；
  普通拖拽由宿主终端原生选择和复制文字；Transcript 使用
  PageUp/PageDown 回翻，PageDown 到底部后恢复自动跟随。方向键 Up/Down 继续保留单行输入历史和
  多行光标移动语义。

终端采用 tui4j `Program`、`Model`、`Viewport` 和 `Textarea`。Runtime 回调只写入有界 Action Queue；
50ms tick 在 Program 事件循环中排空队列，再由既有 Reducer 归约到唯一 `TerminalUiState` 并生成
View。空闲输入期间仍可归约 Runtime 事件和刷新界面。队列溢出或订阅意外关闭时，Controller 会从
权威 Session View 重新对账并按持久 Cursor 重建订阅，避免界面永久停留在 `Working/RUNNING`。
事件 Cursor 在每个 UI tick 合并为一次最新进度写入；瞬时持久化失败只保留待确认 Cursor 并在后续
tick 重试，不会终止渲染轮询或截断后续回复。

启动 UI 时进入 alternate screen 并清空独立屏幕缓冲区，因此启动命令和初始化日志不占用 TUI 行；
正常退出或异常关闭时退出 alternate screen，并恢复主屏内容、Attributes、Signal Handler、回显、
keypad 和光标。

alternate screen 不提供可靠的终端原生历史回滚。生产配置不启用鼠标事件上报，普通拖拽由宿主终端
原生选择和复制文字；Transcript 使用 `PageUp/PageDown` 回看，PageDown 到底部后恢复自动跟随。

Phase C 的 Textarea 适配层以 grapheme boundary 保存权威光标：CJK、surrogate pair、emoji ZWJ
序列和 combining mark 的左右移动、退格与删除不会拆分可见字符；多行上下移动按终端 cell width
对齐。Transcript 和固定区域同样按 cell width 截断，并在渲染前移除 ESC、控制字符和 Tab 注入。
颜色语义覆盖 TrueColor、ANSI256、ANSI16 和 NoColor；Selector 使用高对比背景、粗体和 `>` 共同表达
选中态，未选项降低视觉权重；无色模式仍保留相同文字、标记、状态和顺序。

- 普通首条消息创建真实 Coding Session/Run；
- Idle Enter 提交新 Turn，Active Enter 发送 Steer；
- 普通对话按 Enter 后先清空 Editor、显示用户消息与 `Submitting`，再由 tui4j Command Worker 执行
  Session/Runtime IO；完成结果只在 UI 线程归约，避免提交期间冻结首次可见反馈；
- Run 进入 `COMPLETED`、`FAILED`、`CANCELLED` 或 `TIMEOUT` 后立即回到 Idle；同一已结束 Run 随后
  到达的 Checkpoint/Resource 事件不得把它重新标记为 Active，下一次 Enter 必须提交新 Turn；
- Active Alt+Enter 写入持久 Follow-up Queue，Alt+Up 选择并恢复待发消息；
- 第二轮输入遇到 Run 刚结束/刚激活的状态竞态时，Terminal 先 reconcile 再按最新状态重试一次；
  其他产品错误只显示稳定错误码并保留草稿，不退出进程；
- Escape 是活动 Run 的全局取消键：裸 Escape 不得被当作 EOF；即使 Selector 打开或
  本地 Run 状态尚未刷新，也先关闭 Selector、reconcile 后向产品取消接口发送命令并显示 Cancelling。
  空闲时 Escape 只关闭 Selector；Ctrl+C 仍先清空非空 Editor，空 Editor 时取消活动 Run；
- `/resume` 选择并打开真实 Session；顶层 `haifa-coding resume`、`resume --last` 和
  `resume <SESSION_ID>` 复用同一产品客户端与打开流程；
- `/resume <query>` 搜索 Session，`/rename [name]`（兼容 `/name`）使用 revision 重命名，
  `/archive` 与 `/delete`
  在同一内联 Selector 中确认；删除只隐藏产品 Session，不删除 Runtime/Artifact 事实；
- 切换或启动恢复 Session 时，通过 `CodingSessionClient` 加载最近 100 条 `USER_VISIBLE` 历史投影，
  只显示安全 User/Assistant 正文及失败摘要；更早历史显示有界占位，不回放 Tool、Prompt、reasoning
  或 JSONL；
- `/compact` 复用 Runtime 的 ConversationSummary/Compression，保留原始消息；自定义压缩指令
  当前返回 `COMPACTION_INSTRUCTION_NOT_SUPPORTED`；
- `/reload` 重新发现 Workspace 根 `AGENTS.md`，只影响后续新 Run；当前 Run 不热替换；
- `!command` 与 `!!command` 都通过产品 Shell 服务进入同一 Policy/Approval/ExecutionBroker/
  Sandbox 链；用户在 Terminal 中输入精确命令并按 Enter 是该次 `ASK` 决策的显式一次授权，仍写入
  精确绑定的 Decision/Evidence，但不再对同一用户重复弹确认；`DENY` 仍拒绝。前者的安全结果进入
  后续模型 Context，后者只保留内部 Session 事实和审计；安全命令输出默认展开显示，长输出仍有界；
- `/export <workspace-relative-path>` 新建版本化、脱敏 JSONL，不覆盖文件、不跟随符号链接；
- 输入 `/` 或 `@` 后立即打开可见候选选择器，Tab 仍可按当前 token 重新打开；方向键选择、Enter
  回填；空闲时 Escape 关闭并保留草稿，活动 Run 时 Escape 优先取消任务；
- `/command` 与 `/commands` 打开同一命令选择器；`@path` 候选来自受限 Workspace 文件和目录，
  目录以 `/` 结尾，不列出任意路径层级中点号开头的隐藏文件/目录、敏感路径、版本库元数据和常见生成目录；
- pending Approval 在同一 tui4j Program input owner 中 approve/reject；
- `/model` 打开安全 Selector，也支持 `/model <internal-id>`；无 Session 或执行 `/new` 后的选择会冻结到
  下一次 Session 创建，创建失败时保留以便重试；已有 Session 的选择在活动 Run 期间拒绝切换，成功后只影响
  下一新 Run；
- `/login`、`/account` 和 `/logout` 通过 Coding 产品的安全认证客户端实现；首次启动且所选模型凭据缺失时
  打开同一连接选择器。显式启用本地兼容测试时，选择器同时展示 Antigravity subscription 并启动独立的
  Google Browser Callback；未启用时不展示该入口。ChatGPT 登录可选 Browser Callback 或 Device Code；设备 URL/设备码作为持久可见的
  Transcript 指引展示；Browser Callback 尝试自动打开浏览器，同时始终展示可复制授权 URL 并继续等待本机
  回调，避免系统报告已启动但窗口不可见。API Key 输入使用
  独立掩码缓冲区，不进入 Reducer、Session、Transcript、History 或 Completion；
- `/settings`、`/trust`、`/tree`、`/fork`、`/clone` 在没有真实 API 时返回
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
    后提示消失。先以大窗口渲染、再缩小窗口并用 PageUp 回翻；同时确认鼠标拖拽可由宿主终端选择文字。
15. 分别粘贴带 bracketed-paste 标记和不带标记的多行文本，确认所有行停留在 Editor，末尾换行也不
    提交；随后单独按 Enter 才提交一次。
16. PageUp 回翻后直接提交新消息，确认新一轮自动回到底部且不显示陈旧的 `new output below`；模型交互
    显示 `THINKING (1s)`，工具开始后显示 `WORKING (1s) · tool-name`，工具完成并返回模型后重新显示
    `THINKING (1s)`。进入审批显示 `WAITING FOR APPROVAL (1s)`；同一阶段在 61 秒显示为 `1m 1s`，
    Assistant Delta 和工具输出块不能重置计时。

真实模型和 Web Provider 可能产生费用；未经单独授权保持 **NOT RUN**。自动化验证只使用 Stub/Fake：

```powershell
.\mvnw.cmd -pl :haifa-agent-coding-agent,:haifa-agent-coding-terminal,:haifa-agent-cli -am test
```

## UI thread and background effects

The tui4j update thread only handles input, pure state reduction, and rendering. Runtime, storage,
workspace discovery, shell execution, session commands, reconcile, replay, subscription changes,
and cursor persistence run on bounded background executors. Latency-critical approval, cancel, and interrupt
operations use a CONTROL lane; ordinary interaction uses INTERACTIVE; cursor and recovery work uses MAINTENANCE. User actions first project an
immediate state such as `Approving`, `Cancelling`, or `Running shell command`; immutable completion
messages return to the UI thread on the next polling turn. A pending/requested interaction event queries only its
authoritative `InteractionView` on CONTROL and does not reload the Session, replay history, or rebuild subscriptions.
Reconciles are reserved for queue overflow, closed subscriptions, version races, and
explicit recovery.

Event replay retains at most the latest 2,000 lifecycle events in one background load so reopening a
large session cannot allocate an unbounded intermediate list. Durable conversation messages remain
the authoritative reopened transcript.

## Assistant streaming boundary

Assistant deltas are consumed through the closeable, Run-scoped transient output subscription.
They are bounded in memory and are not part of the durable Run Event Feed, SQLite journal, JSONL
projection, checkpoint, or cross-process replay contract. Durable Tool, Interaction and Run
lifecycle events continue to use the Run Event Feed. After a completed Run is reopened, the
authoritative Assistant text comes from the persisted Session messages.
