# Haifa Agent Docker AIO Sandbox

显式 Linux Docker Engine + AIO 的可选 Sandbox Provider，Provider ID 为 `docker-aio`。

> 状态：W14 P1 实现候选。Windows 替身测试覆盖配置、生命周期顺序、超时、取消与清理语义；
> 真实 Linux 容器、网络隔离、cgroup 与挂载证据仍为 `NOT_RUN` / `BLOCKED_EXTERNAL`。
> 它不是产品默认，也不改变 CA/PA 的 `host-guarded` 默认。

## 范围

- 每次执行创建一个独立容器和一个按 session 命名的临时 internal network。
- `open` 只分配本地 `SandboxSessionId`，不访问 Docker。
- `execute` 在请求 `ExecutionLimits.timeout` 内依次完成：镜像确认、网络创建、容器创建、
  启动、身份核对、AIO ready、命令执行、session 清理、stop/delete 与网络删除。
- `execute` 返回前必须确认容器与网络都已消失；未知清理结果返回 `UNKNOWN`，不会返回成功。
- `close` 只在执行异常或调用方提前关闭时做幂等兜底。
- `supportsManagedProcess()` 保持 `false`；MCP stdio、交互终端和后台服务不进入容器。

## 可信配置

`DockerAioSandboxConfiguration` 由产品装配提供，模型与 Tool 参数不能控制：

- 安装标识、Unix socket 路径、锁定镜像 `repository@sha256:...`
- 容器 workspace 根、镜像内 shell runtime 路径（`bash` / `python`）
- 可选 UID/GID、nano CPU、内存、PID 上限
- Docker Engine 请求期限与 stop 期限、AIO 端口

所有字段进入 `configurationDigest()`（SHA-256）；任一字段变化都会产生不同 Provider 配置身份，
旧 Profile / Tool / Approval 不能复用。配置与摘要不包含宿主路径的模型可见投影。

## 固定 AIO 协议

仅使用固定 v1.11.0 OpenAPI 子集中的：

- `GET /v1/sandbox` 作为 ready 探针
- `POST /v1/bash/exec`（`async_mode=false`、显式 `timeout`/`hard_timeout`、`max_output_length`）
- `POST /v1/bash/kill` 与 `POST /v1/bash/sessions/{id}/close` 清理

输出按真实 `status` / `exit_code` / `stdout` / `stderr` 交付；HTTP 200 不被当作命令成功，
`running` / `pending` 不被当作 `exit=0`。本切片不做字节流式输出，只返回有界结果。

## 安全边界

- 容器只绑定已授权 Workspace 根到配置的容器路径，读写；不挂宿主 HOME、产品 data-root 或
  Docker socket。
- 容器/网络均带安装、session、role labels；清理只按精确身份删除，不做全局 prune。
- 容器要求 `cap-drop=ALL`、`no-new-privileges`、非 privileged，并在 inspect 中核对；
  资源上限、镜像、网络、mount、UID/GID 都在执行前核对。
- 网络配置要求 Docker 接受 `internal=true`、`gateway_mode_ipv4/ipv6=isolated`、`enable_icc=false`；
  任一不满足即 fail closed，不降级为普通 bridge 或宿主执行。
- 管理端口只发布到 `127.0.0.1`；这不是 `network=none`，且管理通路与 workload 断网仍须 Linux 实测。
- 普通 Docker 与宿主共享内核；本期不承诺敌对多租户、独立内核、microVM 或远端集群隔离。

## 已知缺口

- 真实 Linux 的 Docker daemon、镜像、isolated network、seccomp/cgroup/UID/GID、挂载/链接、
  取消与回收证据尚未执行。
- 当前管理通路使用 `127.0.0.1` 随机宿主端口；若目标 Docker 版本不支持该组合，候选实现将 fail closed，
  不会自动改用宿主执行。
- Skill 容器装配尚未实现；`SkillScriptPlatforms.trusted(...)` 仍只提供宿主 runtime 预检。

## 验证

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L1 -- `
  -pl :haifa-agent-sandbox-docker -am `
  '-Dtest=DockerAioSandboxProviderTest,DockerAioSandboxConfigurationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

该测试只使用内存 Docker/AIO 替身，不证明真实容器已启动或隔离已通过。
