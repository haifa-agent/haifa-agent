# Haifa Agent Git Adapter

提供 Provider-neutral Git 仓库查询模型，以及经 `ExecutionBroker` 执行的 inspect、status、diff 只读适配器。接口只接受 Workspace 逻辑引用，不暴露宿主机仓库路径。

本阶段不执行 fetch、commit、push、reset、clean 或自动 merge。Worktree 合并必须先验证父 Workspace revision 与 base commit，再显式应用已经验证的 Patch。
