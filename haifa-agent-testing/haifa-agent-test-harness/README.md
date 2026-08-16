# Haifa Agent Test Harness

`haifa-agent-test-harness` 是 Reactor 末端唯一的可执行测试控制面。它负责解析冻结计划、运行模式门禁、
预算授权、仓库外执行、进程收敛、验收和证据终结；产品模块不得依赖本模块。

公共 shaded JAR 只暴露两个动作：

```text
haifa-test plan --suite <id> --profile <id> --platform <id> --mode <dev|live|release>
haifa-test run --plan <execution-plan.json> [--approve-budget <amount>]
```

`plan` 生成不含 Secret 的 `ExecutionPlanDocument`，冻结 Suite、标准 Agent Client 装配摘要、Platform、
Fixture Package、两仓 Revision、预算和 Case 选择。`run` 重新解析当前状态并校验 Plan SHA；内部可以执行
多个步骤，但不再公开 Campaign、Phase 或 Gate 子命令。

统一生命周期为：

```text
Resolve -> Preflight -> Provision -> Execute -> Grade -> Finalize
```

运行模式只调整治理门禁，不改变 Case Oracle：`dev` 用于无凭据的确定性运行；`live` 要求预算批准、
Secret 预检、仓库外运行根和精确 Revision；`release` 额外执行完整资产台账校验和只读证据终结。

Critical Path 通过精确 Failsafe selector 串行执行，要求 XML 证明至少一个测试实际运行，且不能有失败、
错误或跳过。Autonomous Delivery 通过注入的公共 `CodingAgentClientFactory` 执行；具体模型、Provider、
Credential、Tool 和持久化装配留在最高层独立产品装配模块。Harness 主代码、测试和 Fixture 不包含
供应商专用命名或分支。

共享 Fixture 使用自包含 Package：

```text
fixtures/<id>/
  fixture.yaml
  workspace-or-cases/
  acceptance/
```

每个 Package 只登记一次并计算一个规范化内容摘要；Suite 只引用 `id + version`。Package 内普通文件
不再逐项登记到全局资产台账，越界路径和符号链接会被拒绝。

每个 Run/Repeat 只保留一个权威 `run-result.json`。较大内容进入 `attachments/`，并在结果中记录相对
路径、大小和 SHA-256；`secret-scan.json` 与 `manifest.sha256` 保留安全和完整性事实。旧 Projection、
旧结果 Schema 和历史兼容读取路径已删除。

CLI shaded JAR、参数解析、YAML、stdio、PTY/ConPTY 与退出码由独立 E2E/CLI Smoke 验证，不计入
Critical Path 或 Autonomous Delivery 的产品能力通过率。

约束：

- Live/Release 必须显式授权真实外部调用和预算；
- Credential 只通过环境引用注入，结果不得包含完整 Prompt、reasoning、原始供应商响应或真实 Host Path；
- Live/Release 运行根必须位于主仓、`docs` 和 `test-config` 之外；
- 超时后必须主动收敛 Maven、CLI、Tool 和子进程树；
- Testkit 仅保留轻量、确定性、无外部副作用的辅助能力；
- 本模块不作为生产发布制品部署。
