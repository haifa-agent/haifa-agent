# Haifa Agent Project Host

Project 的本机文件系统 Adapter。只有本模块允许解析 `WorkspaceLocationRef` 为真实 `Path`、执行 real-path、
symlink/Junction 防逃逸校验，以及访问本机文件、目录和 Git repository boundary。

Host 类型保留在 `io.haifa.agent.project.hostworkspace..`，不进入公共 Javadoc。公共 DTO、Store payload、
Tool schema 与模型输出不得包含真实宿主路径。
