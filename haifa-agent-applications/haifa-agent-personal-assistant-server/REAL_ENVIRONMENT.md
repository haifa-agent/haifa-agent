# Personal Assistant 真实环境快速启动

这套方案按前后端分离方式运行，不使用 Nginx：

| 组件 | 地址 | 启动方式 |
| --- | --- | --- |
| Personal Assistant Web | `http://127.0.0.1:20000/` | Node.js `serve` 直接提供 `dist/` |
| Personal Assistant Server | `http://127.0.0.1:20001/` | Spring Boot executable JAR，或 IDE 当前编译 classpath |
| Personal Server 内置 MCP | `http://127.0.0.1:20002/mcp` | Server 进程内的 `embedded-echo`（脚本不再启动外部 MCP） |

Web 在浏览器中直接请求 `http://127.0.0.1:20001/api/v1`。Server 已限定允许来自
loopback `20000` 的 Origin，方案中没有反向代理。

## 1. 准备条件

- Java 21；
- Node.js 22.x、npm 10.x；后端构建使用仓库自带的 Maven Wrapper，不要求 PATH 上有 `mvn`；
- Python 3；PowerShell 与 POSIX Shell 入口共用仓库根目录 `scripts/real_environment.py` 中的生命周期实现；
- 主仓：`D:\workspace\haifa-agent`；
- `DEEPSEEK_API_KEY`：必需，只从当前进程环境读取（Windows 也回退到用户环境变量）；
- `TAVILY_API_KEY`：必需，默认 Search 与 Fetch Provider 都是 Tavily；
- 可选 `DASHSCOPE_API_KEY` + `ALIYUN_BAILIAN_WORKSPACE_ID`（可选 `ALIYUN_BAILIAN_REGION`，缺省
  `cn-beijing`）；
- 可选 `KIMI_API_KEY`、`BIGMODEL_API_KEY`、`SILICONFLOW_API_KEY`；
- 可选 `ALIYUN_IQS_API_KEY`、`BROWSERLESS_TOKEN`：只在 Search/Fetch 显式选择对应 Provider 时必需。

凭据只来自环境变量，脚本不再读取任何 Key 文件。不要把凭据写进命令行参数、命令历史、日志或文档；
脚本从不打印凭据值，只把它们注入子进程环境。

`OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL_ID` 仅用于可选的本机 OpenAI Responses
Provider。三项都配置时启用该 Provider；全部缺失或仅配置一部分时继续使用 DeepSeek-only 环境，
其中不完整配置会输出不含配置值的警告。

百炼只有 API Key、Workspace ID 和 region 全部有效时才启用；三者分别来自
`DASHSCOPE_API_KEY`、`ALIYUN_BAILIAN_WORKSPACE_ID`、`ALIYUN_BAILIAN_REGION`，后端配置
只保存 `env://DASHSCOPE_API_KEY`。Kimi、智谱、硅基流动分别来自 `KIMI_API_KEY`、`BIGMODEL_API_KEY`、
`SILICONFLOW_API_KEY`，
配置只保存对应 `env://...` 引用。可选 Provider 只扩展目录，未显式指定 `--default-model-id` 时继续使用
`deepseek-chat-flash`。启动本身不会调用任何模型 API；百炼 Chat/Responses、Kimi Chat、智谱 Chat/
Anthropic 和硅基流动 Chat 的真实调用必须另行明确发起。硅基流动只发布已验证的
`deepseek-ai/DeepSeek-V4-Flash`，内部模型 ID 是 `siliconflow-deepseek-v4-flash`。

启动脚本固定发布 Direct Binding `antigravity-gemini`；`HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST=true` 只控制
能否发起新的本地兼容 OAuth 登录，不控制模型目录。该 Binding 从
`model-auth://google-antigravity/default` 获取当前登录凭据，默认使用
Daily Endpoint `https://daily-cloudcode-pa.googleapis.com/v1internal`，并通过
`HAIFA_ANTIGRAVITY_PROXY_URL`（默认 `http://127.0.0.1:2081`）访问。可用
`HAIFA_ANTIGRAVITY_MODEL_ENDPOINT`、`HAIFA_ANTIGRAVITY_MODEL` 覆盖模型调用配置。

## 2. 一键启动

在普通 PowerShell 中执行：

```powershell
Set-Location D:\workspace\haifa-agent
& .\scripts\start-real-environment.ps1
```

