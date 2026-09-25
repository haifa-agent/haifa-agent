# Personal Assistant 真实环境快速启动

这套方案按前后端分离方式运行，不使用 Nginx：

| 组件 | 地址 | 启动方式 |
| --- | --- | --- |
| Personal Assistant Web | `http://127.0.0.1:20000/` | Node.js `serve` 直接提供 `dist/` |
| Personal Assistant Server | `http://127.0.0.1:20001/` | Spring Boot executable JAR |
| MCP | 默认关闭 | 需要时显式配置外部 loopback MCP（`HAIFA_PERSONAL_MCP_*`） |

Web 在浏览器中直接请求 `http://127.0.0.1:20001/api/v1`。Server 已限定允许来自
loopback `20000` 的 Origin，方案中没有反向代理。

## 1. 准备条件

- Java 21；
- Node.js 22.x、npm 10.x；后端构建使用仓库自带的 Maven Wrapper，不要求 PATH 上有 `mvn`；
- Python 3；PowerShell 与 POSIX Shell 入口共用仓库根目录 `scripts/real_environment.py` 中的生命周期实现；
- 主仓：`D:\workspace\haifa-agent`。

启动器只注入 `HAIFA_PERSONAL_DATA_DIR` 与 `HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true`：前者指向
本次运行的本地数据目录，后者确认本机联调部署允许启动受控宿主进程（Server 对该开关 fail closed，未确认时
拒绝启动）。Provider、模型、Endpoint 与凭据引用全部来自
`haifa-agent-personal-assistant-server/src/main/resources/application.yml`：每个 Provider 通过
`credential-reference: model-auth://…/default` 从本地凭据库读取凭据，`default-model-id` 决定默认模型。
脚本不读取任何凭据环境变量，也不校验 Key 前缀。可信 Host 开关只确认部署风险，具体调用仍需 Runtime 的
exact approval。

## 2. 配置凭据与默认模型

首次启动只有模型目录、没有可用凭据是预期状态。打开 `http://127.0.0.1:20000/`，点击“模型 / 模型连接”，
按 Provider 保存一次 API Key 或完成 OAuth 登录即可。凭据保存在本地 `model-auth` 凭据库中，不会写入
YAML、日志或浏览器响应。

需要切换默认模型时，修改 `application.yml` 的 `default-model-id`，或设置
`HAIFA_PERSONAL_DEFAULT_MODEL_ID` 环境变量。

## 3. 一键启动

```powershell
Set-Location D:\workspace\haifa-agent
& .\scripts\start-real-environment.ps1
```

```bash
./scripts/start-real-environment.sh
```

`.ps1` 与同目录 `.sh` 只处理各自平台的参数入口和 Python 3 解释器发现；构建、复用判断、健康检查和
子进程生命周期均由共用 Python 实现，两个入口保持同一行为。

CLI 只有三个可选参数：

```text
start-real-environment.ps1|.sh [--rebuild] [--backend-jar <path>] [--startup-timeout-seconds <30..600>]
```

- `--rebuild`：强制 `mvnw -pl :haifa-agent-personal-assistant-server -am -DskipUnitTests=true clean package`
  并强制重建前端 `dist`；要求 20000/20001 当前空闲；
- `--backend-jar <path>`：显式使用已构建的 Spring Boot JAR，而不是 `target/` 下的候选；与 `--rebuild` 互斥；
- `--startup-timeout-seconds`：启动健康检查超时，默认 180，范围 30..600。

脚本依次完成：

1. 校验本机工具（`java`、`node`、`npm`、仓库 Maven Wrapper）；
2. 需要时构建后端（缺失可读 JAR 或 `--rebuild`），把 JAR 复制为固定的
   `local-tmp/personal-assistant-real/backend/app.jar` 后从该副本启动；
3. 只在 `node_modules/serve/build/main.js` 缺失时执行 `npm ci`，只在 `dist/index.html` 缺失或
   `--rebuild` 时执行 `npm run build`；
4. 前台启动 20001 后端并等待 `/actuator/health`，再启动 20000 前端并等待 `GET /`；
5. 打印访问地址、数据/日志/后端运行目录。

端口已监听且健康时脚本复用现有服务；端口已监听但不健康时直接失败并保留现场，不会终止任何进程。

## 4. IDE 直接运行

在 IDE 中直接运行：

```text
io.haifa.agent.personalassistant.server.PersonalAssistantServerApplication
```

需自行设置 `HAIFA_PERSONAL_DATA_DIR`（例如 `local-tmp/personal-assistant-real/data`）和
`HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true`，否则 Server 因可信 Host 未确认而 fail closed。前端仍由
`npm run dev` 或本脚本承担。IDE 直接运行不执行 Maven `package`、Spring Boot `repackage` 或 JAR staging。

## 5. 停止与重建

脚本前台运行，`Ctrl+C` 会终止本次由它启动的子进程。脚本不写状态文件，也没有独立的停止子命令；
需要收尾时按端口处理：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 20000,20001 |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ }
```

```bash
lsof -nP -iTCP:20000 -iTCP:20001 -sTCP:LISTEN -t | xargs -r kill
```

端口释放后，用 `--rebuild` 重新构建并启动。

## 6. 验证与故障排查

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20001/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20000/
```

运行目录：

```text
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\backend\
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\data\
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\logs\
```

日志按角色与时间戳命名：`local-tmp/personal-assistant-real/logs/<role>-<timestamp>.{out,err}.log`。

如果 PowerShell 的脚本执行策略阻止本次运行，可仅对当前进程临时放开：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

## 7. macOS

macOS 使用同目录的 `start-real-environment.sh`，参数与行为同 PowerShell 版本一致：

```bash
./scripts/start-real-environment.sh
./scripts/start-real-environment.sh --rebuild
./scripts/start-real-environment.sh --startup-timeout-seconds 300
```

要求 macOS 已安装 Java 21、Node.js 22.x、npm 10.x。凭据与默认模型同样来自 `application.yml` 与 PA Web
模型连接面板。
