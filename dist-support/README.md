# Haifa Agent Release Distributions

该目录承载 Haifa Agent 正式发行包的打包与分发辅助工具，不参与 Maven Reactor 业务模块编译。

## 产出制品

在 `dist-support/output/` 目录下生成以下 3 个 Release 发布包及 SHA-256 校验清单。制品名称中的版本统一带
`v` 前缀（例如传入 `0.1.0` 时输出 `v0.1.0`），与 Git Tag `v0.1.0` 保持一致：

1. **`haifa-coding-agent-windows-x64-v<version>.zip`**
   - 专为 Windows 终端交互打造的 Coding Agent 绿色便携包；
   - 内置裁剪版 Java 21 运行时（~45MB JRE），用户电脑完全无需配置 Java；
   - 包含 `bin/haifa.bat`、`bin/haifa.ps1` 以及一键将 `bin/` 加入用户 PATH 的 `bin/install.ps1`；
   - 内置无密钥默认配置 `haifa-coding.yaml`，启动脚本将其作为 `--config` 加载，并将持久化数据与日志
     统一落到 `%USERPROFILE%\.haifa-agent\coding\data` 与 `%USERPROFILE%\.haifa-agent\coding\logs`。

2. **`haifa-personal-assistant-windows-x64-v<version>.zip`**
   - Personal Assistant 独立 Web 助手便携包；
   - 内置裁剪版 Java 21 运行时及完整打包的 Web SPA 前端页面（合一单 JAR 架构）；
   - 包含前台运行自启浏览器的 `start.bat`、后台无窗口运行的 `start-background.vbs` 与停服脚本 `stop.bat`。

3. **`haifa-agent-sdk-v<version>.zip`**
   - 包含纯 Java SDK、SDK Starter、Spring Boot 自动装配模块及 BOM 的完整发行压缩包；
   - 附带 Maven / Gradle 引入配置说明与最小代码示例。

4. **`sha256sums.txt`**
   - 所有生成的 Release 压缩包的 SHA-256 哈希校验清单。

---

## 快速使用

### 1. 一键生成全部 3 个发布包

Windows PowerShell：
```powershell
.\dist-support\scripts\build-distributions.ps1 all
```

Linux / macOS：
```bash
./dist-support/scripts/build-distributions.sh all
```

### 2. 指定版本号打包（如正式发版 `0.1.0`）

```powershell
.\dist-support\scripts\build-distributions.ps1 all --version 0.1.0
```

### 3. 单独构建指定产品包

- 仅构建 Coding Agent：
  ```powershell
  .\dist-support\scripts\build-distributions.ps1 ca
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
├── templates/                     # Windows 启动脚本与说明模板
│   ├── ca/                        # Coding Agent 模板 (haifa.bat, haifa.ps1, install.ps1, README.txt)
│   ├── pa/                        # Personal Assistant 模板 (start.bat, stop.bat, README.txt)
│   └── sdk/                       # SDK 依赖说明模板 (README.txt)
├── scripts/
│   ├── package_distributions.py  # 核心打包业务逻辑 (Python 3)
│   ├── build-distributions.ps1   # PowerShell 原样透传薄入口
│   └── build-distributions.sh    # Bash 原样透传薄入口
├── dist-work/                     # 打包中间工作区 (已忽略)
└── output/                        # 最终生成的 ZIP 与校验和文件 (已忽略)
```
