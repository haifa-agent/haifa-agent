# Haifa Agent Memory Core

`DefaultMemoryService` 当前只允许人工审批；策略即使报告可自动批准，Candidate 仍保持
`PENDING`。审批、拒绝、修订、失效和版本替代通过 `MemoryUnitOfWork` 原子提交，并在提交成功后
失效派生选择缓存。Retriever 只使用授权后的有界 ACTIVE 查询，不遍历全库。

同一 Scope、Kind、Subject 只允许一个 ACTIVE Memory：审批遇到未声明替换的 ACTIVE Memory 时以
`MEMORY_SUBJECT_CONFLICT` fail closed，替换必须显式携带 `replacesMemoryRef`；不提供冲突管理、Expiry、Purge 或 Tombstone 入口；`MemoryRetentionPolicy` 只随记录保存，不驱动
过期执行。

Framework-neutral memory governance implementation with deterministic classification, review,
deduplication, fail-closed subject conflicts, authorization-first retrieval, and an
in-memory repository for tests and local runtime assembly.
