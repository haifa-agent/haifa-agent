# Haifa Personal Assistant Web

## Model contract migration

The generated TypeScript contract carries exact binding/profile identity, closed controls, typed PA preferences, a
safe image-input white list (`Model.imageInput`), and a server-computed `selectionCompatibility` on the session
selection (`CURRENT | RESELECTION_REQUIRED | UNAVAILABLE`). The UI is driven entirely by this safe projection; it
never receives or echoes Profile version/digest, endpoint, dialect, or raw provider fields.

The unified “模型与连接” window (opened from the 模型 / 模型连接 top-bar buttons) has two tabs: 模型目录 shows
Provider groups with a connection badge (derived from `model-connections`, never faked), model cards with limits and
capabilities, a search box, and a 查看详情与设置 action; 账号连接 reuses the extracted `ModelConnectionTab`. The
`ModelDetailDrawer` renders execution limits, capabilities, the image IO profile (when the binding declares one),
response settings (response-mode, response-length, reasoning-effort, advanced connection) entirely from Server
controls, a re-confirmation warning when `selectionCompatibility` is not `CURRENT`, and a 恢复默认推荐/取消/确认并应用
footer. Applying a change is disabled while the Conversation has an active Run; the copy states the change takes
effect on the next new Run and never promises a deferred auto-apply it does not implement.

The `+` menu groups bindings by Provider and provider model, then renders dedicated response-mode, response-length,
reasoning-effort, and advanced API-style controls from Server-provided visibility, read-only state, allowed values,
defaults, and help text. It submits the exact selection atomically for a new Conversation or idle existing
Conversation; it does not infer provider capabilities or send raw options. Changing API style restores that binding's
reviewed defaults instead of carrying incompatible parameters across protocols.

Bindings reported as unavailable remain visible for diagnostics but are disabled. The picker displays the Server's
safe unavailable reason and never infers availability from Provider names or model IDs.

The `ModelConnectionTab` reads credential readiness from the separate `model-connections` API. It can start
browser-based ChatGPT/Codex login and, when the Server explicitly enables local compatibility, a separate
Antigravity login; it can also accept a masked API Key, show safe account/status fields, cancel an active
attempt, and log out. The Key exists only in component input state and is cleared after submit, cancel, or unmount; it
is never placed in URL state, local storage, reducer snapshots, or telemetry. A missing connection produces a
non-blocking startup notice—the model catalog remains independently visible.

The live Run card shows an evidence-based phase summary. It displays observed activity counts
without presenting them as a percentage; when the Server supplies an authoritative Plan, it shows
real Todo completion and the current or blocked step. Activity lifecycle events are merged by the
stable operation ID, and execution child records retain their parent Tool relationship.

Completed Deep Research reports remain portable Markdown artifacts. The Web reader hides Haifa
section markers, builds a local table of contents, maps task markers to user-facing Mission Task
details, and resolves `[[source-*]]` references against the delivery's `sourcesArtifactRef`.
Consecutive references share one stable numbered citation control. Citation details open in a
non-blocking evidence side panel with page title, publisher, date, source tier and verification
status; `Escape` closes this inner panel before the Mission workspace. Missing source records are
shown as unavailable without creating a link, `[unverified: ...]` markers become a user-facing
"待核实" badge, and internal task/source identifiers remain hidden. The same document reader is
embedded in a completed Mission workspace, so the report remains readable from the Mission's stable
URL without depending on a matching Conversation turn.

Each Mission has a stable `/missions/{missionId}?conversationId={conversationId}` URL. Direct visits,
refresh, Mission-list selection, and browser history restore the same Mission workspace.

Generic legacy Mission objectives such as "start deep research" are replaced in the interface with
the originating user research goal when it is available. Active planning, execution, and synthesis
states show a motion indicator, completed-task progress, and the current user-facing execution phase.
The current Mission Task reuses the safe Run activity projection to show live Model, Tool, Skill, and
MCP calls plus authoritative token usage without exposing prompts or raw arguments.

The ordinary Conversation composer defaults to chat with an 80-pixel outer control. Its leading `+`
menu exposes Deep Research, model selection, image upload and HTTPS image URL actions for image-capable models,
and audio upload for audio-capable models. Selecting Deep Research shows a removable one-shot mode chip instead of a
permanent mode switch.

## Native media composer behavior

