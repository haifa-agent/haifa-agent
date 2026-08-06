# Haifa Personal Assistant Web

The live Run card shows an evidence-based phase summary. It displays observed activity counts
without presenting them as a percentage; when the Server supplies an authoritative Plan, it shows
real Todo completion and the current or blocked step. Activity lifecycle events are merged by the
stable operation ID, and execution child records retain their parent Tool relationship.

## Image composer behavior

For models that declare `IMAGE_INPUT`, the composer exposes one `+` menu for file upload,
HTTPS image URLs, and drag-and-drop. Pending images render as compact thumbnails inside the
composer with a quick `解释图片` action. The URL entry can be dismissed with its close button,
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
消息输入框输入 `/` 会打开命令菜单；当前 `/model`“选择模型”命令按 Provider、Model 两级展示
Bootstrap 返回的可用模型，并复用同一模型切换 API。
当选中模型声明 `IMAGE_INPUT` 时，输入区通过单一“添加图片”入口提供 HTTPS 图片 URL、文件选择和拖放；
待发送附件按顺序显示且最多四张，上传成功后只把 Server 返回的 opaque image id 放入 Conversation 请求。
已发送图片随用户 Turn 显示在主对话中：外部 URL 显示缩略图，本地上传显示不暴露 opaque id 的附件卡片。
当前不提供本地图片二进制预览或下载端点。

Personal Assistant 的独立 React Web 部署单元。它只消费
`haifa-agent-personal-assistant-server` 发布的 `/api/v1` HTTP/WebFlux SSE 契约，不参与 Server
JAR 的构建或静态资源打包。

## 能力

- 新建、搜索、选择、重命名、归档和恢复 Conversation；
- 当前 Conversation 通过 URL `conversationId` 查询参数持久化，刷新及浏览器前进/后退会恢复对应会话；
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

生产代码没有 Mock Client、Fixture fallback、Follow-up/Steer、Preference 编辑、复杂进度投影或
Deep Research 界面。

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
