# Haifa Agent Release Distributions

该目录承载 Haifa Agent 正式发行包的打包与分发辅助工具，不参与 Maven Reactor 业务模块编译。

## 产出制品

打包目标按「产品 × 平台」寻址，写成 `<product>[:<platform>]`。制品名称中的版本统一带 `v` 前缀
（例如传入 `0.1.0` 时输出 `v0.1.0`），与 Git Tag `v0.1.0` 保持一致：

| 目标 | 产物 | 内置 JRE | 构建宿主 | 平台无关 |
| --- | --- | --- | --- | --- |
| `ca:windows-x64` | `haifa-coding-agent-windows-x64-v<version>.zip` | 是 | Windows x64 | 否 |
| `ca:linux-x64` | `haifa-coding-agent-linux-x64-v<version>.tar.gz` | 是 | Linux x64 | 否 |
| `pa:windows-x64` | `haifa-personal-assistant-windows-x64-v<version>.zip` | 是 | Windows x64 | 否 |
| `pa:linux-x64` | `haifa-personal-assistant-linux-x64-v<version>.zip` | 否（系统 JDK 21） | 任意 | 是 |
| `sdk` | `haifa-agent-sdk-v<version>.zip` | 否 | 任意 | 是 |

不带平台后缀时取当前宿主平台（Windows 上的 `ca` 即 `ca:windows-x64`）；`all` 表示「本宿主能产出的
全部目标 + 平台无关目标」，即 `ca:<host>`、`pa:<host>`、`pa:linux-x64`、`sdk`。`macos-x64` 与
`macos-arm64` **没有发行包**：内置 JRE 需要签名与公证，本仓库不交付；macOS 开发机用
[`scripts/package-local-coding-agent.sh`](../scripts/package-local-coding-agent.sh) 配系统 JDK 生成可搬运目录。
打包脚本对上述矩阵之外的组合直接报错，不会静默跳过或产出错标平台的运行时。

1. **`haifa-coding-agent-windows-x64-v<version>.zip`**
   - 专为 Windows 终端交互打造的 Coding Agent 绿色便携包；
   - 内置裁剪版 Java 21 运行时（~45MB JRE），用户电脑完全无需配置 Java；
   - 包含 `bin/haifa.bat` 以及双击即可将 `bin/` 加入用户 PATH 的 `bin/install.bat`；
   - 内置无密钥默认配置 `haifa-coding.yaml`，启动脚本将其作为 `--config` 加载，并将持久化数据与日志
     统一落到 `%USERPROFILE%\.haifa-agent\coding\data` 与 `%USERPROFILE%\.haifa-agent\coding\logs`。

2. **`haifa-personal-assistant-windows-x64-v<version>.zip`**
   - Personal Assistant 独立 Web 助手便携包；
   - 内置裁剪版 Java 21 运行时及完整打包的 Web SPA 前端页面（合一单 JAR 架构）；
   - 包含前台运行自启浏览器的 `start.bat`、后台无窗口运行的 `start-background.vbs` 与停服脚本 `stop.bat`。

3. **`haifa-personal-assistant-linux-x64-v<version>.zip`**
   - Linux 服务器包：不内置 JRE（使用系统 JDK 21），前端由 nginx 直接提供静态文件；
   - 含 `app.jar`、`web/`（相对基址 `/api/v1` 与 `/v1/admin`）、`conf/pa.env`、
     `conf/haifa-personal-assistant.service`、`conf/pa.conf`（nginx 站点模板）与 `bin/` 脚本；
   - `sh bin/check.sh` 用临时端口与临时数据目录自检启动与 `/actuator/health`。

4. **`haifa-agent-sdk-v<version>.zip`**
   - `repository/` 包含纯 Java SDK、SDK Starter、Spring Boot 自动装配、公开 Integration、
     所有内部依赖与父 POM/BOM，以及源码/Javadoc，采用 Maven 文件仓库布局；
   - 附带 Maven / Gradle 引入配置说明与最小代码示例。

5. **`haifa-coding-agent-linux-x64-v<version>.tar.gz`**
   - Linux x86_64 Coding Agent 便携包，内置在 Linux 上裁剪的 Java 21 运行时；
   - 包含可执行 `bin/haifa`、`lib/` 和无密钥 `haifa-coding.yaml`，解压后可直接启动；
   - 默认数据与日志位于 `$HOME/.haifa-agent/coding`，支持与 Windows 包相同的 `HAIFA_*` 路径覆盖。

