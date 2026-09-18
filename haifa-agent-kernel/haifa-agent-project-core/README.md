# Haifa Agent Project Core

Project API 的默认纯 Java 实现：领域服务、Parser、Diff/Patch/Snapshot 算法，以及 InMemory Store、Ledger
和 Workspace write lease。实现 package 使用 `io.haifa.agent.project.core..`，不与 API split package。

本模块只依赖 `project-api` 和低层 Common/Core，不使用 `java.nio.file`、Host discovery、Spring、数据库、
Provider SDK、Runtime 或 Execution。真实文件系统访问由 `project-host` 通过 Project API ports 完成。
