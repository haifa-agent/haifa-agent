# Scripts

本目录保存面向 Haifa Agent 产品运维、本地打包和运行时可靠性分析的公共脚本入口。所有入口遵循
“Python 公共实现 + PowerShell/Shell 薄入口” 规范。

测试驱动、Terminal 验收与平台冒烟脚本属于测试基础设施，统一维护于
[`haifa-agent-testing/scripts/`](../haifa-agent-testing/scripts/README.md)。

---

## 1. Coding Runtime 可靠性分析

`analyze-coding-runtime.ps1`（Windows）和 `analyze-coding-runtime.sh`（macOS/Linux）只读打开 Coding
Agent 的 `runtime.db`，以最新 `runtime_event.occurred_at` 为窗口终点，输出脱敏的 Run/Tool/失败分类、
Git/GH 目标、Operation Family 和恢复指标。公共逻辑位于 `analyze_coding_runtime.py`。

报告不会包含 Prompt、完整命令、命令输出、Credential、Provider 原始响应、Run/Tool 原始 ID 或主机绝对
路径；Command 和 Identifier 只输出 SHA-256 摘要。输出必须位于数据目录、源码仓库和独立 docs 仓库
之外，且脚本拒绝覆盖已有报告。

```powershell
.\scripts\analyze-coding-runtime.ps1 `
  --data-root C:\Users\example\.haifa-agent\coding\data `
  --latest-hours 4 `
  --output D:\haifa-agent-test-runs\coding-runtime-report.json
```

```bash
./scripts/analyze-coding-runtime.sh \
  --data-root /srv/haifa/coding/data \
  --latest-hours 4 \
  --output /tmp/haifa-coding-runtime-report.json
```

---

## 2. Haifa Coding Agent 本地发行打包

`package-local-coding-agent.sh`（macOS/Linux）和 `package-local-coding-agent.ps1`（Windows）构建
`haifa-agent-cli` shaded JAR，并生成包含启动脚本、JAR、无密钥默认配置、`data/transcripts` 与 `logs`
目录的可搬运目录。公共逻辑位于 `package-local-coding-agent.py`。

```bash
./scripts/package-local-coding-agent.sh
./scripts/package-local-coding-agent.sh "$HOME/.local/haifa-coding-agent"
```

```powershell
.\scripts\package-local-coding-agent.ps1
.\scripts\package-local-coding-agent.ps1 D:\tools\haifa-coding-agent
```

详细配置与安全边界见 [`haifa-agent-cli/README.md`](../haifa-agent-applications/haifa-agent-cli/README.md)。

---

## 3. Personal Assistant 真实联调环境

`start-real-environment.ps1`（Windows PowerShell）和 `start-real-environment.sh`（macOS/Linux）
从仓库根目录启动、复用、验证或停止 Personal Assistant 的真实联调环境。两个入口统一调用 `real_environment.py`。
运行方法和安全边界见
[`haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md`](../haifa-agent-applications/haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md)。

```powershell
.\scripts\start-real-environment.ps1
.\scripts\start-real-environment.ps1 --default-model-id deepseek-chat-flash
.\scripts\start-real-environment.ps1 --stop --dry-run
```

```bash
./scripts/start-real-environment.sh
./scripts/start-real-environment.sh --default-model-id deepseek-chat-flash
./scripts/start-real-environment.sh --stop --dry-run
```

---

## 4. 脚本单元测试

本目录工具脚本的单元测试位于 `scripts/tests/`，离线运行命令：

```bash
python -m unittest discover -s scripts/tests -p "test_*.py"
```