6. **`sha256sums.txt`**
   - **本次打包版本**全部压缩包（`*.zip` 与 `*.tar.gz`）的 SHA-256 校验清单；输出目录里其他版本的
     旧包不会混入。CI 只上传 `*.zip` 与该清单；
     Linux CA 的 `tar.gz` 需人工打包上传，其校验值不会自动进入该清单。

---

## 快速使用

### 1. 在 Windows 一键生成四个发布包

先使用 `sdk-modules` 动作取得公开模块列表，并构建相同版本的制品：

```powershell
$sdkModules = python dist-support/scripts/package_distributions.py sdk-modules
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 '--' `
  -pl "$sdkModules,:haifa-agent-cli,:haifa-agent-personal-assistant-server" -am -Prelease-artifacts verify
```

该命令只生成制品；前置条件是同一 SHA 的 Unit / Architecture 已通过。PA 前端需先运行 `npm ci`。
`templates/pa/conf/pa.env` 与 `templates/pa-server/conf/pa.env` 必须已在提交中（不要写进
`.git/info/exclude`）：打包会直接复制 `templates/pa/conf`，缺失时在干净检出上以
`FileNotFoundError` 失败。
打包要求 `--version` 与根 POM `revision` 相同，SDK 缺少任一 JAR、Flattened POM，或含 Java
源码的模块缺少 Source / Javadoc 时失败。纯依赖 Starter 不生成 Source / Javadoc。
CA 打包还会按与 `scripts/` 相同的契约拒绝非 shaded 的 CLI JAR（例如用 `ci-fast` 跳过 shade 的构建
产物），POM 版本解析与默认配置校验也来自同一份 `scripts/distribution_contracts.py`。
无需向本地 Maven 仓库安装制品。

Windows PowerShell：
```powershell
.\dist-support\scripts\build-distributions.ps1 all
```

`all` 在 Windows x64 上产出 CA/PA 便携包、Linux 服务器包与 SDK；在 Linux 上产出 `ca:linux-x64`、
`pa:linux-x64` 与 `sdk`。内置 JRE 的目标必须在对应原生平台打包，其余目标（`pa:linux-x64`、`sdk`）
可在任意宿主执行。

### 2. 指定版本号打包（如正式发版 `0.1.0`）

```powershell
.\dist-support\scripts\build-distributions.ps1 all --version 0.1.0
```

### 3. 选择产品与平台

可以一次给出多个目标；同一次调用里可复用的中间产物只准备一次：

```powershell
# Windows 宿主的 CA、PA，再单独补一个平台无关的 Linux 服务器包
.\dist-support\scripts\build-distributions.ps1 ca pa pa:linux-x64

# 只打当前宿主平台的 Coding Agent（Windows 上即 ca:windows-x64）
.\dist-support\scripts\build-distributions.ps1 ca

# 只打 SDK Bundle（任何宿主都可以）
.\dist-support\scripts\build-distributions.ps1 sdk

