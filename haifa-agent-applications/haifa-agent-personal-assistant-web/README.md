# Haifa Personal Assistant Web

Personal Assistant 的独立 React Web 部署单元。它只消费
`haifa-agent-personal-assistant-server` 发布的 `/api/v1` HTTP/WebFlux SSE 契约，不参与 Server
JAR 的构建或静态资源打包。

## 能力

- 新建、搜索、选择、重命名、归档和恢复 Conversation；
- Turn 历史、提交消息、SSE 回复、断线后 Snapshot 重取和停止 Run；
- 对话正文的 Markdown、代码块、表格和 KaTeX/LaTeX 公式渲染；
- Clarification/Approval 的显式结构化回复；
- Tool、Skill、MCP 的安全 Activity 投影，不展示原始参数、结果、路径或协议 JSON；
- Memory Candidate 确认/拒绝、Memory 查看/停用；
- 最终 Run 的后端权威 Token Usage；
- 桌面三栏布局和移动端互斥抽屉。

生产代码没有 Mock Client、Fixture fallback、Follow-up/Steer、Preference 编辑、复杂进度投影或
Deep Research 界面。

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
