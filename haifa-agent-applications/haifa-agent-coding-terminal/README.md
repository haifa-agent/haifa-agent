# Haifa Coding Terminal

Coding Agent 的 tui4j 交互产品层。布局、信息层级和交互以
`docs/prd/pi-coding-agent-terminal-low-fi-prototype` 评审原型及
`docs/prompts/19-coding-agent-terminal-ui-ux-refactor-prompt.md` 为准；模型连接流程同时遵守
`docs/prd/28-coding-agent-model-authentication-and-codex-subscription-login-prd.md`；界面支持响应式双栏工作区与可折叠信息侧栏（Ctrl+X B 切换），不自行发明无序扩展 UI。

## 自主交付状态

Terminal 只消费 Runtime API 的 `DeliveryLifecycle` 安全 DTO，不读取 Runtime Store、SQLite、模型正文或
Tool 原始输出来推断交付状态。`completion.deferred` 的 `phase` 由 Runtime 中性发布为 `COMPLETION`，
Terminal 统一显示 Completion deferred，不再把 Runtime 的 blocker code 猜测成 Recovering 或 Verifying；
`budget.threshold-reached` 显示 Budget threshold；卡片只包含
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
haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0.jar
```

## tui4j 迁移状态

迁移采用三阶段和两次人工门禁。人工已批准进入 Stage B（tui4j 事件驱动的动态 Resize 仍延期，见下）；当前生产
`CodingTerminalApplication` 和 CLI Terminal 路由只使用 tui4j `Program / Model / update / view`。
旧 JLine 生命周期、编辑器、KeyMap、Completer、Display、Renderer、测试和直接依赖均已删除。
项目代码不得导入 `org.jline`；架构测试和 Maven Enforcer 会阻止旧实现回流。

tui4j `0.3.3` 由本模块直接依赖。该第三方库内部仍使用 `jline-terminal-jni` 作为跨平台终端后端，
项目依赖管理仅把这一传递后端收敛到 `3.30.0`；这不是项目保留的 JLine UI 实现。

Windows ConPTY Spike 已证明 `Program / Model / update / view`、viewport、textarea、
`Program.send()`、alternate screen、Unicode 粘贴及正常/Escape/Ctrl+C/异常退出可以运行；但 tui4j 事件
驱动的动态 Resize 在三次调整后仍会丢失 Header/Diagnostics/Transcript 区域，已标记
`SKIPPED_AFTER_3_ATTEMPTS` 并延期。tui4j `0.3.3` 的自动 Resize 只依赖 POSIX `WINCH` 信号，而 Windows
JVM 不支持该信号，因此生产 Model 在 Windows 上以 50ms 轮询每计数 10 次（约 500ms）重新查询真实
Window Size 作为兜底；该兜底尚未在真实 Windows 终端上人工验收。旧 JLine 实现没有回退路径。

Terminal 默认启用 bracketed paste；完整 `PasteMessage` 中的 CRLF 会归一化为换行且不会提交。对于
没有发送 bracketed-paste 标记、而把剪贴板内容拆成普通按键的宿主，普通 Enter 经过 100ms 输入稳定
门禁：紧随其后的字符、CR 或 LF 会把该 Enter 归并为编辑器换行，只有独立 Enter 才提交。该门禁只
覆盖普通 Editor Enter；Shift/Ctrl+Enter、Alt+Enter 和 Selector/Approval Enter 保持各自既有语义。

Bracketed paste 达到 3 行或超过 150 字符（`strip` 后）时，Editor 只插入 `[Pasted #N ~M lines]` 占位符，
完整正文按原样（仅做换行/控制字符归一化）保存在进程内的当前草稿映射中；占位符按整体单元处理（左右键跨界、
退格/删除一次清除，任何会破坏占位符的编辑被拒绝并提示 `PASTE_PLACEHOLDER_ATOMIC`），提交时再展开为完整
原文后进入 Session 与输入历史。提交路由基于可见文本，隐藏正文不会触发 `/` 命令或 `!` shell。阈值与
多行长文本折叠策略一致；较小粘贴仍原样插入。

生产 Model 初始化时主动请求一次真实 Window Size，并在 Windows 上约每 500ms 重新查询，避免停留在
`80x24` 启动尺寸或在窗口缩放、跨显示器移动后与实际终端尺寸不一致。尺寸未变化时 `Tui4jTerminalIo` 在
消息进入 tui4j 渲染器前丢弃该 `WindowSizeMessage`，避免渲染器清空行缓存导致整屏重绘。tui4j `0.3.3`
不全局启用 Kitty keyboard protocol；该版本只为修饰 Enter 提供显式映射，全局启用会让部分 CSI-u 控制键
残留字符进入编辑器。`Ctrl+O` 因此保持传统 `SI` 输入并稳定切换最近 Tool 卡片的展开状态。

启动 UI 时把宿主窗口标题压栈（`CSI 22;2t`）并设为 `Haifa Coding Agent`，退出时弹栈恢复（`CSI 23;2t`）；
支持 xterm 标题栈的终端会恢复原标题，不支持时保留应用标题。标题只影响宿主终端显示，不写入 Session、
Transcript 或任何持久化事实。

非 TTY 自定义流会被 tui4j 内部终端后端报告为 `1x1`，导致多帧 Renderer 输出被截断。该自动化路径
连续三轮调整仍未通过，已按规则跳过；输入语义由 Model/Reducer 测试覆盖，真实显示与退出恢复留给
Windows ConPTY Gate B。

## 原型映射与交互

终端采用响应式双栏工作区（主会话视口 + 可折叠信息侧栏）与一体化输入卡片架构：

```text
┌─────────────────────────────────────────────────────────────┬────────────────────────────────┐
│ [主会话视口 (Sticky to bottom)]                             │ [右侧侧边栏 Sidebar (40列宽)]  │
│                                                             │  (快捷键: ctrl+x b 折叠/展开)  │
│  User / Assistant 消息流                                    │  Session: <title>              │
│  Tool 执行与调用详情                                        │  ID: session_01...             │
│                                                             │  Directory: /path/to/workdir   │
│                                                             │  Git branch: main              │
│                                                             │  Model: <model> (<provider>)   │
│                                                             │  Resources: skills, MCP        │
│                                                             │  Haifa Agent v0.1.3            │
│ [交互卡片槽位：Approval / Selector / 命令面板]              │                                │
│ ▌ [Textarea 输入区，3 行，按左栏宽度换行]                   │                                │
│ ▌                                                           │                                │
│ ▌                                                           │                                │
│ Normal · model                 enter send                    │                                │
├─────────────────────────────────────────────────────────────┴────────────────────────────────┤
│  ~/workspace/haifa-agent (空闲为路径/执行中为Spinner)    5.2k · 35s     [f2] models  [ctrl+p] commands │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

不再常驻渲染顶部 Header、Diagnostics、空 Pending、`Widgets above/below none` 或 `Footer` 标签。
主视口高度最大化释放给核心对话与工具调用流。

### 核心交互与生命周期契约

1. **响应式双栏与侧栏 (Sidebar)**：
   - 宽屏（终端宽度 >= 100 列）：启用双栏布局，左侧为主交互区，右侧为 40 列固定宽度的信息侧栏；
   - 窄屏（终端宽度 < 100 列）：自动隐藏侧栏，保持专注单栏编辑与浏览；
   - 分栏竖线连续覆盖会话视口、输入框、输入提示和选择器；编辑器布局与左栏共用同一宽度，长文本按终端单元格换行，不进入右侧栏。侧栏切换或窗口缩放时重新排版，保留草稿和光标；最底部全局状态/快捷键栏保持通栏。
   - 窄屏信息可达性：窄屏用户无需展开侧栏，即可通过 `<leader>s`（`/session`）弹层随时查看同等详尽的环境、Session、Git、模型与资源摘要；
   - 布局自适应与用户显式折叠偏好：首次启动或窗口 Resize 时，终端根据当前列宽（>=100 列）自动计算侧栏展示；一旦用户使用 `<leader>b` 显式折叠或展开侧栏，终端锁定用户的显式偏好（`sidebarExplicitlyToggled`），后续窗口缩放事件不会强行覆盖用户的偏好选择。

2. **焦点与弹层优先级契约 (Focus & Modal Priority Contract)**：
   - 普通内建工具审批增加 `Allow for this session`：当前 Coding Session 内后续普通工具审批自动通过，退出应用后清除；`/approval reset` 恢复逐次审批。首次批准仅在 Runtime 实际应用后生效，Pending 不激活；工作区授权、权限提升、凭据/输入交互、未知工具和硬性拒绝保持原流程。
   - 任何选择器弹层（审批 Approval、Shell 确认、命令面板 Command Palette、模型选择、Session 摘要等）处于激活状态时，输入焦点完全移至弹层（Editor blur），编辑器失焦并禁用一切普通文本输入；
   - 弹层处于激活状态时，所有按键均按弹层事件处理（上下移动选中项、Enter 确认当前项、Esc/Ctrl+C 取消关闭）；
   - 弹层打开期间，所有全局 Leader 快捷操作（如 `<leader>m`、`<leader>s` 等）均被严格忽略（`Kind.EXECUTE_COMMAND` 语义完全与弹层的 `Kind.SUBMIT` 隔离），严禁借用编辑器的 SUBMIT 事件意外确认审批或执行未预期的全局命令；
   - 弹层处于激活状态时，允许 `Ctrl+O` 展开/折叠后台关联工具详情供审查，但不会触发审批提交；
   - 弹层关闭或取消后，Editor 自动重新获取焦点（Editor focus）。

3. **草稿与粘贴映射保护契约 (Draft & Paste Preservation Contract)**：
   - 用户在输入框中编写的草稿内容、光标位置（Cursor），以及因多行/长文本粘贴而生成的占位符映射（`[Pasted #N ~M lines]` -> 原文正文映射 `pastedContent`），受到全局严格保护；
   - 打开命令面板（`Ctrl+P`）、Session 摘要（`<leader>s`）、交互式重命名（`<leader>r`）等辅助弹层时，Controller 自动暂存当前草稿与光标；
   - 弹层完成或被取消（Esc/Ctrl+C）后，完整恢复用户原有的草稿正文与光标位置，不被命令替换为空或 `/command`；
   - 即使在输入框临时用于交互式重命名期间，粘贴占位符映射也不会被垃圾回收，草稿恢复后完整保留粘贴映射；
   - 交互式 Session 重命名（`<leader>r` 或 `/rename` 不带参数）：无活动 Session 时提示友好错误；有活动 Session 时在编辑器展示当前 Session 名称供修改，Enter 确认重命名并在后台异步执行，Esc 取消并恢复草稿。

4. **异步后台执行与模块边界契约 (Background Execution & Boundary Contract)**：
   - Terminal 遵守纯 Java 与无直接进程/文件 I/O 约束（由 ArchUnit 架构测试阻止 `ProcessBuilder`、`Process`、`java.io.File` 及 `java.nio.file..` 直接调用）；
   - 外部编辑器唤起（`<leader>e`）在当前版本明确延期：不引入子进程 `ProcessBuilder` 阻塞 UI 线程；按下 `<leader>e` 仅提供友好提示，不破坏界面事件循环；
   - 模型目录读取与轮换（`F2` / `Shift+F2`）：模型列表通过后台 Effect 异步加载并防抖，按键轮换仅在已绑定且可用的模型间循环；活动 Run 期间严格禁止切模。

5. **状态栏信息与提示层级契约 (Status Bar & Feedback Contract)**：
   - 活动 Run 期间：底部状态栏左侧显示单调计时的运行状态 `⠋ <timedActivity> · esc interrupt`；新输出在视口外产生时追加 ` · new output below`，严禁使用路径文字覆盖活动状态指示；
   - 空闲（Idle）期间：左侧优先显示操作反馈与引导状态（如 `/new` 提示、切模成功反馈、重命名状态等）；无反馈时显示当前工程路径；
   - 右侧展示快捷键提示（`[ctrl+o] details  [f2] models  [ctrl+p] commands`）及 Session 性能指标；空间受限时优先回退为不含 `[ctrl+o]` 的短提示并优先丢弃指标、次选裁剪工程路径，核心操作反馈与活动运行状态具有最高显示优先级；
   - 输入框上方 Meta 条右侧保持显示编辑器操作提示（如 `enter waiting · alt+enter follow-up`、
     Waiting 存在时 `enter send now · e edit · esc cancel`，或 Idle 的 `enter send · shift+enter newline`）。

Theme 使用 tui4j Lip Gloss 的 Adaptive Color 表达 Accent、Muted、Surface Panel、Surface Element、Border Subtle、User、Success、Pending、
Error、Queued 和 Focus。TrueColor 参考色会按明暗背景自适应；NoColor 环境仍依靠稳定标题、状态文字、
边线字符和顺序区分：

- User 消息采用左侧竖线装饰（`│ `，窄屏 `<40` 列自动降级为单空格缩进）与平滑面板底色（Surface Panel），
  首行带有 `❯ You` 双重角色线索；文本按可用宽度（`contentWidth = Math.max(16, mainWidth - 3)`）严格软折行，
  单行 cell 宽度严格闭合，**不增加上下封闭框线与额外空行**以保持最大垂直视口高度；
- Error 消息采用统一的左侧红色竖线装饰（`│ `）与弱化红底，首行带有 `✖ Error` 双重结构化标签，
  完整保留错误原因、上下文与恢复建议诊断文本；
- Assistant 正文保持 2-cell 左对齐以与 User 边线气泡形成清晰的视觉层级；输入框 Prompt Box 适配
  Surface Element 底色，侧栏垂直分割线统一使用 Border Subtle，消除界面视觉断层；
- 高频 Markdown 子集只在 View 层转换为终端样式，`TranscriptItem`、Session 与持久化继续保留原始 Markdown；
- Tool 主行使用该 Run 冻结定义的可读标题（如 `Read File`、`Apply Patch`、`Run Command`），不再把机器名
  直接当展示名；展开后的元数据保留机器名与分类徽标，历史事件缺少机器标识时只对已知内置名做固定映射。
  正常完成/`EXITED` 的折叠项严格占一行：正常成功以 `✓` 开头，非零进程退出以中性 `○` 开头，随后是标题徽标、调用说明（优先模型
  `intentSummary`/`description`，否则安全 Target Summary，再否则标题）、辅助信息（非零退出码与耗时）和
  右端折叠符 `›`；说明在 `REQUESTED` 阶段即可展示，不提前显示成功。连续折叠项之间不插入空行。
  `EXITED` 仍表示进程已退出，非零退出码（如 `exit 1`）在折叠和展开时都保留，不把它显示为绿色通过，
  也不升级为 Run 失败；`exit 0` 不重复展示。不根据命令文本或模型意图推断“预期内失败”。
- 一次普通工具调用失败使用克制的黄色 `!` 和单行“未完成：短原因”，折叠态不再强制附带两行详情；
  展开后仍显示真实错误码、目标、结果和安全输出。用户拒绝/规则阻止使用中性 `–` 与“已拒绝/规则阻止”，
  用户取消使用中性 `–` 与“已取消”，都不再画成系统故障红叉。`OUTCOME_UNKNOWN` 继续使用醒目的黄色 `?`
  和“结果未确认”提示，折叠态保留最多两行处理信息；`ATTENTION`/`RECOVERY REQUIRED` 等阻断状态仍用红色。
  Run 自身的失败仍由红色 Run Summary 表示，不把历史普通失败全部染红。
- 折叠态耗时始终显示；按 `ctrl+o` 或点击条目可展开/收起。鼠标选中过的条目保持为当前展开目标，
  未选中过条目时回退到最近可展开项。点击仅在鼠标捕获开启时可用，且完成文本选择后不触发展开。
  新 Tool 生命周期投影将业务输出与 `Technical details` 分离：`ctrl+x t`（macOS `⌃X T`）切换当前工具的
  技术信息，包含机器名、关联 ID、原因码、Result Ref 与可得进程状态，以及 `Duration … · N lines · X KB`。
  展开后的业务输出优先使用 Runtime 投影的有界 `outputPreview`（当前为本次实际写入的文件内容或本次调用
  Diff），Diff 由 `TerminalDiffHighlighter` 逐行高亮并保留缩进，NoColor 退化为相同纯文本；工具结果没有该
  事实时回退既有有界摘要，不从自然语言或事后文件反推。
  第二层展开状态在同一调用的更新中保留；旧事件或缺少结构化投影的历史条目继续显示原有详情，不猜测输出文本结构。
- 运行中的执行工具通过进程内 transient publisher 增量刷新同一 Tool Call 卡片的
  `Output (streaming):` 段；每批最多 4 KiB，卡片正文最多保留 16 KiB 尾部，stderr 和 preview
  丢弃会显式标记；执行侧达到输出上限时使用独立截断标记，避免与预览丢批混淆。该内容不持久化、
  不 replay，工具终态事件会用权威结果整体替换临时预览；
- Run 进入终态（completed/failed/cancelled/timeout）时追加一张 Run Summary 卡片，
  标题显示终态与可得耗时（如 `Run completed · 24s`），正文显示终态、稳定错误码及耗时，
  不再从本地 Transcript 反推或累加工具与变更集计数；
- Run `COMPLETED` 时只把具有同一 Run 关联的最近 Assistant 消息标为 `Assistant / Final response`，
  不解析正文判断完成程度，仍调用现有 Markdown renderer 并保留原始正文。`ctrl+x y`（macOS `⌃X Y`）
  通过既有后台 Clipboard/OSC 52 路径复制该回复的已渲染正文；`ctrl+x a`（macOS `⌃X A`）定位同一 Run 的
  首个工具活动，无活动时提示不可用。历史消息缺少可信 Run 关联时不猜测。完成后底栏显示 `Ready for follow-up`，
  沿用唯一输入框；Run completed 卡片仅表示生命周期终态，不证明测试通过或工作全部完成。
- Approval 使用 Pending 语义，Error 使用 Error 语义；`delivery-*` 与
  `resource-*` Resource 项默认可折叠为一行，认证、历史等关键 Resource 项保持展开可见；
- Editor 的当前操作提示使用弱化的 Muted 语义，Selector 仍使用 Focus 语义；普通 Editor 始终保留
  3 行输入区域，并使用深色背景区分可编辑范围。顶部全局快捷键区保持独立展示。

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
- [ ] 目录表格、读取正文分页和项目持久化范围授权仍需各自产品事实/激活机制；`file_create`/`file_write`/`file_patch`
  已展示本次调用实际写入的内容与 Diff，`file_read`、`file_list` 等工具的结构化结果仍只有有界摘要，
  Terminal 不从 View 直接读取文件，也不从事后仓库状态或自然语言生成这些内容。
- [ ] Tool 专用结构化预览器、跨 Tool 聚合和批量展开；首版实时输出仍保持每个稳定 Tool Call ID
  独立、有界地展示。

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

- Tool 按稳定 Tool Call ID 原位更新，不为同一调用重复创建卡片；显示明确 lifecycle、通用 Target 和
  Result Ref，缺失的 Duration 不伪造。Terminal 不从 Runtime Event 解释 Execution 命令、Workdir、
  Stream、Exit 或输出；这些仅可由拥有 Execution 结果的产品集成另行展示；
- Runtime Checkpoint 继续持久化并推进事件 Cursor，但作为内部恢复事实不投影到 Transcript；
- Approval 优先从 `InteractionView.approvalPresentation` 渲染结构化展示：动作标题、目的、内容类型与
  正文、环境要点、默认折叠的技术细节和允许动作；缺少结构化展示时回退到 `safePrompt` 文本。
  `InteractionLifecycle.actionOrReason` 等自由文本不参与 UI 解析。Selector 接管输入期间以及响应回执后，
  原有 editor buffer/cursor 均保持不变；
- `RunInputLifecycle.ACCEPTED` 将 Steer 放入 Pending，`APPLIED` 后移除；持久 Follow-up 与 Steer
  合并展示且按稳定 ID 去重，Alt+Up 仍从产品队列恢复原文；
- 活动状态区分 `THINKING`（模型交互与处理工具结果）、`WORKING`（工具执行）和
  `WAITING FOR APPROVAL`（等待用户审批）。计时是当前活动阶段耗时，不是整轮 Run 总耗时：新 Run、
  工具开始、工具完成后返回模型、进入/离开审批、应用 Steer、恢复、验证和取消阶段时从 `1s` 重新开始；
  Assistant Delta、轮询、重复事件和 viewport 操作不重置。`WORKING` 只追加短 Tool
  标题，例如 `WORKING (12s) · Run Command`；内部仅以单调递增 revision 区分阶段，不展示 RunId 或
- 错误按 Retryable、User action required、Interrupted、Terminal capability、Terminal failure
  五类给出稳定错误码和下一步操作；失败和 Selector 都不清空草稿；遇到 `MODEL_AUTHENTICATION_REQUIRED`
  时保持用户草稿，并弹出模型就绪恢复选择器，安全默认项为通过 `/model` 切换已就绪模型，次选为 `/connect`；

失败 Run 的 Transcript 使用 `[AgentErrorCode] 安全文案`，并在存在时显示 Diagnostic ID。
预算超限、模型限流/超时和 Tool Outcome Unknown 使用稳定 code 选择下一步；终端不解析英文
message，也不显示异常类或堆栈。
- viewport 只在用户主动 PageUp 后停止自动跟随并在新内容到达时显示 `new output below`；Run 状态引起的
  Header、Status 或 Editor 布局高度变化不会误判为用户滚动，PageDown 回到底部后恢复自动跟随；用户
  明确提交新消息或 Steer 时也会恢复自动跟随，避免上一轮回翻状态把新一轮输出持续藏在下方。
- 终端保持禁用应用级鼠标事件上报（不启用 SGR cell-motion），启动与退出时输出防御性 reset 序列。
  划词文本选择与系统剪贴板复制由宿主终端（Windows Terminal、VS Code 终端、iTerm2 等）原生接管，
  支持在整个屏幕任意区域拖拽选中与复制。`PageUp/PageDown` 负责应用拥有的 Transcript 视口翻页导航，
  方向键 Up/Down 继续保留单行输入历史和多行光标移动语义。

终端采用 tui4j `Program`、`Model`、`Viewport` 和 `Textarea`。Runtime 回调只写入有界 Action Queue；
50ms tick 在 Program 事件循环中排空队列，再由既有 Reducer 归约到唯一 `TerminalUiState` 并生成
View。空闲输入期间仍可归约 Runtime 事件和刷新界面。队列溢出或订阅意外关闭时，Controller 会从
权威 Session View 重新对账并按持久 Cursor 重建订阅，避免界面永久停留在 `Working/RUNNING`。
事件 Cursor 在每个 UI tick 合并为一次最新进度写入；瞬时持久化失败只保留待确认 Cursor 并在后续
tick 重试，不会终止渲染轮询或截断后续回复。
流式 Transcript 新增显式换行或因终端宽度自动折行时，只更新 Viewport 内容，不发送全屏清除命令；
自动跟随、PageUp 回看和 `new output below` 提示继续由既有状态控制。

启动 UI 时进入 alternate screen 并清空独立屏幕缓冲区，因此启动命令和初始化日志不占用 TUI 行；
正常退出或异常关闭时退出 alternate screen，并恢复主屏内容、Attributes、Signal Handler、回显、
keypad 和光标。

alternate screen 不提供可靠的终端原生历史回滚，因此 Transcript 历史由应用维护视口，通过
`PageUp/PageDown` 提供键盘翻页导航；用户将历史文本滚动到当前视口后，可直接使用宿主终端的原生鼠标
划词进行文本选择与剪贴板复制，应用不介入选择计算或剪贴板传输。

Phase C 的 Textarea 适配层以 grapheme boundary 保存权威光标：CJK、surrogate pair、emoji ZWJ
序列和 combining mark 的左右移动、退格与删除不会拆分可见字符；多行上下移动按终端 cell width
对齐。Transcript 和固定区域同样按 cell width 截断，并在渲染前移除 ESC、控制字符和 Tab 注入。
颜色语义覆盖 TrueColor、ANSI256、ANSI16 和 NoColor；Selector 使用高对比背景、粗体和 `>` 共同表达
选中态，未选项降低视觉权重；无色模式仍保留相同文字、标记、状态和顺序。

- 普通首条消息创建真实 Coding Session/Run；
- Idle Enter 提交新 Turn；Active Enter（有文本）先进入输入框上方的本地 Waiting 区，不立刻 Steer；
- Waiting 区动作：空 Editor 再按 Enter = Send Now（Steer）；空 Editor 按 `e` = Edit；
  Esc = 取消 Waiting（不 arm / 不取消当前 Run）。Run 自然结束后若仍有 Waiting，自动作为下一 Turn 发送；
- 普通对话按 Enter 后先清空 Editor、显示用户消息与 `Submitting`，再由 tui4j Command Worker 执行
  Session/Runtime IO；完成结果只在 UI 线程归约，避免提交期间冻结首次可见反馈；
- Run 进入 `COMPLETED`、`FAILED`、`CANCELLED` 或 `TIMEOUT` 后立即回到 Idle；同一已结束 Run 随后
  到达的 Checkpoint/Resource 事件不得把它重新标记为 Active，下一次 Enter 必须提交新 Turn；
- Active Alt+Enter 写入持久 Follow-up Queue，Alt+Up 选择并恢复待发消息；
- 第二轮输入遇到 Run 刚结束/刚激活的状态竞态时，Terminal 先 reconcile 再按最新状态重试一次；
  其他产品错误只显示稳定错误码并保留草稿，不退出进程；
- Escape：若存在 Waiting，只取消 Waiting；否则是活动 Run 的全局取消键（先 arm 再确认）。
  裸 Escape 不得被当作 EOF；Selector 打开时先关闭 Selector。空闲时 Escape 只关闭 Selector；
  Ctrl+C 仍先清空非空 Editor，空 Editor 时取消活动 Run；
- `/resume` 选择并打开真实 Session；顶层 `haifa-coding resume`、`resume --last` 和
  `resume <SESSION_ID>` 复用同一产品客户端与打开流程；
- `/resume <query>` 搜索 Session，`/rename [name]`（兼容 `/name`）使用 revision 重命名，
  `/archive` 与 `/delete`
  在同一内联 Selector 中确认；删除只隐藏产品 Session，不删除 Runtime/Artifact 事实；
- 未显式指定 `resume` 的 Terminal 启动会在 INTERACTIVE 后台队列异步查询当前 Workspace 最近一次
  Session；加载期间 UI 保持可输入，未找到历史 Session 时维持既有空态。显式 `resume` 仍按其给定
  Session 或 `--last` 语义执行；
- 切换或启动恢复 Session 时，通过 `CodingSessionClient` 加载最近 100 条 `USER_VISIBLE` 历史投影，
  只显示安全 User/Assistant 正文及失败摘要；更早历史显示有界占位，不回放 Tool、Prompt、reasoning
  或 JSONL；
- `/compact` 复用 Runtime 的 ConversationSummary/Compression，保留原始消息；自定义压缩指令
  当前返回 `COMPACTION_INSTRUCTION_NOT_SUPPORTED`；
- `/reload` 重新发现 Workspace 根 `AGENTS.md`，只影响后续新 Run；当前 Run 不热替换；
- `!command` 与 `!!command` 都通过产品 Shell 服务进入同一 Policy/Approval/ExecutionBroker/
  Sandbox 链；`ASK` 决策会打开 Shell 确认选择器（`Run governed shell command?`），显式批准后才
  执行，`DENY` 仍拒绝。前者的安全结果进入后续模型 Context，后者只保留内部 Session 事实和审计；
  安全命令输出默认展开显示，长输出仍有界；
- `/export <workspace-relative-path>` 新建版本化、脱敏 JSONL，不覆盖文件、不跟随符号链接；
- 输入 `/` 或 `@` 后立即打开可见候选选择器，Tab 仍可按当前 token 重新打开；方向键选择、Enter
  回填；选择器打开期间普通字符（含空格）继续编辑草稿；空闲时 Escape 关闭并保留草稿，活动 Run
  时 Escape 优先取消任务；
- `/command` 与 `/commands` 打开同一命令选择器；`@path` 候选来自受限 Workspace 文件和目录，
  目录以 `/` 结尾，不列出任意路径层级中点号开头的隐藏文件/目录、敏感路径、版本库元数据和常见生成目录；
- 默认由应用接管鼠标：滚轮在 transcript 区域内上下回溯，左键拖拽建立选区并在释放时复制，
  本地剪贴板失败时回退 OSC 52；`/mouse` 切换开关，`/mouse on|off` 显式设置，
  `HAIFA_TERMINAL_MOUSE=off` 决定启动初始态。关闭后恢复宿主原生选择，两种模式都保留 PageUp/PageDown；
- pending Approval 在同一 tui4j Program input owner 中 approve/reject；
- `/model` 打开安全 Selector，也支持 `/model <internal-id>`；当模型来自多个 Provider 时先按 Provider
  分组展示（`Provider · N models`），再展示所选 Provider 的模型；在二级模型列表或详情页按向左键可返回上一级列表并保持当前选中项位置。
  返回模型列表时保持刚检查的模型位置。无 Session 或执行 `/new` 后的选择会冻结到下一次 Session 创建，创建失败时保留以便重试；已有 Session
  的选择在活动 Run 期间拒绝切换，成功后只影响下一新 Run；
- `/connect` 是唯一连接入口；`/login` 从命令列表与帮助中移除，输入时只提示改用 `/connect`。`/account` 和
  `/logout` 继续通过 Coding 产品的安全认证客户端实现。首次启动且所选模型凭据缺失时，连接中心按
  “订阅登录 / API Key”展示目录中当前可用的 Provider，而不是只展示默认模型对应的一个选项。连接中心显示
  分类、推荐/已连接/当前徽标和说明，并支持直接输入名称过滤。显式启用本地兼容测试时展示 Antigravity 并
  启动独立的 Google Browser Callback；未启用时不展示该入口。ChatGPT 登录可选 Browser Callback 或
  Device Code。Browser Callback 尝试自动打开浏览器并继续等待本机回调；授权链接不进入 Transcript，
  等待面板提供倒计时、复制链接和重新打开浏览器，按 Escape 可取消，进入凭据换取/保存阶段后拒绝取消。
  设备码等待面板显示设备码并提供复制设备码、复制链接和打开页面动作。认证成功完成（或 API Key 保存成功）
  后刷新连接状态，并按 Provider 加载模型：只有一个可用模型时直接选中，多个模型时打开过滤后的模型列表，
  没有配置模型时明确提示配置位置。`/account` 显示脱敏账号并提供更新 Key、重新登录和断开动作；断开需要
  二次确认，默认焦点在取消。模型凭据失效时，恢复选择器的次选项直接进入当前 Provider 的登录方式，
  不再要求用户从连接中心顶部重新选择。API Key 输入使用独立单行掩码缓冲区（自适应首次录入与更新覆盖），
  不进入 Reducer、Session、Transcript、History 或 Completion，凭据安全持久化至原生系统凭据管理器
  （Windows Credential Manager）；百炼（Aliyun Bailian）使用三步向导依次录入 API Key、Workspace ID 和
  Region，仅 Key 掩码，Workspace/Region 明文校验；`/connect api aliyun-bailian <workspaceId> [region]`
  仍可用于预填并快速完成，首次配置要求真实 Workspace ID，更新既有配置时在 Key 步骤回车可继承现有值，
  Region 缺省为 cn-beijing；
- `/trust` 通过标准产品客户端异步展示脱敏的持久 Workspace 授权；
  `/trust revoke <workspaceRef>` 可撤销非初始根，不暴露宿主路径且不阻塞 UI；
- `/settings`、`/tree`、`/fork`、`/clone` 在没有真实 API 时返回
  `CAPABILITY_NOT_IMPLEMENTED`，不显示装饰性选择器；
- `/quit` 退出；活动 Run 下 EOF 显示明确的退出选择。

## Assistant Markdown rendering

Assistant text uses a product-local AST renderer for heading levels, emphasis, inline code, links,
images as alt text and URLs, three visible list indentation levels, nested list/quote/code blocks,
horizontal rules, and pipe tables. Tables wrap cells by terminal cell width; when columns cannot
fit, each row becomes a list of `column: value` lines. Code panels have a language label, a left
stripe and a dedicated background, including whitespace and wrapped lines. NoColor keeps the
structural text without SGR sequences. Markdown source in Session and TranscriptItem is unchanged.

Streaming reparses the mutable top-level tail. A prefix can be frozen only after its containers
are closed and a structural blank followed by another top-level block proves the boundary.
The final block stays mutable. Resize reuses the AST and only reflows rendered lines; replacement
and bounded-tail rollover rebuild the document. Cache entries are removed with transcript retention
and cleared on theme changes. Parse metrics count sanitized source characters actually parsed,
including reparsed tail characters; `fullParseCount` counts initial document construction and
replacement rebuilds, while `lastDeltaCharacters` counts newly received sanitized characters.

An internal startup-only escape hatch selects the improved line renderer with the system property
`-Dhaifa.terminal.markdown.renderer=legacy` or environment variable
`HAIFA_TERMINAL_MARKDOWN_RENDERER=legacy`. The system property takes precedence. Unset values use
the AST renderer; an unrecognized value logs one fixed warning and uses AST. No automatic fallback
or live switching occurs. The escape hatch is not part of user YAML configuration. Legacy preserves
the P1/P2 spacing and styles but does not support nested blocks or tables. Retire it and its switch
after two released versions with no unresolved AST rendering regressions, retaining parser and
renderer tests as the behavior baseline.

The supported product content width is at least 12 terminal cells; smaller top-level requests use
that minimum in both renderers. Nested containers reduce or omit decoration before leaving fewer
than two content cells, so CJK and emoji graphemes remain intact. Oversized tokens, including runs
of hyphens, are hard-wrapped without discarding text or ANSI styles. Table columns use a shared
width ceiling found by binary search, preserving a two-cell minimum and stable tie-breaking;
allocation costs O(columns * log(maximum natural column width)), rather than one scan per removed cell.
Font shape, host contrast,
and mouse selection/scroll behavior still require checks in the target terminal; unit tests do not
establish those visual acceptance results.

## 构建与启动

```powershell
java -version # 必须是 Java 21
.\mvnw.cmd -pl :haifa-agent-cli -am package

$jar = ".\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0.jar"
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
11. 用 Stub/Fake Tool 走通 requested → started → succeeded/failed/cancelled，确认同一 Tool
    只更新一张卡片，正常完成/EXITED 折叠行严格一行并带 `✓` 状态符号、工具名徽标、耗时和右端 `›`；
    `exit 1` 仍为 `✓` 且只显示为弱化事实，真实失败保留最多两行诊断；`/mouse on` 时点击条目可展开/收起，
    完成文本选择后不触发展开，`/mouse off` 时 Ctrl+O 仍可折叠/展开最近项；Run 终态确认追加 Run
    Summary 卡片。
12. 用 Stub/Fake Approval 检查结构化字段、approve/reject 回执与 editor 草稿恢复；审批期间输入只由
    Selector 消费。
13. Active Enter 后观察 Steer 从 accepted 保持到 applied；Alt+Enter 后观察持久 Follow-up Queue，
    Alt+Up 恢复且重启后不重复。
14. 用 PageUp 离开底部后产生新输出，确认 viewport 不跳动且出现 `new output below`；PageDown
    回到底部后提示消失。普通鼠标拖拽确认由宿主终端原生高亮选区与复制，支持全屏各区域选择。
15. 分别粘贴带 bracketed-paste 标记和不带标记的多行文本，确认所有行停留在 Editor，末尾换行也不
    提交；随后单独按 Enter 才提交一次。
16. PageUp 回翻后直接提交新消息，确认新一轮自动回到底部且不显示陈旧的 `new output below`；模型交互
    显示 `THINKING (1s)`，工具开始后显示 `WORKING (1s) · <tool title>`（如 `Run Command`），工具完成并返回模型后重新显示
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

## Clarification input

`ask_clarification` surfaces in the terminal as a `Clarification · <title>` transcript card whose body lists every
question with its options. Space only marks an option (multi-choice `[ ]` ↔ `[✓]`, single choice `( )` ↔ `(✓)`, both U+2713)
and never submits; Enter confirms the current
question, which submits the marked options and moves on to the next one. "Type your own answer" hands the composer to
the free-text lane while the card stays pending. The answer is submitted as one part per question
with format `clarification-answer.v1`, so the Runtime validates it like any other interaction input. "Cancel"
answers with the `cancel` action, which resumes the run without an answer instead of cancelling it.