For models that declare `IMAGE_UPLOAD_INPUT`, the composer exposes file upload and drag-and-drop. It exposes HTTPS
image URLs independently for models that declare `IMAGE_URL_INPUT`. Pending images render as compact thumbnails inside the
composer with a quick `分析媒体` action. Models that declare `AUDIO_INPUT` also accept WAV, MP3,
AIFF, AAC, OGG Vorbis, and FLAC uploads through the same menu and drag-and-drop surface. Image and
audio attachments share a maximum of four items per turn. The URL entry can be dismissed with its close button,
`Escape`, or an outside pointer action. Attachments belong only to the turn being submitted and
are cleared after a successful request, so a later turn never silently reuses them.

The right-side activity panel renders safe durable Model, Tool, Skill, and MCP events.
Model-call cards show the model, attempt coordinates, status, and terminal token or
normalized failure summary without prompt or response text. When an activity is added or
updated, the panel automatically scrolls to the latest event.

输入框上方的实时运行卡按优先级汇总当前 Run：审批或交互、失败或超时、SSE 重连、流式回答、
最新安全 Activity、Run 生命周期。卡片只使用现有安全投影，不展示原始 Prompt、工具参数、Provider
响应或虚构进度；普通活动可打开右侧详情，待处理状态会聚焦主消息流中的 Interaction 卡片。

会话标题区提供模型 Selector。新会话使用 Bootstrap 默认值或用户选择；已有会话仅在无活动 Run 时
调用带 `If-Match` 与幂等键的切换 API。页面只提交内部 Model ID。
消息输入框输入 `/` 会打开命令菜单；`/model`“选择模型”命令按 Provider、Model 两级展示 Bootstrap
返回的可用模型，并复用同一模型切换 API；`/deep-research <目标>` 与显式 Deep Research 模式只打开
复用的 Mission 创建草稿，不提交普通 Conversation Run。普通“调研一下”不会触发长任务路由。
当选中模型声明 `IMAGE_UPLOAD_INPUT` 或 `AUDIO_INPUT` 时，输入区通过同一个附件入口提供对应媒体的文件选择和
拖放；仅声明 `IMAGE_URL_INPUT` 的模型显示 HTTPS URL 入口。图片与音频合计最多四个，上传成功后只把 Server
返回的 opaque id 放入 Conversation 请求。已发送的上传图片通过 Server 的本机只读端点重新校验后显示缩略图，
界面不显示 opaque id；音频仍只显示安全文件摘要。

Personal Assistant 的独立 React Web 部署单元。它只消费
`haifa-agent-personal-assistant-server` 发布的 `/api/v1` HTTP/WebFlux SSE 契约，不参与 Server
JAR 的构建或静态资源打包。

## 能力

- 新建、搜索、选择、重命名、归档和恢复 Conversation；
- 显式 Mission 入口、列表、创建、确认前整体计划替换/重新生成、确认、取消、Snapshot polling、
  Dispatcher/Task 执行摘要、blocked Task 重试、Task Run Interaction 回复和 Conversation 摘要卡片；
- Mission 使用全屏三栏工作台；左侧可搜索和选择 Mission，中间浏览完整计划，点击任务后在右侧查看
  目标、验收标准、依赖和状态；等待确认时提供结构化“适度调整计划”，支持在 Mission 约束内修改
  任务标题、目标、验收标准、顺序和早期依赖，并增删任务；保存生成新 Plan Revision，确认后冻结。
  编辑器不展示或允许修改内部 Task 类型、Skill 或结果 Schema；右侧详情可以独立关闭，窄屏使用
  Mission、报告和详情三个互斥视图，避免三栏纵向堆叠和多重滚动；
- 显式 Standard / Deep Research 模式；创建页默认只要求目标，并准备领域无关、可编辑的执行/研究默认值，
  验收标准和完整 Research Brief 按需展开编辑；提交时把受支持的相对时间冻结为明确 UTC 日期区间。
  `pa.research-delivery/v2` 提供正常/部分/降级/失败语义、完整 Markdown 报告查看/复制/下载、降级原因、
  受影响 Task、可信 Evidence Summary、成本指标、来源链接和五类交付文件；主对话只展示 Assistant
  Mission 交付卡片与可信度警告，完整报告保留在 Mission 工作台，Markdown 下载通过浏览器 Blob
  触发真实文件保存；
