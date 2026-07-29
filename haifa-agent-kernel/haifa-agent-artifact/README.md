# Haifa Agent Artifact

纯 Java 的显式发布物领域。Artifact 是不可变、可版本化、带 Provenance 的业务输出；它只引用内容寻址 payload，不等同于 Asset 或可变 Workspace File，也不依赖 Product、Project Provider 或存储框架。

当前最小纵切面提供开放字符串 `ArtifactType`、版本与关系、发布状态、内容寻址 payload、完整来源
元数据、Store Port 和线程安全 InMemory 实现。`haifa-agent-store-sqlite` 另提供受
`maximumPayloadBytes` 约束、可跨重启恢复的单机 metadata/BLOB 适配。

发布失败会释放本次 payload 引用；相同内容的已发布 Artifact 不会因后续失败被删除。当前
`ArtifactService` 仍是 payload 先写、metadata 后写并同步补偿的两步操作，进程在两步之间崩溃可能留下
孤儿 payload。Artifact 不会通过扫描 `outputs` 或其他目录自动产生，也不提供 staging/finalize、GC、
更新、撤销、删除、分享或外部对象存储。
