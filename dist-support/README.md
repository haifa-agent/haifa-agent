# Haifa Agent Release Distributions

该目录承载 Haifa Agent 正式发行包的打包与分发辅助工具，不参与 Maven Reactor 业务模块编译。

## 产出制品

在 `dist-support/output/` 目录下按动作生成以下 Release 发布包及 SHA-256 校验清单。制品名称中的版本统一带
`v` 前缀（例如传入 `0.1.0` 时输出 `v0.1.0`），与 Git Tag `v0.1.0` 保持一致：

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
   - 所有生成的 Release 压缩包的 SHA-256 哈希校验清单。

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
打包要求 `--version` 与根 POM `revision` 相同，SDK 缺少任一 JAR、Flattened POM，或含 Java
源码的模块缺少 Source / Javadoc 时失败。纯依赖 Starter 不生成 Source / Javadoc。
无需向本地 Maven 仓库安装制品。

Windows PowerShell：
```powershell
.\dist-support\scripts\build-distributions.ps1 all
```

`all` 包含需要 Windows JDK 的 CA/PA 便携包，须在 Windows x64 上运行。
Linux / WSL 使用下面的 `ca-linux`，无内置 JRE 的 `pa-server` 与 `sdk` 仍可独立跨平台打包。

### 2. 指定版本号打包（如正式发版 `0.1.0`）

```powershell
.\dist-support\scripts\build-distributions.ps1 all --version 0.1.0
```

### 3. 单独构建指定产品包

- 仅构建 Coding Agent：
  ```powershell
  .\dist-support\scripts\build-distributions.ps1 ca
  ```
- 在 Linux / WSL 构建 Coding Agent：
  ```bash
  bash dist-support/scripts/build-distributions.sh ca-linux
  ```
- 仅构建 Personal Assistant：
  ```powershell
  .\dist-support\scripts\build-distributions.ps1 pa
  ```
- 仅构建 SDK Bundle：
  ```powershell
  .\dist-support\scripts\build-distributions.ps1 sdk
  ```
- 复用已生成的 JRE（避免重复裁剪）：
  ```powershell
  .\dist-support\scripts\build-distributions.ps1 all --skip-jre
  ```

---

## 目录组织

```text
dist-support/
├── README.md                      # 本文档
├── .gitignore                     # 忽略 output/ 与 dist-work/ 临时目录
├── templates/                     # 各平台启动脚本与说明模板
│   ├── ca/                        # Coding Agent 模板 (haifa.bat, install.bat, README.txt)
│   ├── ca-linux/                  # Linux Coding Agent 模板 (bin/haifa, README.txt)
│   ├── pa/                        # Personal Assistant 模板 (start.bat, stop.bat, README.txt)
│   └── sdk/                       # SDK 依赖说明模板 (README.txt)
├── scripts/
│   ├── package_distributions.py  # 核心打包业务逻辑 (Python 3)
│   ├── build-distributions.ps1   # PowerShell 原样透传薄入口
│   └── build-distributions.sh    # Bash 原样透传薄入口
├── dist-work/                     # 打包中间工作区 (已忽略)
└── output/                        # ZIP、tar.gz 与校验和文件 (已忽略)
```

---

## Linux Coding Agent（`ca-linux`）

在 Linux x86_64 主机或 WSL Ubuntu 中运行，要求 Python 3.12+、Linux JDK 21 的 `jlink`
位于 PATH，且 CLI JAR 已按当前 POM 版本构建。打包只收集制品，不会自动运行 Maven。

```bash
bash dist-support/scripts/build-distributions.sh ca-linux
# 后续打包可复用本平台的运行时
bash dist-support/scripts/build-distributions.sh ca-linux --skip-jre
# 从压缩包重新解压，用内置 Java 检查启动；使用临时数据，不调用模型
python3 dist-support/scripts/smoke_distributions.py verify-ca-linux

tar -xzf dist-support/output/haifa-coding-agent-linux-x64-v0.1.4.tar.gz
./haifa-coding-agent-v0.1.4/bin/haifa --terminal --workspace /absolute/workspace
```

Windows PowerShell 进入当前 worktree 对应的 WSL 挂载目录后，也可调用同一入口：

```powershell
wsl.exe -d Ubuntu -- bash dist-support/scripts/build-distributions.sh ca-linux
```

`ca` 只生成 Windows 包，`ca-linux` 只生成 Linux 包；脚本拒绝使用不匹配的平台或 ARM 主机
生成标注 x64 的运行时包。JRE 缓存分别在 `dist-work/jre/windows-x64`、
`dist-work/jre/linux-x64`，CA 暂存目录也按平台隔离。单独打包任一产品后都会更新
`sha256sums.txt`，同时保留输出目录中现有 ZIP 与 tar.gz 的校验项。

tar.gz 明确保留启动脚本、Java 与进程辅助程序的执行权限，即使 WSL 暂存目录位于 Windows
挂载盘上也无需手动 chmod。包内 README 说明模型连接与数据目录；首次运行会打开连接模型窗口。

内置 JRE 的兼容范围取决于构建 JDK。已在 WSL Ubuntu 24.04 验证，其他发行版、旧 glibc
与 musl 环境未验证。`all` 不自动跨系统生成该包；发布 Linux CA 时须在 Linux 主机单独执行
`ca-linux`，现有 Windows Release 上传流程仍保持原有产物范围。

---

## Linux 服务器包（`pa-server`）

`haifa-personal-assistant-linux-x64-v<version>.zip` 面向 Linux 常驻部署，与 Windows 便携包的差异：

| 项目 | Windows 便携包（`pa`） | Linux 服务器包（`pa-server`） |
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
.\dist-support\scripts\build-distributions.ps1 pa-server

# 复核全部产物校验和并冒烟
python dist-support/scripts/smoke_distributions.py verify
```

```bash
./dist-support/scripts/build-distributions.sh pa-server
```

`all` 现在按 `ca → pa → pa-server → sdk` 顺序打包，结束后磁盘上的 `dist/` 停留在服务器（相对基址）形态；
若紧接着只跑 `pa --skip-web-build`，校验会失败并提示重新构建，这是预期行为。

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
