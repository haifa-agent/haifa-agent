# Execution 与 Sandbox 边界

Haifa Agent 通过 Execution Broker 统一执行 Shell、脚本、构建、测试和命令行工具。当前有两类
Sandbox Provider：

- `host-guarded`：CLI、Coding Agent、Coding Terminal 和 Personal Assistant 的默认实现，以当前宿主
  用户权限运行进程；
- `docker-aio`：显式装配的 Linux Docker + AIO 容器实现，通过 SDK Starter 的
  `ContainerExecutionPlatforms.dockerAio(...)` 接入。

两者复用同一个 `DefaultExecutionBroker`、Policy、Approval 和 Execution 结果边界。模型不能直接选择
Docker 引擎、镜像、mount、网络或宿主路径。两者的隔离能力不同：`host-guarded` 不提供内核级隔离，
`docker-aio` 也不承诺敌对多租户或独立内核隔离。

## 执行链路

模型只看到 `execution_run` Tool。一次调用的路径是：

```text
Runtime Tool Pipeline
  -> ExecutionToolProvider
  -> DefaultExecutionBroker（授权、Policy、期限、取消、结果记录）
  -> SandboxProvider.open(...)
  -> SandboxSession.execute(SandboxExecution)
  -> host-guarded 或 docker-aio Provider
```

Sandbox SPI 位于 `haifa-agent-execution/haifa-agent-sandbox-api`，核心类型是
`SandboxProvider`、`SandboxSession`、`SandboxExecution`、`SandboxProcessResult` 和
`SandboxProfile`。Broker 位于
`haifa-agent-execution/haifa-agent-execution-core/.../DefaultExecutionBroker.java`。

`docker-aio` 的实际交互分两层：

- Docker Engine API（Unix socket）：创建/启动/检查/停止/删除容器和 network；
- AIO HTTP API：容器内的命令执行与就绪探针。

模型、Runtime 和产品代码都不直接访问 Docker daemon。

## 哪些执行会进入容器

当前只有 `execution_run` 这一条执行路径会使用 Sandbox Provider。其他能力需要分别看：

- **MCP Tool**：Tool 调用在 Haifa 进程内完成，不经过容器。远程 MCP Server 使用 Streamable HTTP；
  stdio MCP Server 需要 `ExecutionBroker.openManagedSession(...)` 和 `SandboxManagedProcess`，而
  `docker-aio` 的 `supportsManagedProcess()` 为 `false`，`openManagedProcess(...)` 返回
  `MANAGED_PROCESS_UNSUPPORTED`。因此 stdio MCP 目前只能在 `host-guarded` 宿主路径运行，不能进入
  容器。
- **Skill**：Skill 内容加载和读取是进程内操作；Skill 脚本由 `SkillScriptPlatforms` 装配，当前固定
  使用 `HostGuardedSandboxProvider`，没有 `docker-aio` 容器装配。因此 Skill 脚本目前在宿主机执行。
- **`file_*` Tool**：文件读写由产品在 Haifa 进程内通过宿主机授权目录执行，不经过 Sandbox。容器内的
  `execution_run` 看到的是同一份 bind mount workspace，但 `file_*` Tool 本身不在容器内。
- **其他 Java Tool**：默认在 Haifa 进程内执行，不会因为启用了 `docker-aio` 就自动进入容器。

需要特别区分“工作区在容器内可见”和“Tool 在容器内执行”：前者只说明容器挂载了 workspace，后者要求
该 Tool 的实现本身通过 Sandbox 执行。

## host-guarded

Execution Broker 通过 Sandbox SPI 使用 `host-guarded` Provider 作为内置产品的本地实现。它提供的是
应用层 Process Governance，例如：

- 受控 Process Creation；
- 显式 Working Directory；
- Bounded Output；
- Timeout 与 Cancellation；
- Process Tree Cleanup；
- 受控 Environment Handling；
- MCP stdio 等能力使用的 Managed Process。

`host-guarded` 不承诺：

- Linux namespace isolation；
- macOS Seatbelt isolation；
- Windows AppContainer isolation；
- Container isolation；
- Network isolation；
- CPU / Memory cgroup isolation；
- Hostile multi-tenant code containment。

Working Directory 校验也不等价于 Kernel-enforced Filesystem Isolation。

## docker-aio 容器沙箱

`docker-aio` 是显式的 Linux 容器 Provider，核心实现位于
`haifa-agent-execution/haifa-agent-sandbox-docker`：

- `DockerAioSandboxProvider`：Sandbox 生命周期、workspace 校验、容器/网络身份校验；
- `DockerJavaEngine`：Docker Engine API；
- `HttpAioClient` 和 `AioClient`：固定 AIO HTTP 子集。

### 生命周期

Provider 支持两种模式：

- `WARM_SESSION`（当前默认）：按 Workspace 保留容器，跨多次 `execution_run` 复用，空闲后由
  `idleTimeout` 回收。容器内的文件、后台进程和运行时状态会在命令之间保留。
- `EPHEMERAL`：每次执行创建独立容器和 network，执行结束立即销毁。

容器的选择、资源上限和期限属于可信 Product Assembly，不是模型参数。切换生命周期模式会改变
`configurationDigest()`，旧 Profile、Tool Binding 和 Approval 不能复用。

### 镜像要求

`docker-aio` 不是“任意项目镜像直接运行”的通用容器运行时。镜像必须满足以下契约：

- Linux OCI 镜像，使用 digest 锁定（`repository@sha256:...`），并在执行前已经存在于目标 Docker
  Engine；当前 `ensureImage()` 只检查镜像是否存在，不会在运行时自动 pull。
