# Haifa Coding Terminal 开发约束

本文件适用于 `haifa-agent-coding-terminal` 模块。除仓库根目录 `AGENTS.md` 外，修改本模块时必须同时遵守以下规则。

## UI 线程与后台任务

- UI 线程只负责接收输入、执行纯状态归约和渲染。不得在 UI 线程执行 Runtime、SQLite、文件系统、网络、模型、进程或其他可能阻塞的 I/O。
- 不得把“Reducer 和渲染必须串行”扩大为“Controller 的全部工作必须同步执行”。需要访问 `CodingSessionClient` 或外部资源的操作必须建模为后台 Effect/Command；后台完成后，将不可变结果消息投递回 UI 队列，再由 UI 线程归约状态。
- 用户提交操作后，必须先完成乐观 UI 状态变化并允许下一帧立即渲染，再启动后台工作。例如审批应立即关闭或禁用 Selector、显示 `Approving`；取消应立即显示 `Cancelling`；命令执行应立即显示 `Starting` 或等价状态。
- Approval、Shell、Slash Command、Session 操作、reconcile、事件重放/重订阅、Cursor 持久化和 Workspace 路径发现均受同一规则约束，不得只为普通消息提交提供后台执行路径。
- 后台任务必须按延迟敏感度分流：Approval、Cancel、Interrupt 进入有界 CONTROL 通道；普通交互进入 INTERACTIVE 通道；Cursor、reconcile 和订阅清理进入 MAINTENANCE 通道。CONTROL 不得执行完整 Session 重载、历史重放或订阅重建。
- Runtime 回调只能向有界队列投递 Action/Message，不得直接修改 UI 状态或触发渲染。UI 线程每次只消费有界数量的消息，避免事件洪峰饿死输入和绘制。
- `update`、Reducer 和 View 必须保持无阻塞；不得调用 `Future.get/join`、等待锁、轮询休眠或同步等待后台任务。

## 状态与恢复

- 乐观状态只表达“请求已提交”，权威结果仍以 Runtime/产品事件为准。后台失败必须通过结果消息回投，并恢复为明确、可重试的稳定状态。
- 正常事件不得触发全量 reconcile、历史重放或重订阅。只有队列溢出、订阅关闭、版本冲突或明确恢复场景才允许后台 reconcile；完成后一次性回投快照。
- Pending/Requested Interaction 只允许按 Run 查询单个权威 `InteractionView`；不得为了生成审批 Selector 调用完整 Session reconcile。
- 当权威 Interaction 事件离开 Pending 状态时，Reducer 必须关闭或废弃对应 Selector，不能依赖发起响应的本地调用路径清理 UI。

## 验证要求

- 涉及交互的测试必须使用可控阻塞的 Fake Client，证明后台调用尚未返回时，按键后的下一次 `update/view` 已显示即时反馈且仍可处理 Resize、Escape/Ctrl+C 和 Runtime 事件。
- Approval、Shell、Slash Command、Session 操作、reconcile 和 `@path` 补全均需覆盖“慢调用不阻塞 UI”测试；只验证最终状态不算通过。
- 不允许通过缩短轮询间隔、提高 SQLite `busy_timeout` 或增加线程内超时掩盖 UI 阻塞。
