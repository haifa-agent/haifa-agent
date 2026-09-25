# 错误处理

Haifa Agent 尽量暴露稳定的 Error Semantics，同时避免通过 Public API 泄露 Provider Payload、Credential、Prompt 或 Internal Stack Trace。

## 同步 Failure 与 Run Failure

Run 成功接受之前发生的失败属于 API / Command Failure。

异步执行过程中发生的失败，会作为 Run Terminal State 与结构化 Agent Error 保存。

调用方应使用稳定 Error Code、Category / Retryability Metadata 与 Diagnostic ID，而不是解析英文 Error Message。

## Retryability

Retryable 不等于“总是可以重新执行”。

Runtime Retry Policy 还会判断一个 Physical Call 是否真的可以安全 Replay。

Authentication Error、Invalid Request、Cancellation、Context-too-long，以及已经产生不安全 Partial Effect 的请求，都不会被盲目重放。

有 Side Effect 的 Tool 如果 Outcome Unknown，也不会被转换成普通 Automatic Retry。

## Provider Error

Provider Integration 会把已知 Protocol Failure 归一化成有界、Provider-neutral Error。

Raw Response Body 与 Secret 不应进入 Application Client 或普通 Log。

## Diagnostics

使用 Diagnostic ID，把对外安全 Failure 与内部可信 Diagnostics 关联起来。

Public Diagnostics 有意避免包含：

- 完整 Prompt；
- Model Reasoning；
- 未明确确认安全且有界的 Tool Arguments / Results；
- Credential；
- Raw Provider Payload；
- 可能含用户数据的任意 Exception Message。
