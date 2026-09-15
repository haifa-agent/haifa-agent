# Haifa Agent Git Control Plane

本模块提供不向模型披露的只读 Git Adapter。`GitRevisionProbe.inspectHead` 经 `ExecutionBroker` 获取
仓库、HEAD、branch/ref 与 submodule 安全事实，供漂移门禁使用；
`ExecutionBrokerHostGitInspectionPort` 验证某个授权目录内的候选仓库 root；
`ExecutionBrokerGitReviewProbe` 为 Coding Review 读取有界 status 与 diff 元数据。三者共用同一条
DIRECT Git 读取通道和精确 Policy 绑定，只接受 Workspace 逻辑引用，不把宿主仓库路径、remote 或 diff
正文写入公共 DTO。

模型侧 status、diff、log、blame、branch、commit、push 等操作统一通过 `execution_run` 直接调用系统
`git`，不再依赖内置 Git Skill。该模块不是 Java Git SDK，不注册 `git.*` Tool，也不执行 fetch、
commit、push、reset、clean、merge 或 worktree 生命周期操作。
