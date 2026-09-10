# Haifa Agent Autonomous Delivery (最小能力防退化评测)

`haifa-agent-autonomous-delivery` 是 Haifa Agent 四层测试体系（fixtures / integration / critical-path / autonomous-delivery）中的第 4 层。

## 模块定位与职责边界

1. **最小能力防退化评测**：专注内部核心能力阶梯回归，确保 Agent 在单点修复、局部功能、跨模块协调等阶段性能力上不退化；
2. **不承担业界完整 Benchmark 平台职责**：学术/业界 Standard Benchmark（如 SWE-bench Verified、aider-polyglot 等）统一由独立工程 `haifa-agent-evals` 承担；
3. **能力阶梯设计**：
   - 规划 6 个难度等级（L1 单点修复 ～ L6 跨模块复杂重构与集成）+ 2 类正交变体（执行失败自恢复、高覆盖防退化）；
   - 难度分布呈金字塔型：L1~L4 基础能力占 70%，L5~L6 复杂能力占 30%；
   - 详细规范请参阅：
     - 本模块 [`AUTONOMOUS_DELIVERY_LADDER_SPEC.md`](AUTONOMOUS_DELIVERY_LADDER_SPEC.md)
     - 架构设计文档 [`docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md`](../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md)

## 当前状态

本阶段按重构规划设立标准模块骨架与占位规范；历史 26 题 fixture 素材安全保留在 `haifa-agent-test-fixtures` 中，新阶梯题库在后续迭代中逐步置换录入。
