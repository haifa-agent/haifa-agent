# Haifa Agent Memory API

当前 Phase 2 公共契约提供：

- `PENDING -> revise(PENDING, revision+1) -> APPROVED/REJECTED` Candidate 生命周期；
- `ACTIVE/INVALIDATED` 正式 Memory、精确版本引用与 `REPLACED` 双向引用；
- 带 expected revision 和幂等键的 approve/reject/revise/invalidate；
- Candidate/Memory 有界游标分页，以及授权优先的 Retriever Port；
- 只写 Audit/幂等内部端口和可注入事务边界。

本模块仍为纯 Java。Conflict 管理、Expiry、Retention 执行、Purge、Tombstone、Audit 查询和
Artifact 生产持久化不属于当前实现。

Pure Java contracts for governed long-term memory. Candidates, approved immutable memory versions,
scope, evidence, review, conflicts, retention, purge, and retrieval are intentionally separate from
conversation summaries and Context assembly.
