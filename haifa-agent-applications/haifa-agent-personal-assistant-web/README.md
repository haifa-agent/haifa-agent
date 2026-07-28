# Haifa Personal Assistant Web

Personal Assistant 的前端交付物与后端 HTTP API 契约草案。当前阶段只实现浏览器端，并用内存 Mock
提供可操作的完整体验；不包含 Java/Spring 后端，也不依赖仍在开发中的
`feat-sdk-product-foundation` 分支。

## 本期范围

- 会话创建、搜索、选择、重命名和归档；
- 主对话、运行状态、步骤进度与用户可理解的活动摘要；
- 运行中 Follow-up Queue 与 Steer 两种消息语义；
- 高风险动作的一次性确认，以及信息补充交互；
- 产品偏好、已确认长期记忆、记忆候选逐条确认；
- 交付物安全预览和浏览器下载；
- 会话级 Token 输入、输出、总计、缓存读取和提供方实报覆盖情况；
- 桌面三栏布局与移动端抽屉布局。

本期不包含登录、文件上传、语音、Deep Research、Sources/Evidence、内部 Graph、原始事件 JSON
或服务端诊断界面。

## 运行

要求 Node.js 22 或更高版本。

```powershell
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:20000/`。Vite 使用 `strictPort`，如果 20000 已被占用会直接失败，
不会悄悄切换到其他端口。

## 验证

```powershell
npm run contract:check
npm run typecheck
npm test
npm run build
```

## 后端边界

[`api/personal-assistant-openapi.yaml`](api/personal-assistant-openapi.yaml) 是设计契约，不代表后端已
实现。前端当前通过 `MockPersonalAssistantClient` 获取同形数据。待 SDK Product/Session/Memory/
Artifact 基建稳定后，再由应用服务器实现该契约并替换 Mock 客户端。

契约使用 `http://127.0.0.1:20000` 作为本地默认地址，并保持以下语义：

- 调用者身份由可信服务端上下文解析，不接受浏览器提交 Principal/Tenant；
- 一个 Conversation 同时最多一个活动 Run；
- Follow-up Queue 与 Steer 是不同命令；
- Memory Candidate 必须由人确认后才能成为长期记忆；
- Artifact 只暴露逻辑 ID 与版本，不暴露服务器路径；
- Token Usage 只累计模型提供方返回的 usage，不使用文本长度估算；
- 写命令带幂等键，竞争更新使用 revision/`If-Match`。
