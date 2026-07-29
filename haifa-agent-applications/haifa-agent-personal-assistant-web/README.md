# Haifa Personal Assistant Web

Personal Assistant 的独立 React Web 部署单元。它只消费
`haifa-agent-personal-assistant-server` 发布的 `/api/v1` HTTP/WebFlux SSE 契约，不参与 Server
JAR 的构建或静态资源打包。

## 能力

- 新建、搜索、选择、重命名、归档和恢复 Conversation；
- 当前 Conversation 通过 URL `conversationId` 查询参数持久化，刷新及浏览器前进/后退会恢复对应会话；
- Turn 历史、提交消息、SSE 回复、断线后 Snapshot 重取和停止 Run；
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

- `/admin/` 按 Session 选择一次 Run，并以可折叠树查看冻结配置、Prompt/Message、Attempt、Step、
  Tool/MCP、Checkpoint、Interaction、Skill 和 Runtime Event。失败 Run 会自动聚焦到持久事实中
  最后一个失败节点，右侧展示该节点的完整内容。
- `/admin/capabilities` 按 Tool、MCP Server、Skill 浏览产品组装时冻结的注册清单，可搜索并查看定义、
  策略、Schema、资源、协议、导入关系和摘要；不展示凭据值或临时连接状态。

Admin 是独立入口：普通 Personal Assistant 页面没有 Admin 链接、导航、按钮、capability 或 Client
接口，两个页面按 URL 动态加载，普通页面不会加载 Admin 应用代码。Admin 只读调用
`http://127.0.0.1:20001/v1/admin`，并明确展示完整 Prompt、Tool 参数、结果与错误，因此只能在受信
本机环境中使用。需要覆盖地址时单独设置：

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

Run SSE 的 durable 与 transient 事件分别去重：`answer.delta` 实时追加当前 Generation 草稿，
`answer.failed`/`answer.superseded`/新 `answer.started` 会清除旧草稿，避免重试拼接；完整回复提交后由
Turns 中的权威 `session_message` 替换草稿。客户端重连发送复合 `Last-Event-ID`，服务重启时只重置
transient cursor。收到 `run.status`、`interaction.status` 或 `activity.committed` 时仍会立即重取权威
Snapshot，因此审批卡片、执行活动和终态不依赖手工刷新。

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