# 只预热当前宿主平台的裁剪 JRE，不产出发布包
.\dist-support\scripts\build-distributions.ps1 jre
```

```bash
# 在 Linux / WSL 打 Linux Coding Agent
bash dist-support/scripts/build-distributions.sh ca:linux-x64
```

### 4. 复用规则

打包过程中的三项工作只做一次，并且跨调用复用：

| 中间产物 | 复用范围 | 何时失效重建 |
| --- | --- | --- |
| 裁剪 JRE（`dist-work/jre/<platform>`） | 跨调用复用 | `--rebuild-jre`；模块集合变化（`dist-work/jre/<platform>.modules` 记录）；架构或 Java 版本校验失败 |
| Web SPA（`haifa-agent-personal-assistant-web/dist`） | 同一次调用内每种基址只构建一次 | 回环基址与相对基址必然各构建一次；`--skip-web-build` 直接复用现有 dist |
| SDK 仓库收集 | 每次 `sdk` 目标一次 | 按当前版本重新压缩 |

JRE 默认复用缓存；只有显式 `--rebuild-jre`、模块集合变化或缓存校验失败时才重新执行 `jlink`。

---

## 目录组织

```text
dist-support/
├── README.md                      # 本文档
├── .gitignore                     # 忽略 output/ 与 dist-work/ 临时目录
├── templates/                     # 各平台启动脚本与说明模板
│   ├── ca/                        # Coding Agent 模板 (haifa.bat, install.bat, README.txt)
│   ├── ca-linux/                  # Linux Coding Agent 模板 (bin/haifa, README.txt)
│   ├── pa/                        # Personal Assistant 模板 (bin/, conf/pa.env, README.txt)
│   ├── pa-server/                 # Linux 服务器包模板 (bin/, conf/, docker/, README.txt)
│   └── sdk/                       # SDK 依赖说明模板 (README.txt)
├── scripts/
│   ├── package_distributions.py  # 核心打包业务逻辑 (Python 3)
│   ├── distribution_contracts.py # 与 scripts/ 共用的 POM 版本、模型配置、shaded JAR 和构建入口契约
│   ├── build-distributions.ps1   # 转发到 build-support/scripts/invoke-python.ps1
│   └── build-distributions.sh    # 转发到 build-support/scripts/invoke-python.sh
├── dist-work/                     # 打包中间工作区 (已忽略)
└── output/                        # ZIP、tar.gz 与校验和文件 (已忽略)
```

---

## Linux Coding Agent（`ca:linux-x64`）

在 Linux x86_64 主机或 WSL Ubuntu 中运行，要求 Python 3.11+、Linux JDK 21 的 `jlink`
位于 PATH，且 CLI JAR 已按当前 POM 版本构建。打包只收集制品，不会自动运行 Maven。

```bash
bash dist-support/scripts/build-distributions.sh ca:linux-x64
# 如需强制重裁运行时（默认复用 dist-work/jre/linux-x64）
bash dist-support/scripts/build-distributions.sh ca:linux-x64 --rebuild-jre
# 从压缩包重新解压，用内置 Java 检查启动；使用临时数据，不调用模型
python3 dist-support/scripts/smoke_distributions.py verify-ca-linux

tar -xzf dist-support/output/haifa-coding-agent-linux-x64-v0.1.5-SNAPSHOT.tar.gz
./haifa-coding-agent-v0.1.5-SNAPSHOT/bin/haifa --terminal --workspace /absolute/workspace
```

若 WSL 不可用（`wsl.exe -d Ubuntu -- bash -lc true` 直接失败并报 `Wsl/Service/E_UNEXPECTED`，或只
输出 `localhost` 转发告警后退出），本机无法生成该包：先 `wsl.exe --shutdown` 重试，仍失败则改在
Linux 主机打包。CI 不产出该包，需要人工上传到 Release。

Windows PowerShell 进入当前 worktree 对应的 WSL 挂载目录后，也可调用同一入口：

```powershell
wsl.exe -d Ubuntu -- bash dist-support/scripts/build-distributions.sh ca:linux-x64
```

`ca:windows-x64` 与 `ca:linux-x64` 互不代打：内置 JRE 的目标必须在本机原生平台构建，脚本拒绝
错宿主或 ARM 主机。JRE 缓存分别在 `dist-work/jre/windows-x64`、`dist-work/jre/linux-x64`，
CA 暂存目录也按平台隔离。写完任一批目标后都会重写 `sha256sums.txt`，且只收录当前版本的包。

tar.gz 明确保留启动脚本、Java 与进程辅助程序的执行权限，即使 WSL 暂存目录位于 Windows
挂载盘上也无需手动 chmod。包内 README 说明模型连接与数据目录；首次运行会打开连接模型窗口。

内置 JRE 的兼容范围取决于构建 JDK。已在 WSL Ubuntu 24.04 验证，其他发行版、旧 glibc
与 musl 环境未验证。`all` 不自动跨系统生成该包；发布 Linux CA 时须在 Linux 主机单独执行
`ca:linux-x64`，现有 Windows Release 上传流程仍保持原有产物范围。

---

## Linux 服务器包（`pa:linux-x64`）

`haifa-personal-assistant-linux-x64-v<version>.zip` 面向 Linux 常驻部署，与 Windows 便携包的差异：

| 项目 | Windows 便携包（`pa:windows-x64`） | Linux 服务器包（`pa:linux-x64`） |
| --- | --- | --- |
| JRE | 内置 jlink 裁剪运行时 | 不内置，使用系统 JDK 21 |
| 前端 | 注入 JAR 的 `BOOT-INF/classes/static/`，浏览器同源直连 | 独立 `web/` 目录，基址为 `/api/v1` 与 `/v1/admin`，由 nginx 提供 |
| 启动 | `bin/start.bat`、`bin/start-background.vbs` | `conf/haifa-personal-assistant.service`（systemd）或 `bin/start.sh` |
| 反向代理 | 无 | `conf/pa.conf`：改写 Host/Origin 通过回环校验、SSE 关闭缓冲、上传上限 12m |
| 数据 | 包内 `data/personal-assistant/` | `HAIFA_PERSONAL_DATA_DIR`（模板默认 `/root/app/pa/data`） |
| 自检 | `smoke_distributions.py verify`（Windows 起 JRE 探活） | `sh bin/check.sh`（临时端口 + 临时数据目录探活，不动正式 data 与端口） |

两类产物共用同一个 `personal-assistant-web/dist`，但基址不同，因此打包脚本会按目标重建并**校验**：
Windows 包必须仍是回环绝对地址，服务器包必须是相对地址；不符则直接失败，避免发出“浏览器去连自己
localhost”的产物。该文件也是 `templates/pa-server/` 的同步依据。

```powershell
# 仅打包 Linux 服务器包（前端会按同源相对基址重建）
.\dist-support\scripts\build-distributions.ps1 pa:linux-x64