- Conversation Composer 提供普通对话 / Deep Research 显式模式和 `/deep-research <目标>` 命令；路由前
  拒绝静默丢弃附件，活动 Mission 冲突时打开当前 Mission，Bootstrap 未声明 `web-research` 时在计划生成前
  禁用入口。打开草稿本身不调用普通消息、Mission 创建、Planner、Web Search/Fetch 或付费模型；
- 当前 Conversation 通过 URL `conversationId` 查询参数持久化，刷新及浏览器前进/后退会恢复对应会话；
- 每个 Mission 使用 `/missions/{missionId}` 稳定 URL，并保留所属 Conversation 参数；刷新、直接访问及
  浏览器前进/后退均可恢复对应 Mission 工作台；
- 完成态优先展示结论和完整报告，研究说明、验收标准、执行进度与计划默认收进“研究过程”；来源清单
  使用网页标题而非 URL 作为名称，并区分政府/监管、已标注发布方与一般网页来源；通用 Markdown
  来源清单隐藏内部来源 ID，URL 可在浏览器新标签页打开；主对话中的研究报告可从来源清单恢复
  `[[source-*]]` 引用上下文，使用相同的编号引用和证据侧栏；
- Mission 列表支持状态筛选、更新时间/进度排序和进度条；运行状态、Task 状态及当前任务统一使用用户
  语言，Dispatcher、内部 Task ID 等运行细节仅进入折叠的技术详情；
- `pa.research-delivery/v2` 的五类交付文件使用可读名称，点击后在第三栏读取 Markdown 或结构化 JSON，
  不再新开原始 Markdown 页面；存在未决问题或待核实结论时可预填一个后续 Mission；
- Turn 历史、提交消息、SSE 回复、断线后 Snapshot 重取和停止 Run；
- 输入框 `/` 命令菜单，以及按模型厂商、模型两级完成的新会话选择或已有会话切换；
- 图片模型下的 HTTPS URL、PNG/JPEG/WEBP/非动画 GIF 文件选择与拖放上传；
- 完成态 Assistant 回答底部的 2～3 个推荐问题；点击后按普通新消息提交，快问快答、简单计算或
  推荐生成失败时不展示；
- 对话正文的 Markdown、代码块、表格和 KaTeX/LaTeX 公式渲染；
- 助手完整 Markdown 回答和独立代码块的复制按钮，复制成功后显示图标反馈；
- Clarification/Approval 的显式结构化回复；审批只在主对话区展示，长文本或代码默认预览并支持展开全文；
- Tool、Skill、MCP 的安全 Activity 投影，不展示原始参数、结果、路径或协议 JSON；
- Memory Candidate 确认/拒绝、Memory 查看/停用；
- 最终 Run 的后端权威 Token Usage；
- 命令/脚本 exact approval 的完整可读正文、调用摘要和高风险警示；
- 执行 REQUESTED / STARTED / SUCCEEDED / FAILED / TIMED_OUT 安全活动及有界结果摘要；
- 桌面三栏布局和移动端互斥抽屉。

Mission 同步状态显式区分 loading、current、syncing、stale、recovering 和 offline。浏览器离线时保留
最后一次安全快照、停止把陈旧数据呈现为 current，并在重新联网后自动恢复 polling；终态变化通过
`aria-live` 宣告。Mission 全屏工作台支持 `Escape` 关闭、键盘焦点约束和关闭后的焦点恢复，交互控件具有
可见焦点样式，桌面与移动布局使用同一套状态和操作语义。同步 Planner 命令使用与 Server 120 秒
规划窗口匹配的 130 秒客户端上限；普通请求仍使用 12 秒上限。失败 Mission 以可理解文案显示规划依赖、
规划容量或执行资源限制，稳定 blocker code 只保留在折叠的技术详情中。

生产代码没有 Mock Client、Fixture fallback、Follow-up/Steer、Preference 编辑、复杂进度投影或
Mission 页面只显示安全 Dispatcher 状态与最新 Attempt 摘要，不暴露内部 payload 或 Skill 绑定；
Deep Research 的固定产品 Skill 由 Server 根据已选 Mission 模式装配，浏览器不展示或编辑 Skill 别名、
路径、版本或摘要；
Pause/Resume、Verifier、Repair 和浏览器指定 Skill 的入口仍未提供。

## 本机 Run Diagnostics

同一独立 Web 部署单元提供两个 Admin 只读视图：

