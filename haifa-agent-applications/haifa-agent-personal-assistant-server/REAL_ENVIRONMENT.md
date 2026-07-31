# Personal Assistant 真实环境快速启动

这套方案按前后端分离方式运行，不使用 Nginx：

| 组件 | 地址 | 启动方式 |
| --- | --- | --- |
| Personal Assistant Web | `http://127.0.0.1:20000/` | Node.js `serve` 直接提供 `dist/` |
| Personal Assistant Server | `http://127.0.0.1:20001/` | Spring Boot executable JAR |
| Utility MCP Server | `http://127.0.0.1:20002/mcp` | Maven Spring Boot Plugin |

Web 在浏览器中直接请求 `http://127.0.0.1:20001/api/v1`。Server 已限定允许来自
loopback `20000` 的 Origin，方案中没有反向代理。

## 1. 准备条件

- Java 21；
- Maven 可通过 `mvn.cmd` 使用；
- Node.js 22.x、npm 10.x；
- 主仓：`D:\workspace\haifa-agent`；
- Utility MCP 仓库：
  `D:\workspace\haifa\haifa-ai\haifa-ai-utility-mcp-server`；
- DeepSeek Key 文件：`D:\workspace\ss-deepseek.txt`，文件中只放 Key 本身。
- Aliyun IQS Key 文件：`D:\workspace\ss-aliyun-iqs.txt`，文件中只放 Key 本身；
- Personal Skill 根目录：`D:\agents\hermes-agent\optional-skills\finance`，其直接子目录分别包含
  `SKILL.md`。

Key 文件不能提交到 Git，也不要把内容复制到命令历史、日志或文档。

## 2. 一键启动

在普通 PowerShell 中执行：

```powershell
Set-Location D:\workspace\haifa-agent
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1
```

脚本会依次完成：

1. 校验本机工具、DeepSeek/IQS Key、finance Skill 根目录和 Utility MCP 目录；
2. 首次运行时生成随机 32 字节 Continuation Key，并持久化到
   `D:\workspace\ss-haifa-personal-continuation.txt`；
3. 只在后端 JAR 不存在时构建后端；
4. 只在 `node_modules` 不存在时执行 `npm ci`，只在 `dist` 不存在时构建前端；
5. 启动或复用健康的 20002 Utility MCP；
6. 以真实 `deepseek-v4-flash`、Aliyun IQS Web Tool、finance Skills 和外部 MCP 模式启动
   20001 后端；
7. 用 Node.js `serve` 启动 20000 前端；
8. 等待三个 HTTP 健康检查成功，并输出 PID、各组件工作目录、数据/日志目录、访问
   地址和状态文件位置。

因此日常再次启动不会重复执行 npm 构建。脚本不会杀掉端口上的未知进程；如果端口被
非目标服务占用，它会直接失败并保留现场。

如果 PowerShell 的脚本执行策略阻止本次运行，可仅对当前进程临时放开：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

## 3. 更新代码后重建

先使用脚本自身的停止功能关闭旧环境：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 -Stop
```

停止前，脚本会同时核对 `last-start.json` 记录的 PID、端口当前监听 PID、进程名和
命令行身份标识（组件工作路径或 MCP 主类）。任一项不一致都会拒绝停止，不会把
同端口上的其他程序当作本环境。
需要只查看将要停止的进程时：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 -Stop -WhatIf
```

状态文件缺失、记录 PID 已过期或进程身份校验无法通过时，可按三个固定端口的当前监听
进程显式强制停止：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 -Stop -Force
```

`-Force` 只允许与 `-Stop` 一起使用。它会把状态或身份不一致降级为警告，并强制结束
当前监听 `20000`、`20001`、`20002` 的进程，因此可能终止占用这些端口的非目标程序。
需要先核对强制停止目标时，可使用 `-Stop -Force -WhatIf`。

确认 20000、20001、20002 均已释放后，再执行：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 -Rebuild
```

`-Rebuild` 会重新构建后端和前端。为了避免 Windows 上正在运行的 JAR 文件锁和
旧页面产物混用，只要三个端口中任意一个仍被占用，重建就会拒绝执行。

需要使用非默认 Key 或 MCP 路径时：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 `
  -DeepSeekKeyFile 'D:\secure\deepseek.txt' `
  -AliyunIqsKeyFile 'D:\secure\aliyun-iqs.txt' `
  -ContinuationKeyFile 'D:\secure\personal-continuation.txt' `
  -PersonalSkillRoot 'D:\agents\hermes-agent\optional-skills\finance' `
  -UtilityMcpDirectory 'D:\src\haifa-ai-utility-mcp-server'
```

Continuation Key 文件必须长期保留。删除或更换它会使旧的加密 continuation token
无法恢复；脚本从不打印 Key 内容。

## 4. 当前真实能力配置

后端使用以下关键配置：

```text
HAIFA_PERSONAL_DEFAULT_MODEL_ID=deepseek-v4-flash
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
HAIFA_PERSONAL_WEB_ENABLED=true
HAIFA_PERSONAL_WEB_CREDENTIAL=env://ALIYUN_IQS_API_KEY
HAIFA_PERSONAL_SKILL_ROOT=D:\agents\hermes-agent\optional-skills\finance
HAIFA_PERSONAL_MCP_MODE=external
HAIFA_PERSONAL_MCP_ENDPOINT=http://127.0.0.1:20002/mcp
HAIFA_PERSONAL_MCP_ALIAS_NAMESPACE=utility
HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true
HAIFA_PERSONAL_PYTHON_PATH='D:\Program Files\Python311\python.exe'
```

