# Haifa Agent Docker AIO Sandbox

显式 Linux 容器 Docker Engine + AIO 的可选 Sandbox Provider，Provider ID 为 `docker-aio`。
宿主可使用本地 Unix socket，或原生 Windows JVM + Docker Desktop 的本地 named pipe。

> 状态：W14 P1 实现候选。替身测试覆盖配置、生命周期顺序、超时、取消与清理语义；
> 真实 Linux 容器、网络隔离、cgroup 与挂载证据仍为 `NOT_RUN` / `BLOCKED_EXTERNAL`。
> 原生 Windows named pipe、管理端口路由和驱动器路径有单元测试；真实 Docker Desktop 的 NTFS
> 挂载与容器/宿主互写仍为 `NOT_RUN`，不能据此宣称 Windows 实机验收通过。
> 它不是产品默认，也不改变 CA/PA 的 `host-guarded` 默认。

## 范围与生命周期模式

Provider 支持两种容器生命周期模式（`DockerAioLifecycleMode`）：

- **`WARM_SESSION`（默认模式，依据修改后 ADR-038 与 DeerFlow Parity 架构）**：
  - 容器按 Workspace 维度常驻并复用，多次工具调用间免除反复创建与销毁容器开销（实测调度延迟由约 1.8s 降至 400ms，加速 4.3 倍）。
  - 执行严格在调用方超时预算内扣减耗时（排队、准备与底层执行统一衰减）；同 Workspace 并发调用在锁队列中有界等待。
  - 在途任务收到 `cancel()` 时立即中断并销毁/重置该容器，确保后台 runaway 进程彻底被杀灭；取消若发生在容器创建期间，创建完成的容器同样被销毁而不进入预热池。命令正常完成后调用 AIO `/v1/bash/sessions/{id}/close` 防止会话泄漏。
  - 空闲达 `idleTimeout`（默认 15 分钟）由后台守护协程自动安全回收（原子 CAS 防竞态）。
  - Provider 初始化时扫描并自动清理匹配当前 `installationId` 的遗留孤儿容器与网络。
- **`EPHEMERAL`（可选模式）**：
  - 每次执行创建独立容器和一个按 session 命名的临时 internal network，执行完毕立即彻底销毁。
  - `execute` 在请求 `ExecutionLimits.timeout` 内依次完成：镜像确认、网络创建、容器创建、
    启动、身份核对、AIO ready、命令执行、session 清理、stop/delete 与网络删除。
- `open` 只分配本地 `SandboxSessionId`，不额外同步访问 Docker。
- `supportsManagedProcess()` 保持 `false`；MCP stdio、交互终端和后台服务不进入容器。

## 可信配置

`DockerAioSandboxConfiguration` 由产品装配提供，模型与 Tool 参数不能控制：

- 安装标识、本地 Unix socket / Windows named pipe 路径、锁定镜像 `repository@sha256:...`
- 容器 workspace 根、镜像内 shell runtime 路径（`bash` / `python`）
- 可选 UID/GID、nano CPU、内存、PID 上限
- Docker Engine 请求期限与 stop 期限、AIO 端口

所有字段进入 `configurationDigest()`（SHA-256）；任一字段变化都会产生不同 Provider 配置身份，
旧 Profile / Tool / Approval 不能复用。配置与摘要不包含宿主路径的模型可见投影。

### 原生 Windows Docker Desktop

产品装配可用 `DockerAioSandboxConfiguration.localDockerSocket()` 解析一次本机端点，再把返回路径
传入配置。未设置 `DOCKER_HOST` 时，Windows 使用 `npipe:////./pipe/docker_engine`，其它宿主使用
`unix:///var/run/docker.sock`。`DOCKER_HOST` 可以显式选择本地 pipe（例如
`npipe:////./pipe/dockerDesktopLinuxEngine`）或 Unix socket；不接受远程 pipe、TCP、SSH、凭据、
query 或 fragment。端点进入配置摘要，不在每次执行时重新读取环境。

Windows 必须将 Docker Desktop 切到 **Linux containers**。驱动器路径（包括空格和中文）转换为
`/d/...` 等容器绑定源，inspect 只接受同一路径的精确 Desktop 别名；UNC 工作区不受支持。
Windows 的容器 IP 位于 Desktop VM 中，因此两种生命周期都使用经核对的 loopback 映射端口；
Linux Engine 保持优先使用 internal network 的容器 IP。

Engine 不可用时返回 `DOCKER_ENGINE_UNAVAILABLE` 并提示启动 Docker Desktop、检查 Linux containers
模式与端点；锁定镜像缺失仍是 `DOCKER_IMAGE_UNAVAILABLE`。Provider 失败后保持失败关闭，绝不自动
执行宿主命令。产品若需要 `host-guarded`，必须由用户显式切换并重新授权，不能继承容器隔离的承诺。

Windows 实机验收应在目标宿主上使用产品锁定的镜像和非 root UID/GID（通常 `1000:1000`）：
在含空格/中文的 NTFS Workspace 中依次执行宿主创建、容器读取/修改/创建、宿主读取/修改，核对实际
运行 UID/GID，并分别验证 WARM_SESSION 和 EPHEMERAL 的管理端口、取消与资源回收。不通过放宽至
`chmod 777` 掩盖权限问题；记录镜像 digest、Docker Desktop/WSL2 版本和各项结果。

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
- 容器要求非 privileged、`no-new-privileges`，并在 inspect 中核对；资源上限、镜像、网络、mount、UID/GID 都在执行前核对。
- 网络配置要求 Docker 接受 `internal=true`、`enable_icc=false`（禁止容器间互通与外网连通）；任一不满足即 fail closed，不降级为普通 bridge 或宿主执行。
- 管理端口在 Linux Engine 上优先路由容器 IP（或回退 127.0.0.1 映射端口），Windows Desktop 使用 loopback 映射端口；这不是 `network=none`，管理通路与 workload 断网通过内部网桥保障。
- 普通 Docker 与宿主共享内核；本期不承诺敌对多租户、独立内核、microVM 或远端集群隔离。

## 已知缺口

- 真实 Linux 的 Docker daemon、镜像、isolated network、seccomp/cgroup/UID/GID、挂载/链接、
  取消与回收证据尚未执行。
- Windows Desktop 管理通路使用 `127.0.0.1` 随机宿主端口；若目标 Docker 版本不支持该组合，候选实现将 fail closed，
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
