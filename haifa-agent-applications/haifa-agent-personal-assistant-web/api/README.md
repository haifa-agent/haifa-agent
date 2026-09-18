# Personal Assistant Web API Contract

本目录只定义 Personal Assistant Web MVP 的后端 API，不包含任何 Java、Spring、Controller 或
持久化实现。真实后端必须等待 HAIFA-ARCH-020 前置任务完成后再开发。

## 本地约定

- 默认同源地址：`http://127.0.0.1:20000`
- API Base：`/api/v1`
- 前端开发服务器与 Mock API 共用 `20000`，不额外占用端口
- 正式后端允许部署者覆盖端口，但示例、E2E 和默认配置统一从 `20000` 开始

## 冻结边界

1. Browser 不提交 Tenant、Principal、Reviewer 或 Product Profile；Server 从可信配置注入。
2. 所有写操作要求 `Idempotency-Key`；修改现有资源还要求 `If-Match` revision。
3. 普通输入在无活动 Run 时创建下一 Turn；有活动 Run 时进入 Follow-up Queue。
4. “用于当前任务”单独映射 Steer，不能与 Follow-up 混用。
5. Clarification 和 Approval 复用同一个 Interaction Response 边界。
6. Snapshot 是页面恢复基线；Cursor Page/SSE 只负责增量，不是事实源。
7. Memory Candidate 必须人工确认；修改正式 Memory 会创建新 Candidate。
8. Artifact 只通过逻辑 ID/Version 打开，API 不返回物理 Path 或 Blob Key。
9. Token Usage 是会话级累计值，只聚合模型提供方实报，不使用文本长度估算；同时返回模型调用数
   和实报调用数以表达覆盖率。
10. 未授权与不存在返回相同的 `RESOURCE_NOT_FOUND`。
11. 默认部署不注册任何 diagnostics path。

## 前端开发

当前前端使用 `MockPersonalAssistantClient` 实现相同的 TypeScript Port。后端可用后，只需要新增
HTTP Client 并保持 `PersonalAssistantClient` 接口，不应改写页面状态模型。

```powershell
npm install
npm run contract:check
npm run dev
```
