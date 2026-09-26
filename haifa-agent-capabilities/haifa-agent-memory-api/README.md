# Haifa Agent Memory API

纯 Java 的直接 Memory 契约：

- `MemoryService`：`put` / `update(id, expectedRevision, content)` / `delete(id, expectedRevision)` / `find` /
  `list(MemoryQuery)` / `clear(scope)`，每次调用都以可信 `MemoryActor`（tenant + principal）约束，读取或写入他人
  Scope 一律 `MEMORY_UNAVAILABLE`；
- `MemoryScope`：`USER`（目标即 owner）/ `AGENT`（Agent Definition id）/ `SESSION`，均绑定 tenant 与 owner；
- `Memory` 只表示有效记忆，删除后不再返回；`revision` 是 CAS 令牌；可选 `MemorySourceRef` 记录来源；
- `MemoryDraft.observedAt`：异步捕获的来源观察时间，早于或等于 scope 清空水位线或同 subject 删除时间的写入以
  `MEMORY_WRITE_STALE` 拒绝，防止清空/删除后复活；
- `MemoryRepository`：`MemoryService` 背后的可信持久化端口，每个方法各自原子；
- `MemoryRetriever.contextFor(MemoryContextRequest)`：按可信 Run 身份读取 USER/AGENT/SESSION 三个桶的有界片段；
  `onlyWhen(predicate)` 与 `none()` 用于按 Run 或 Agent 关闭召回。

不提供 Candidate 审批、生命周期状态机、Conflict、Retention、Evidence 或 Memory 专用 Audit。
