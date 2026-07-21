# Haifa Agent Artifact

纯 Java 的显式发布物领域。Artifact 是不可变、可版本化、带 Provenance 的业务输出；它只引用内容寻址 payload，不等同于 Asset 或可变 Workspace File，也不依赖 Product、Project Provider 或存储框架。

当前最小纵切面提供开放字符串 `ArtifactType`、版本与关系、发布状态、内容寻址 payload、完整来源元数据、Store Port 和线程安全 InMemory 实现。发布失败会释放本次 payload 引用；相同内容的已发布 Artifact 不会因后续失败被删除。Artifact 不会通过扫描 `outputs` 或其他目录自动产生。