使用 Antigravity Direct Gemini dialect：

```powershell
& .\scripts\start-real-environment.ps1 --default-model-id antigravity-gemini
```

`.ps1` 与同目录 `.sh` 只处理各自平台的参数入口和 Python 3 解释器发现；服务配置、构建、健康检查、
状态文件、PID 身份校验与安全停止逻辑均由共用 Python 实现，两个入口保持同一行为。

### 2.1 IDE 直接启动，不打包后端 JAR

在 IDE 中运行测试源码集里的：

```text
io.haifa.agent.personalassistant.server.development.PersonalAssistantRealEnvironmentMain
```

该 Main 会把 IDE 已编译的模块 classpath 规范化为绝对路径，并以
`--backend-launch-mode classpath` 调用同一个 `scripts/real_environment.py`。Provider 清单、环境变量
凭据、Web、端口、健康检查、PID 状态和停止流程仍由 Python 实现；Java 入口不维护第二份装配。
因此修改 Java 源码后只需让 IDE 增量编译，再停止旧环境并重新运行该 Main，不会执行 Maven `package`、
Spring Boot `repackage` 或 JAR staging。

Main 参数会原样传给 Python，例如在 IDE Program arguments 中填写：

```text
--default-model-id antigravity-gemini
```

如 Python 不在默认 PATH，可在 IDE 环境变量中设置 `HAIFA_PYTHON_COMMAND` 为 Python 可执行文件绝对路径。
`HAIFA_PERSONAL_DEV_CLASSPATH` 由 Main 自动注入，不应手工持久化。classpath 模式不接受 `--rebuild`；前端
仍只在 `dist` 缺失时构建，显式全量重建继续使用脚本的 JAR 模式。

脚本会依次完成：

1. 校验本机工具（`java`、`node`、`npm`、仓库 Maven Wrapper）和必需环境变量
   （`DEEPSEEK_API_KEY`、所选 Web Provider 的 Key）；
2. 解析 Continuation Key：优先 `HAIFA_PERSONAL_CONTINUATION_KEY`，其次 `--continuation-key-file`，
   都没有时才生成随机 32 字节 Key 并持久化到
   `local-tmp/personal-assistant-real/continuation-key.env`，变量名为
   `HAIFA_PERSONAL_CONTINUATION_KEY`；
3. JAR 模式只在后端 JAR 不存在时构建后端；IDE classpath 模式直接使用当前编译结果；
4. 按内容摘要把后端 JAR 复制到 `local-tmp/personal-assistant-real/backend/`，从运行副本启动，避免
   Java 进程锁定 Maven `target/` 下的构建产物；复制前校验 Spring Boot Manifest 和 `BOOT-INF`，
   遇到未完成 `repackage` 的普通 JAR 时自动重新执行 `package`，二次校验失败则拒绝启动；
5. 只在 `node_modules` 不存在时执行 `npm ci`，只在 `dist` 不存在时构建前端；
6. 以真实模型和所选 Web Provider 启动 20001 后端；脚本不再注入 MCP 覆盖项，也不注入本地 Skill 根
   目录，因此后端使用产品默认的 `embedded-echo` MCP 且不加载本地 Skill；
7. 用 Node.js `serve` 启动 20000 前端；
8. 等待两个 HTTP 健康检查成功，并输出 PID、各组件工作目录、数据/日志目录、访问
   地址和状态文件位置。

因此日常再次启动不会重复执行 npm 构建；运行 PA 时也可以执行 Maven `clean`、`package` 和全仓验证。
脚本不会杀掉端口上的未知进程；如果端口被非目标服务占用，它会直接失败并保留现场。
升级脚本前已经直接从 `target/` 启动的后端需要完成一次“`--stop` 后重新启动”，才会迁移到运行副本；
停止流程继续识别旧命令行路径，不需要使用 `--force`。
升级脚本前由脚本启动的 20002 Utility MCP 不再由脚本管理：必须先用旧脚本停止它，否则它会占用
20002 并让后端进程内的 `embedded-echo` MCP 绑定失败。

如果 PowerShell 的脚本执行策略阻止本次运行，可仅对当前进程临时放开：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

## 3. 更新代码后重建

先使用脚本自身的停止功能关闭旧环境：

```powershell
& .\scripts\start-real-environment.ps1 --stop
```

