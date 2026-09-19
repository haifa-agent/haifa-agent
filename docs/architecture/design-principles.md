# 证据驱动的设计原则

Haifa Agent 刻意把“Enterprise-grade”“Auditable”“Recoverable”“Extensible”看成需要证据支撑的结果，而不是自动增加 Framework Layer 的建模指令。

这些原则用于约束新的公共抽象。

## 从具体 Failure 或 Product Promise 出发

只有存在明确理由时，才增加 Durable State、State Machine、SPI 或 Shared Domain Type，例如：

- 已经发生或可复现的 Failure，简单实现无法安全处理；
- 产品明确承诺跨 Restart / Retry / Approval / Recovery；
- 某个 Security Invariant 必须在所有调用点一致执行；
- 多个真实 Consumer 需要完全相同的语义。

“以后可能有用”本身不够。

## 只持久化 Authoritative Fact

Recovery 不等于复制所有 Transient Object。

优先保留唯一 Authoritative Fact Source，再从它重建 Transient View。

重复 Persistence 往往只会增加事实冲突、Migration 与 Compatibility 成本。

## Product Semantics 留在 Product

Coding Agent 使用的能力，不会自动因此属于 Runtime。

只有低层真正拥有 Invariant，或多个 Consumer 已经证明稳定共享语义时，才应该向下抽取。

## 优先 Explicit Composition

少量 Explicit Typed Component 的 Builder，通常比 Generic Capability Resolution Engine 更容易理解、测试和删除。

只有产品确实需要 Dynamic Discovery 时，才引入 Dynamic Discovery。

## Safety 不等于 Complexity

真正必要的 Safety Boundary 仍然必须保持硬约束：

- exact-target Approval；
- Secret Isolation；
- Unknown Side-effect Protection；
- Idempotency；
- Cancellation 与 Budget Enforcement。

但这些要求并不自动推出完整 IAM Platform、Universal Audit Event Sourcing、Distributed Coordinator 或 Capability Marketplace。

## 为删除而设计

早期抽象如果证据消失，应尽量容易删除。

在引入长期 Public Type、Persistence Format 与 Compatibility Obligation 之前，优先使用 Product-local、Bounded、可替换的机制。

## 文档规则

公开文档只描述当前支持的行为。

历史 Architecture Plan、Prompt、Implementation Report 与 Retrospective 可以保留在内部工程文档，但不能静默演变成 Public Contract。
