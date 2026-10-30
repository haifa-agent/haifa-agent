# Haifa Agent Docker AIO Sandbox

显式本地 Docker Engine + Linux AIO 容器的可选 Sandbox Provider，Provider ID 为 `docker-aio`。
连接入口支持 Unix socket，以及 Windows 原生 JVM 通过 Docker Desktop 命名管道运行 Linux 容器。

> 状态：W14 P1 实现候选。Windows 替身测试覆盖配置、生命周期顺序、超时、取消与清理语义；
> 真实 Linux 容器、网络隔离、cgroup 与挂载证据仍为 `NOT_RUN` / `BLOCKED_EXTERNAL`。
> Windows Docker Desktop 的真实命名管道、NTFS 双向读写与 UID/GID 验证也需要目标环境显式执行。
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

- 安装标识、本地 Unix socket 或 Windows 命名管道路径、锁定镜像 `repository@sha256:...`
- 容器 workspace 根、镜像内 shell runtime 路径（`bash` / `python`）
- 可选 UID/GID、nano CPU、内存、PID 上限
- Docker Engine 请求期限与 stop 期限、AIO 端口

所有字段进入 `configurationDigest()`（SHA-256）；任一字段变化都会产生不同 Provider 配置身份，
旧 Profile / Tool / Approval 不能复用。配置与摘要不包含宿主路径的模型可见投影。

## Windows Docker Desktop 装配

可信产品装配把 `DockerAioSandboxConfiguration.localDockerSocket()` 的返回值传入原有
`dockerSocket` 参数：Linux 默认 `/var/run/docker.sock`，Windows 默认 `\\.\pipe\docker_engine`。
该入口在配置创建时读取本机 OS 与 `DOCKER_HOST`；显式环境值支持当前平台的 `unix:///...` 或
`npipe:////./pipe/...`，不支持 TCP/SSH 远端。直接传入 `Path` 的既有构造器保持显式配置语义。
Desktop 使用独立 Linux Engine 管道时，可显式配置 `npipe:////./pipe/dockerDesktopLinuxEngine`。

Windows 上先启动 Docker Desktop 并切换到 **Linux containers**，预先加载 digest 锁定的 AIO 镜像。
Workspace 必须是 Docker Desktop 可共享的本地盘目录；`D:\work space\项目` 转换为
`/d/work space/项目`，inspect 校验只接受同一路径及其 Desktop `/host_mnt`、
`/run/desktop/mnt/host` 前缀别名，仍核对完整路径。UNC 网络共享路径不支持。

命名管道模式始终通过 Docker 发布到 `127.0.0.1` 的端口访问 AIO；容器 IP 位于 Desktop VM 内，
原生 Windows JVM 不直接访问它。Unix socket 模式保留容器 IP 优先的既有行为。
UID/GID 继续由装配显式指定或沿用镜像默认值，不自动提权、修改 NTFS 权限或执行 `chmod 777`。
Docker 不可用时返回 `DOCKER_ENGINE_UNAVAILABLE` 及启动/连接提示，容器失败不会切换到宿主执行。
锁定镜像缺失仍返回 `DOCKER_IMAGE_UNAVAILABLE`；实机验收还应单独核对取消、回收、网络和资源隔离。
这项 Provider 支持不自动把 Coding Agent、Personal Assistant 或 Skill 的默认执行平台改成容器。

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
- Unix socket 模式优先路由容器 IP（或回退 127.0.0.1 映射端口），Windows 命名管道模式使用 127.0.0.1 映射端口；这不是 `network=none`，管理通路与 workload 断网通过内部网桥保障。
- 普通 Docker 与宿主共享内核；本期不承诺敌对多租户、独立内核、microVM 或远端集群隔离。

## 已知缺口

- 真实 Linux 的 Docker daemon、镜像、isolated network、seccomp/cgroup/UID/GID、挂载/链接、
  取消与回收证据尚未执行。
- Windows 管理通路使用 `127.0.0.1` 随机宿主端口；若目标 Docker 版本不支持该组合，候选实现将 fail closed，
  不会自动改用宿主执行。
- Skill 容器装配尚未实现；`SkillScriptPlatforms.trusted(...)` 仍只提供宿主 runtime 预检。

## 验证

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L1 '--' `
  -pl :haifa-agent-sandbox-docker -am `
  '-Dtest=DockerAioSandboxProviderTest,DockerAioWarmSandboxProviderTest,DockerAioSandboxConfigurationTest,DockerDesktopPathsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

该测试只使用内存 Docker/AIO 替身，不证明真实容器已启动或隔离已通过。

`DockerDesktopSandboxIT` 是默认关闭的真实环境用例。先完成同一 SHA 的单元验证，再在原生 Windows
终端确认 `docker version` 可连接且 `docker info --format '{{.OSType}}'` 返回 `linux`，预先拉取
符合本模块 AIO 契约且允许以 `1000:1000` 启动的锁定镜像，然后显式执行：

```powershell
$desktopImage = 'your-registry/aio@sha256:<64-hex-digest>'
$desktopArguments = @(
  '-Pci-integration-only', '-pl', ':haifa-agent-sandbox-docker', '-am',
  '-Dit.test=DockerDesktopSandboxIT', '-Dfailsafe.failIfNoSpecifiedTests=false',
  '-Dhaifa.docker.desktop.it=true', "-Dhaifa.docker.desktop.image=$desktopImage", 'verify'
)
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 '--' @desktopArguments
```

用例通过真实 Provider 分别执行 `EPHEMERAL` 与 `WARM_SESSION`，在 `@TempDir` 中检查宿主文件可由
容器用户读取、容器创建的文件可由宿主读取并修改、修改可被后续执行读取，同时断言实际 UID/GID。
容器与网络使用每次独立的安装标识并由 Provider 关闭时清理；不会在测试中拉镜像。
缺少平台或开关时用例跳过；跳过不代表真实 Docker 验证通过，也不证明全部网络隔离或 cgroup 行为。
