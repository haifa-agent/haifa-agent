# Haifa Agent Memory API

当前 Phase 2 公共契约提供：

- `PENDING -> revise(PENDING, revision+1) -> APPROVED/REJECTED` Candidate 生命周期；
- `ACTIVE/INVALIDATED` 正式 Memory、精确版本引用与 `REPLACED` 双向引用；
- 带 expected revision 和幂等键的 approve/reject/revise/invalidate；
- Candidate/Memory 有界游标分页，以及授权优先的 Retriever Port；
- 只写 Audit/幂等内部端口和可注入事务边界。

本模块仍为纯 Java，不提供 Conflict 管理、Expiry、Purge 或 Tombstone 入口；`MemoryRetentionPolicy`
只随记录保存，不驱动过期执行。Audit 查询和 Artifact 生产持久化不属于当前实现。

Pure Java contracts for governed long-term memory. Candidates, approved immutable memory versions,
scope, evidence, review, retention, and retrieval are intentionally separate from
conversation summaries and Context assembly.
