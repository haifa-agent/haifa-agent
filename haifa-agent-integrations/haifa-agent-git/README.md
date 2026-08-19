# Haifa Agent Git Control Plane

本模块只提供不向模型披露的最小 Git Revision Probe。`GitRevisionProbe.inspectHead` 经
`ExecutionBroker` 获取仓库、HEAD、branch/ref 与 submodule 安全事实，供 Worktree 隔离、漂移门禁和
Patch 合并使用；接口只接受 Workspace 逻辑引用，不暴露宿主仓库路径、remote、status entries 或 diff。

模型侧 status、diff、log、blame、branch、commit、push 等操作统一通过 `execution.run` 直接调用系统
`git`，并由共享 Git Skill 提供工作流。该模块不是 Java Git SDK，不注册 `git.*` Tool，也不执行 fetch、
commit、push、reset、clean 或自动 merge。Worktree 合并必须先验证父 Workspace revision 与 base commit，
再显式应用已经验证的 Patch。