允许的 Utility MCP 工具共 19 个：

```text
location_search, weather_current, weather_forecast, air_quality,
time_now, time_convert, currency_rate, currency_convert,
holiday_list, holiday_next, workday_is_workday, workday_add,
calculate, unit_convert, wikipedia_search, wikipedia_summary,
microsoft_docs_search, microsoft_docs_fetch, microsoft_code_sample_search
```

`HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true` 只确认当前本机部署允许启动受控
宿主进程；具体命令或脚本调用仍需经过 Runtime 的 exact approval。

## 5. 验证与故障排查

启动完成后检查：

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20002/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20001/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:20000/
```

运行记录和日志位于：

```text
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\last-start.json
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\logs\
```

正常停止直接使用启动脚本：

```powershell
& .\haifa-agent-applications\haifa-agent-personal-assistant-server\scripts\start-real-environment.ps1 -Stop
```

停止结果写入：

```text
D:\workspace\haifa-agent\local-tmp\personal-assistant-real\last-stop.json
```

停止后可以再次检查端口：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -in 20000, 20001, 20002
```

## 6. Web Tool 与 finance Skills

脚本读取 `D:\workspace\ss-aliyun-iqs.txt`，只向后端子进程注入
`ALIYUN_IQS_API_KEY`。Personal Profile 同时允许 `web_search` 和 `web_fetch`，两者分别精确绑定
公共模块中的 `web.search` 与 `web.fetch`，并继续经过 Runtime Tool Pipeline、Policy、Approval 和
Credential lease；没有隐式 Provider fallback。

`D:\agents\hermes-agent\optional-skills\finance` 是 Skill Source 根目录，不是一个 Skill 包。启动时会
发现它下面直接包含 `SKILL.md` 的子目录（当前包括 `3-statement-model`、`comps-analysis`、
`dcf-model`、`excel-author`、`lbo-model`、`merger-model`、`pptx-author` 和 `stocks`）。
其中带脚本资源的 `dcf-model`、`excel-author` 和 `stocks` 按当前 Skill 安全基线标记为
`REVIEW_REQUIRED`，不会进入模型可用 Catalog；其余五个 finance Skill 会直接启用。配置可信目录
只表示允许发现和读取包，不等同于批准包内脚本。

## 7. macOS 一键启动

macOS 使用同目录的 `start-real-environment.sh`，功能与 PowerShell 脚本对齐：

- 构建或复用 Personal Server JAR 和 Personal Web `dist`；
- 启动或复用 Utility MCP、Personal Server 和 Personal Web；
- 固定使用 `127.0.0.1:20002/20001/20000` 并等待 HTTP 健康检查；
- 密钥只注入后端子进程，不写入状态文件或日志；
- 未知进程占用端口时直接失败；
- 停止前核对 `last-start.json`、监听 PID、进程名和命令行身份标识。

要求 macOS 已安装 Java 21、Maven、Node.js 22.x、npm 10.x，以及系统命令
`curl`、`lsof`、`openssl`。默认读取：

```text
~/workspace/ss-deepseek.txt
~/workspace/ss-aliyun-iqs.txt
~/workspace/ss-haifa-personal-continuation.txt
~/workspace/haifa/haifa-ai/haifa-ai-utility-mcp-server
~/agents/hermes-agent/optional-skills/finance
```

前三个分别是 DeepSeek Key、Aliyun IQS Key 和持久 Continuation Key。Continuation Key 不存在时，
脚本会生成随机 32 字节 Key，并把文件权限设为 `0600`。

从主仓根目录启动：

```bash
./haifa-agent-applications/haifa-agent-personal-assistant-server/scripts/start-real-environment.sh
```

路径不同时显式覆盖：

```bash
./haifa-agent-applications/haifa-agent-personal-assistant-server/scripts/start-real-environment.sh \
  --deepseek-key-file /absolute/secure/deepseek.txt \
  --aliyun-iqs-key-file /absolute/secure/aliyun-iqs.txt \
  --continuation-key-file /absolute/secure/personal-continuation.txt \
  --utility-mcp-directory /absolute/src/haifa-ai-utility-mcp-server \
  --personal-skill-root /absolute/skills/finance
```

也可以使用对应环境变量：

```text
HAIFA_DEEPSEEK_KEY_FILE
HAIFA_ALIYUN_IQS_KEY_FILE
HAIFA_PERSONAL_CONTINUATION_KEY_FILE
HAIFA_UTILITY_MCP_DIRECTORY
HAIFA_PERSONAL_SKILL_ROOT
HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST
```

停止、停止预检和重新构建：

```bash
# 只显示并验证目标，不停止进程
./haifa-agent-applications/haifa-agent-personal-assistant-server/scripts/start-real-environment.sh \
  --stop --dry-run

# 安全停止
./haifa-agent-applications/haifa-agent-personal-assistant-server/scripts/start-real-environment.sh --stop

# 三个端口释放后执行后端 clean package、重新构建前端并启动
./haifa-agent-applications/haifa-agent-personal-assistant-server/scripts/start-real-environment.sh --rebuild
```

macOS 与 Windows 共用：

```text
local-tmp/personal-assistant-real/last-start.json
local-tmp/personal-assistant-real/last-stop.json
local-tmp/personal-assistant-real/logs/
```
