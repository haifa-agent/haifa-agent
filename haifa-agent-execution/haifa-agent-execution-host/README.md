# Haifa Agent Execution Host

Execution 的窄本机 Adapter：当前只负责 Host OS detection、可信脚本 runtime executable 解析和基于 NIO
WatchService 的本机 Workspace 变化观察。它不启动进程；进程与隔离仍由具体 Sandbox Provider 负责。

`execution-core` 只保留 deployment-neutral registry、port、Broker、Policy/Sandbox 协调和 Tool 语义。
CLI、PA 以及任何直接组装本机 runtime 的产品边界必须显式依赖本模块；remote execution 路径不得传递
获得它。
