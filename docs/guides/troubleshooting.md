# 故障排查

## Starter 提示 DEEPSEEK_API_KEY 未配置

安全默认 Starter 在 build / create 时就要求该环境变量存在。

macOS / Linux：

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

Windows PowerShell：

~~~powershell
$env:DEEPSEEK_API_KEY = '<your-api-key>'
~~~

不要为了绕过检查把 Key 写进源码。

## Tool 已注册，但 Model 不能调用

依次检查：

1. Tool 是否真的注册进当前 Product Assembly；
2. Tool alias / name 是否被当前 ProductProfile / Frozen Binding 允许；
3. 所选 Model 是否支持 Tool Calling；
4. Input Schema 与 Tool Definition 是否有效；
5. Policy / Approval 是否允许这次精确 Request。

MCP Discovery 成功，并不代表发现的每个 Tool 都自动对 Run 可用。

## Command 已批准但仍然失败

Approval 只表示这次 Exact Request 获得授权。

Execution 仍可能因为 Workspace Authorization、Executable 缺失、cwd 非法、Timeout、Host Process Failure、Resource Limit 或 Cancellation 失败。

## Command 返回非零 Exit Code

Process Exit Code 本身就是 Command Result 的一部分。

很多开发工具会用非零 Code 表示具有业务含义的结果。

不要把所有非零 Exit Code 自动分类成 Runtime Exception；应结合 Bounded stdout / stderr 与 Command Semantics 判断。

## Crash 之后 Run 没有继续

执行 owner 异常丢失并不等于 Transparent Failover。

Runtime 会安全收敛 Abandoned Execution。

Intentional Pause / Interaction 使用另一套 Continuation Semantics。详见 [持久化与恢复](../core-components/persistence-and-recovery.md)。

## Browser / Client 看不到 Provider 细节

通常这是有意的安全边界。

Product API 暴露的是 Safe Model Metadata，而不是 Credential、Raw Endpoint、Provider Payload 或 Internal Snapshot Digest。

## Host Execution 无法访问某个 Path

Coding Agent 等 Host Product 使用显式 Workspace / Authorized Directory Boundary。

Attach 一个 READ Directory，不代表自动拥有 DEVELOP / Execution 权限。

## 需要进一步诊断

更底层行为以相邻模块 README 与测试为当前实现事实来源。可以从仓库根 README 的模块入口继续阅读。