停止前，脚本会同时核对 `last-start.json` 记录的 PID、端口当前监听 PID、进程名和
命令行身份标识（组件工作路径或启动类）。任一项不一致都会拒绝停止，不会把
同端口上的其他程序当作本环境。
需要只查看将要停止的进程时：

```powershell
& .\scripts\start-real-environment.ps1 --stop --dry-run
```

状态文件缺失、记录 PID 已过期或进程身份校验无法通过时，可按两个固定端口的当前监听
进程显式强制停止：

```powershell
& .\scripts\start-real-environment.ps1 --stop --force
```

`--force` 只允许与 `--stop` 一起使用。它会把状态或身份不一致降级为警告，并强制结束
当前监听 `20000`、`20001` 的进程，因此可能终止占用这些端口的非目标程序。
需要先核对强制停止目标时，可使用 `--stop --force --dry-run`。

确认 20000、20001 均已释放后，再执行：

```powershell
& .\scripts\start-real-environment.ps1 --rebuild
```

`--rebuild` 会重新构建后端和前端。后端已使用独立运行副本，不再锁定 Maven 构建产物；但为了保证
两个服务来自同一次受控重建、避免旧页面产物混用，只要这两个端口中任意一个仍被占用，重建仍会拒绝执行。

凭据只能通过环境变量提供，没有对应的命令行参数。需要显式指定 Web Provider 或持久化 Continuation
Key 位置时：

```powershell
& .\scripts\start-real-environment.ps1 `
  --bailian-region cn-beijing `
  --web-search-provider aliyun `
  --web-fetch-provider browserless `
  --continuation-key-file 'D:\secure\personal-continuation.env'
```

Continuation Key 文件必须长期保留。删除、更换它或改用另一个 `HAIFA_PERSONAL_CONTINUATION_KEY`
都会使旧的加密 continuation token 无法恢复；脚本从不打印 Key 内容。

## 4. 当前真实能力配置

后端使用以下关键配置：

```text
HAIFA_PERSONAL_DEFAULT_MODEL_ID=qwen3.7-max-2026-05-17  # 完整百炼配置存在且未显式覆盖时
HAIFA_PERSONAL_MODELPROVIDERS_0_ID=deepseek
HAIFA_PERSONAL_MODELPROVIDERS_0_DISPLAYNAME=DeepSeek
HAIFA_PERSONAL_MODELPROVIDERS_0_MODE=remote
HAIFA_PERSONAL_MODELPROVIDERS_0_ALLOWDETERMINISTIC=false
HAIFA_PERSONAL_MODELPROVIDERS_0_ENDPOINT=https://api.deepseek.com
HAIFA_PERSONAL_MODELPROVIDERS_0_CREDENTIALREFERENCE=env://DEEPSEEK_API_KEY
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_ID=deepseek-v4-pro
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_DISPLAYNAME=DeepSeek V4 Pro
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_PROVIDERMODELID=deepseek-v4-pro
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_ID=deepseek-v4-flash
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_DISPLAYNAME=DeepSeek V4 Flash
HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_PROVIDERMODELID=deepseek-v4-flash
HAIFA_PERSONAL_WEB_SEARCH_ENABLED=true
HAIFA_PERSONAL_WEB_SEARCH_PROVIDER=tavily
HAIFA_PERSONAL_WEB_SEARCH_ENDPOINT=https://api.tavily.com/search
HAIFA_PERSONAL_WEB_SEARCH_CREDENTIAL=env://TAVILY_API_KEY
HAIFA_PERSONAL_WEB_FETCH_ENABLED=true
HAIFA_PERSONAL_WEB_FETCH_PROVIDER=tavily
HAIFA_PERSONAL_WEB_FETCH_ENDPOINT=https://api.tavily.com/extract
HAIFA_PERSONAL_WEB_FETCH_CREDENTIAL=env://TAVILY_API_KEY
HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true
HAIFA_PERSONAL_PYTHON_PATH='D:\Program Files\Python311\python.exe'
```

脚本不再设置 `HAIFA_PERSONAL_MCP_*` 覆盖项，也不再设置 `HAIFA_PERSONAL_SKILL_ROOT`：MCP 由产品
默认配置决定（`mcp.mode=embedded-echo`，只暴露本地 `echo` Tool，端口 `20002`），本地 Skill 根目录保持
为空。需要外部 MCP 或本地 Skill 时，按产品 README 手工配置后端环境变量，脚本不代为管理。

`HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true` 只确认当前本机部署允许启动受控
宿主进程；具体命令或脚本调用仍需经过 Runtime 的 exact approval。

## 5. 验证与故障排查

启动完成后检查：

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20001/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20000/
```

运行记录和日志位于：

```text
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\last-start.json
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\backend\
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\logs\
```

正常停止直接使用启动脚本：

```powershell
& .\scripts\start-real-environment.ps1 --stop
```

停止结果写入：

```text
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\last-stop.json
```

停止后可以再次检查端口：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -in 20000, 20001
```

## 6. Web Tool

脚本从当前进程环境读取 `TAVILY_API_KEY` 并注入后端子进程，默认组合为 `web.search=tavily`、
`web.fetch=tavily`。可通过 `--web-search-provider aliyun`，或通过
`--web-fetch-provider aliyun|browserless` 单独覆盖；选择 Aliyun 时需要 `ALIYUN_IQS_API_KEY`，选择
Browserless 时需要 `BROWSERLESS_TOKEN`。缺少所选 Provider 的必需变量时脚本拒绝启动。
两个 Tool 分别精确绑定公共模块中的 `web.search` 与 `web.fetch`，并继续经过 Runtime Tool Pipeline、
Policy、Approval 和 Credential lease；没有隐式 Provider fallback，也不会把一个 Provider 的秘密交给另一个。

## 7. macOS 一键启动

macOS 使用同目录的 `start-real-environment.sh`，功能与 PowerShell 脚本对齐：

- 构建或复用 Personal Server JAR 和 Personal Web `dist`，并从仓库 `target/` 之外的 JAR 副本启动；
- 启动或复用 Personal Server 和 Personal Web；
- 固定使用 `127.0.0.1:20001/20000` 并等待 HTTP 健康检查；
- 凭据只从环境变量读取并注入后端子进程，不写入状态文件或日志；
- 未知进程占用端口时直接失败；
- 停止前核对 `last-start.json`、监听 PID、进程名和命令行身份标识。

要求 macOS 已安装 Java 21、Node.js 22.x、npm 10.x，以及系统命令 `lsof`（用于端口监听 PID 发现）。
凭据全部来自进程环境，没有默认 Key 文件路径。Continuation Key 的默认持久化位置是：

```text
local-tmp/personal-assistant-real/continuation-key.env
```

该文件使用 `KEY=VALUE` env 格式。Continuation Key 不存在且 `HAIFA_PERSONAL_CONTINUATION_KEY` 未设置时，
脚本会生成随机 32 字节 Key，以 `HAIFA_PERSONAL_CONTINUATION_KEY=...` 写入，并把文件权限设为 `0600`。

从主仓根目录启动：

```bash
./scripts/start-real-environment.sh
```

需要显式验证 DeepSeek Chat Completions 路径时，可选择已配置的 Chat 模型；默认仍使用 Responses：

```bash
./scripts/start-real-environment.sh --default-model-id deepseek-chat-flash
```

需要覆盖 Provider 选择或 Continuation Key 位置时：

```bash
./scripts/start-real-environment.sh \
  --bailian-region cn-beijing \
  --web-search-provider aliyun \
  --web-fetch-provider browserless \
  --continuation-key-file /absolute/secure/personal-continuation.env
```

也可以使用对应环境变量：

```text
HAIFA_PERSONAL_CONTINUATION_KEY
HAIFA_PERSONAL_CONTINUATION_KEY_FILE
HAIFA_PERSONAL_DEFAULT_MODEL_ID
HAIFA_PERSONAL_BACKEND_LAUNCH_MODE
HAIFA_PERSONAL_WEB_SEARCH_PROVIDER
HAIFA_PERSONAL_WEB_FETCH_PROVIDER
ALIYUN_BAILIAN_REGION
HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST
```

停止、停止预检和重新构建：

```bash
# 只显示并验证目标，不停止进程
./scripts/start-real-environment.sh \
  --stop --dry-run

# 安全停止
./scripts/start-real-environment.sh --stop

# 端口释放后执行后端 clean package、重新构建前端并启动
./scripts/start-real-environment.sh --rebuild
```

macOS 与 Windows 共用：

```text
local-tmp/personal-assistant-real/last-start.json
local-tmp/personal-assistant-real/last-stop.json
local-tmp/personal-assistant-real/logs/
```
