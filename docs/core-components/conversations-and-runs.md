# Conversation 与 Run

SDK Conversation API 与 Runtime Run API 解决的是两个不同层次的问题。

## Conversation

Conversation 是面向产品的多轮容器。

它使用 Core Session identity 作为权威身份，并保存 display name、时间、revision 等轻量展示/索引元数据。

Session、Run、Turn 以及执行状态仍由 Runtime 负责。

一个 Conversation 随时间可以包含多个 Run，但正常用户路径下，同一个 Conversation 同时最多只有一个活动 Run。

## Run

Run 表示某个 Agent Definition 在一份冻结配置下的一次执行。

新的用户 Turn 通常创建新的 Run；从有意暂停恢复时，则继续原 Run。

典型 Lifecycle：

~~~text
PENDING -> QUEUED -> RUNNING
RUNNING -> WAITING_INTERACTION -> RUNNING
RUNNING -> WAITING_APPROVAL -> RUNNING
RUNNING -> SUSPENDING -> SUSPENDED -> RUNNING
RUNNING -> COMPLETING -> COMPLETED
non-terminal -> FAILED | CANCELLED | TIMEOUT
~~~

合法状态转换的权威来源是 Core Domain Model。Runtime 负责协调这些行为，但不能再维护第二份 Lifecycle 表。

## Asynchronous Start

Runtime start 在 Run 被接受、持久化并提交执行后返回；返回时真实工作未必已经完成。

需要终态的调用方必须显式 await 或 observe Run。

客户端等待超时，也不等于 Run 自动被取消。

## Attempt

一个逻辑 Run 可以存在多个物理 Execution Attempt。

正常 Resume 可以为同一个 Run 创建新的 Attempt。

如果执行中的 owner 异常丢失，系统不会假装发生了透明 Failover；Recovery 会安全收敛被中断的执行，而不是猜测外部 Side Effect 是否已经发生。

## Interaction

Clarification 与 Approval 使用持久 Interaction State。

Human waiting 与 Active execution time 分开计算，避免用户只是花时间审批就意外耗尽 Run 的活动执行预算。

## Idempotency

Runtime start 与 Conversation write command 使用 caller-scoped idempotency 与 request digest。

同一个 Idempotency Key 如果被用于不同请求，会 fail closed，而不是静默返回另一份不相关结果。
