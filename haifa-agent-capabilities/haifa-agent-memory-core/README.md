# Haifa Agent Memory Core

`DefaultMemoryService` 当前只允许人工审批；策略即使报告可自动批准，Candidate 仍保持
`PENDING`。审批、拒绝、修订、失效和版本替代通过 `MemoryUnitOfWork` 原子提交，并在提交成功后
失效派生选择缓存。Retriever 只使用授权后的有界 ACTIVE 查询，不遍历全库。

冲突只检测并 fail closed；`resolveConflict` 以及 Expiry/Purge/Tombstone 管理保留为延期兼容入口，
生产 SQLite Provider 会明确拒绝这些操作。

Framework-neutral memory governance implementation with deterministic classification, review,
deduplication, conflict resolution, expiry, purge tombstones, authorization-first retrieval, and an
in-memory repository for tests and local runtime assembly.