# 复核全部产物校验和并冒烟
python dist-support/scripts/smoke_distributions.py verify
```

```bash
./dist-support/scripts/build-distributions.sh pa:linux-x64
```

`all` 与一次给出多个目标的调用都按「内置 JRE 目标 → `pa:linux-x64` → `sdk`」的顺序执行，且每种 Web
基址只构建一次；结束后磁盘上的 `dist/` 停留在服务器（相对基址）形态。若紧接着只跑
`pa:windows-x64 --skip-web-build`，校验会失败并提示重新构建，这是预期行为。

包内 `docker/` 另提供容器化运行材料（本机 Docker Hub 带宽极低时也可用）：

- `docker/build-offline-base-image.sh`：用宿主 JDK 21 组装基础镜像 `pa-base:local`，不访问任何 registry；
- `docker/Dockerfile`：以该基础镜像 COPY 包根目录的 `app.jar`；
- `docker/docker-compose.yml`：只发布 `127.0.0.1:20001`，数据卷 `/root/app/pa/data` 与 HOME `/root/app/pa/home`
  分开挂载，根文件系统只读、`cap_drop: ALL`、内存/CPU/pid 限额。

容器化的两个硬约束（已在模板里固化，改动会导致启动失败）：HOME 不能落在数据目录内（host 执行边界会拒绝
`HOST_USER_HOME_UNAVAILABLE`）；`/tmp` 必须 `exec`（sqlite-jdbc 需要 dlopen 解压出的原生库）。

---

## 两个包的共同结构约定

Windows 便携包与 Linux 服务器包除了平台必需的部分外，保持同一套目录约定：

| 约定 | Windows 便携包 | Linux 服务器包 |
| --- | --- | --- |
| 产物名 | `haifa-personal-assistant-windows-x64-v<version>.zip` | `haifa-personal-assistant-linux-x64-v<version>.zip` |
| 顶层目录 | `haifa-personal-assistant-v<version>/` | `haifa-personal-assistant-v<version>/` |
| 可执行体 | `app.jar`（内嵌前端） | `app.jar` + `web/` |
| 运行参数 | `conf/pa.env` | `conf/pa.env`（同名同格式） |
| 数据目录 | `<包根>/data/personal-assistant/` | `<安装目录>/data/personal-assistant/`（容器为挂载卷） |
| 日志 | `<包根>/logs/personal-assistant.log` | `<安装目录>/logs/personal-assistant.log`（容器为 stdout） |
| 启停 | `bin/start.bat` / `status.bat` / `stop.bat` | `bin/start.sh`（systemd 或 compose 托管） |
| README 骨架 | 结构 / 前置 / 启动停止 / 自检 / 配置 / 数据与凭据 / 安全 / 升级 / 回滚 | 同上，另有 nginx 与容器两节 |

平台固有、不追求一致的差异：JRE 是否内置、进程托管方式、是否有 nginx、前端基址（绝对回环 vs 同源相对）、
凭据存储（凭据管理器 vs 0600 文件）。