- 容器内监听 AIO 端口（默认 `8080`），提供固定 AIO HTTP 子集：
  - `GET /v1/sandbox`：ready 探针；
  - `POST /v1/bash/exec`：`async_mode=false`，接受 `timeout`、`hard_timeout`、
    `max_output_length`，返回 `status`、`exit_code`、`stdout`、`stderr`；
  - `POST /v1/bash/kill`；
  - `POST /v1/bash/sessions/{id}/close`。
- 镜像内提供配置声明的运行时绝对路径。当前支持 `bash` 和 `python`；如果项目需要 JDK、Node、编译器
  或其他 CLI，需要预装到镜像中，不能依赖宿主工具链。
- 默认 entrypoint 能启动 AIO 服务；容器以非 `privileged` 运行，使用 `no-new-privileges`、CPU、
  内存和 PID 上限。当前实现不再强制 `cap-drop=ALL`，以兼容官方 AIO 启动脚本，但镜像不应依赖特权
  能力、host network/PID、Docker socket 或额外 capability。
- 镜像必须允许把已授权 Workspace 根 bind mount 到配置的 `containerWorkspaceRoot`，并能以配置的
  UID/GID（或镜像默认用户）在该目录读写。
- 容器网络为 Docker `internal=true`，并关闭 ICC；它没有外网和容器间互通能力。镜像不能在运行时依赖
  外网下载依赖或访问外部服务。
- AIO 管理端点当前不携带认证头；镜像不能要求客户端提供额外认证，否则当前客户端不兼容。管理面的可达性
  由内部网络、Docker 端口绑定和宿主机访问控制约束。

因此，实际使用通常是以官方 AIO 1.11.0 兼容镜像为基础，把项目工具链预装进镜像；不能把普通应用镜像
直接当作 AIO Sandbox。即使容器成功启动，也只有在 AIO API、运行时路径、mount 和资源身份都匹配时，
Provider 才会继续执行。

### 协议与边界

`SandboxSession.execute` 接收的命令、工作目录、环境、输入和 `ExecutionLimits` 都由 Broker 构造；
Provider 只执行已冻结的请求。`docker-aio` 在 `execute` 内完成镜像检查、network/容器创建、身份核对、
AIO ready 和命令执行，并按请求期限处理取消与清理。网络、容器和资源身份不匹配时 fail closed，不会
回退到宿主机执行。

stdio MCP 使用的是另一条 Managed Process 协议：

```text
DefaultExecutionBroker.openManagedSession(...)
  -> SandboxSession.openManagedProcess(...)
  -> SandboxManagedProcess.read/write/exit/cancel
```

`host-guarded` 实现这条协议；`docker-aio` 目前不支持。

## 配置与切换

内置产品当前只装配 `host-guarded`。CLI 的 `execution.provider` 只接受 `host-guarded`；配置
`docker-aio` 会在启动期被拒绝。Coding Agent、Terminal 和 Personal Assistant 也没有运行时切换到
容器 Provider 的开关。

可切换的是 SDK Starter 的装配代码：

```java
// 宿主机执行
var hostExecution = HostExecutionPlatforms.hostGuarded(
        new HostExecutionPlatforms.HostExecutionSpec(
                workspaceRoot, dataRoot, defaultTimeout, maximumTimeout, maxOutputBytes, maxOutputLines),
        tenant, principal);
hostExecution.applyTo(builder);

// 容器执行
var containerExecution = ContainerExecutionPlatforms.dockerAio(
        new ContainerExecutionPlatforms.ContainerExecutionSpec(
                workspaceRoot, defaultTimeout, maximumTimeout, maxOutputBytes, maxOutputLines),
        dockerAioConfiguration,
        tenant, principal);
containerExecution.applyTo(builder);
```

容器配置由 `DockerAioSandboxConfiguration` 提供，包括镜像 digest、Docker socket、容器 workspace
根、`bash`/`python` 路径、UID/GID、CPU/内存/PID、Engine/stop 超时、AIO 端口、最大并发、
`lifecycleMode` 和 `idleTimeout`。`ContainerExecutionSpec` 提供 Workspace、默认/最大期限和输出上限。

这是**装配期选择**，不是运行时热切换：选择结果会进入 Profile 和配置摘要，旧 Approval 不能用于新的
Provider 配置；容器创建或执行失败时不会自动回退 `host-guarded`。切换 Provider 需要重新装配 Builder，
并重新冻结相关 Tool/Profile。

## Trust Model

`host-guarded` 适用于受信任、本地产品场景，前提是操作者明确知道命令会以当前 Host Account 权限执行。

`docker-aio` 提供 Docker 容器、资源上限和受限网络边界，但 Docker 与宿主共享内核，不承诺敌对多租户、
独立内核、microVM 或远端集群隔离。如果要执行不可信第三方代码，或者面对强 Multi-tenant Isolation 场景，
应把整个 Haifa Agent 进程放进外部安全边界，例如 VM、Hardened Container 或其它专门针对该 Threat Model
的环境。

## Approval 是另一层边界

Policy / Approval 可以要求操作者批准一个精确 Execution Request。

Approval 解决的是“Haifa 是否应该执行这次动作”，它不会把 Host Process 变成 Kernel Sandbox，也不会
替代容器、网络或宿主隔离。

## Coding Agent

Coding Agent 通过 Authorized Workspace Directory 与统一 Execution Path 执行 Shell、git、gh、Build
Tool 和客户脚本。当前 Coding Agent 默认使用 `host-guarded`；如果未来产品装配 `docker-aio`，只有
`execution_run` 进入容器，`file_*`、MCP 和 Skill 仍需按上面的边界分别处理。

Java 层不会再为每一种命令复制一套 Command-specific Permission DSL。