- `/admin/` 按 Session 选择一次 Run，并以可折叠树查看冻结配置引用、Attempt、Step、Tool/MCP、
  Checkpoint、Interaction、Skill、Runtime Event、安全错误详情和诊断编号。失败 Run 会自动聚焦
  到持久事实中的最后一个失败节点；Prompt、Tool 参数/结果和其他敏感正文始终隐藏。
- `/admin/capabilities` 按 Tool、MCP Server、Skill 浏览产品组装时冻结的注册清单，可搜索并查看定义、
  策略、Schema、资源、协议、导入关系和摘要；不展示凭据值或临时连接状态。

Admin 是独立入口：普通 Personal Assistant 页面没有 Admin 链接、导航、按钮、capability 或 Client
接口，两个页面按 URL 动态加载，普通页面不会加载 Admin 应用代码。Admin 只读调用
`http://127.0.0.1:20001/v1/admin`，只展示安全执行元数据，不展示 Prompt、Tool 参数/结果、原始
Provider 内容或 Stack Trace；该入口仍只允许在受信本机环境中使用。需要覆盖地址时单独设置：

```powershell
$env:VITE_PERSONAL_ASSISTANT_ADMIN_API_BASE_URL='http://127.0.0.1:20001/v1/admin'
```

## 契约

事实链：

```text
Server web.v1 OpenAPI
-> scripts/generate-contract.mjs
-> src/api/generated.ts
-> thin direct-browser client
```

更新 Server OpenAPI 后运行：

```powershell
npm run contract:generate
npm run contract:check
```

`contract:check` 会拒绝过期 TypeScript DTO、错误端口、缺少幂等键的写接口和已延期操作。

失败 Run 使用 OpenAPI 生成的 `ExecutionError` 渲染稳定 code、安全 message 和可复制的
diagnosticId；重试或人工确认提示基于 code/retryability，不解析英文文案。普通聊天界面不显示
Java 类型、堆栈、Provider Body 或内部路径。

Run SSE 的 durable 与 transient 事件分别去重：`answer.delta` 实时追加当前 Generation 草稿，
`answer.failed`/`answer.superseded`/新 `answer.started` 会清除旧草稿，避免重试拼接；完整回复提交后由
Turns 中的权威 `session_message` 替换草稿。客户端重连发送复合 `Last-Event-ID`，服务重启时只重置
transient cursor。`run.status` 只刷新 Run，`interaction.status` 只刷新 Interaction；带有安全 Activity
投影的 `activity.committed` 直接合并到本地状态，缺少投影时才重取 Activities。Interaction 请求使用
generation 门禁丢弃旧响应，审批卡片不会等待 Activities，也不会被较早返回的空响应覆盖。

When a Run is waiting for approval or interaction and its interaction snapshot cannot be loaded, the page displays
an explicit blocking error instead of silently hiding the approval controls.

## 本地开发

要求 Node.js 22.x、npm 10.x。

```powershell
npm ci
npm run dev
```

Vite 固定使用 `http://127.0.0.1:20000`。浏览器直接请求独立 Server
`http://127.0.0.1:20001/api/v1`；Vite 不代理 `/api`。

## 独立部署

Web 使用前端生态的 `serve` 独立提供 `dist` 和 SPA history fallback，固定监听
`127.0.0.1:20000`。先启动 `127.0.0.1:20001` 的 Server，再在本目录执行：

```powershell
npm ci
$env:VITE_PERSONAL_ASSISTANT_API_BASE_URL='http://127.0.0.1:20001/api/v1'
npm run build
npm run serve
```

浏览器访问 `http://127.0.0.1:20000`。API 地址在构建时由
`VITE_PERSONAL_ASSISTANT_API_BASE_URL` 冻结；未设置时使用上述 loopback 默认值。Server 只允许
`127.0.0.1`、`localhost` 和 `::1` 的 `20000` Origin，不启用浏览器凭据，写请求仍必须携带
`X-Haifa-CSRF`、`Idempotency-Key` 和需要的 `If-Match`。SSE 由浏览器直接连接 WebFlux。

当前 Personal Assistant Server 是 loopback-only 产品边界；把 Web 或 API 部署到远程主机不属于
本方案，不能通过放宽 CORS 代替认证、TLS 和可信 Caller 设计。

## 验证

```powershell
npm run lint
npm run contract:check
npm run typecheck
npm test
npm run build
npm run bundle:check
npm run deploy:check
```

`dist/`、`node_modules/` 和 TypeScript 增量产物均不提交。
