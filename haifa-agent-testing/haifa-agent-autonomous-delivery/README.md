# Haifa Agent Autonomous Delivery (最小能力防退化评测)

`haifa-agent-autonomous-delivery` 是 Haifa Agent 四层测试体系（fixtures / integration / critical-path / autonomous-delivery）中的第 4 层。

## 模块定位与职责边界

1. **最小能力防退化评测**：专注内部核心能力阶梯回归，确保 Agent 在单点修复、局部功能、跨模块协调等阶段性能力上不退化；
2. **不承担业界完整 Benchmark 平台职责**：学术/业界 Standard Benchmark（如 SWE-bench Verified、aider-polyglot 等）统一由独立工程 `haifa-agent-evals` 承担；
3. **能力阶梯设计**：
   - 规划 6 个难度等级（L1 单点修复 ～ L6 开放式自主交付）+ 2 类正交变体（失败恢复、回归保护）；
   - 难度分布呈金字塔型：L1~L4 基础能力占约 70%，L5~L6 复杂能力占约 30%；
   - 每题带三维诊断标签（定位难度 / 修改跨度 / 验收复杂度，各 1~5 分）；
   - 详细规范请参阅：
     - 本模块 [`AUTONOMOUS_DELIVERY_LADDER_SPEC.md`](AUTONOMOUS_DELIVERY_LADDER_SPEC.md)
     - 架构设计文档 [`docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md`](../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-capability-ladder-design.md)

## 当前状态

23 题已在独立资产仓 [`haifa-agent-autonomous-delivery-assets`](https://github.com/haifa-agent/haifa-agent-autonomous-delivery-assets) 作者化（L1×5、L2×5、L3×4、L4×4、L5×3、L6×2，其中 L6-02 为 Java/Maven 题），当前资产版本 `2026.09.11.2`。每个题目目录 `cases/<caseId>/` 自包含 `case.yaml`（单源元数据）、`prompt.txt`（英文题面）、`base-workspace/`（初始工作区）、`reference/`（参考解）与 `acceptance.py`（工作区之外执行的黑盒验收，输出单行 JSON）；资产仓的 `cases/` 由其 `authoring/` 生成，不手工编辑。

验收约定（资产仓 `README.md` 为准）：卫生检查只守护该题承诺的内容（既有测试与受保护文件逐字节不变、改动源文件在可编辑范围与改动预算内），新增测试文件、工具缓存与临时产物不判失败；每个隐藏检查在独立解释器中带独立超时运行；性能检查与本机 O(n) 基准校准而非固定秒数；L3/L4 共用一个带分层约束的中等规模工程，题面不给文件路径。

本模块只保留 Runner、共享结果契约和不可变资产锁。`assets.lock.json` 固定 GitHub 仓、完整 Git commit SHA 和 manifest SHA-256；`tools/fetch_assets.py` 是唯一下载入口，Maven 默认测试不会访问网络。先显式下载，再将已校验目录传给 `tools/run_case.py`：

```powershell
python haifa-agent-testing/haifa-agent-autonomous-delivery/tools/fetch_assets.py `
  --cache-dir D:\haifa-agent-cache\autonomous-delivery --print-path
python haifa-agent-testing/haifa-agent-autonomous-delivery/tools/run_case.py `
  --assets-dir D:\haifa-agent-cache\autonomous-delivery\assets-34674c775b68d84163f508bf7e6364cad205de93 `
  --case L1-01 --mode nop
```

`agent` 模式的 `--agent-command` 使用 `{workspace}` 指向每次运行的新工作区，也可使用 `{prompt_file}` 读取该题英文题面；题面会先被复制到资产目录之外的临时文件再交给 Agent（`reference/` 与 `acceptance.py` 就在题面旁边），运行结束即删除。Runner 不会把题面或模型原始输出写进 report，子进程输出一律按 UTF-8 解码。

Runner 自身的单元测试离线运行：

```powershell
python -m unittest discover -s haifa-agent-testing/haifa-agent-autonomous-delivery/tools/tests -p "test_*.py"
```

## 一键评测

`tools/run-ladder.ps1`（Windows）与 `tools/run-ladder.sh`（macOS/Linux）是"先体检、再全量评测"的单一入口，公共逻辑在 `tools/run_ladder.py`。

必需环境变量（缺失时脚本打印可直接粘贴的设置命令并退出，不会开始评测）：

| 变量 | 说明 |
| --- | --- |
| `HAIFA_LADDER_ALLOW_REAL_PROVIDER` | 必须为 `true`：评测调用真实 Provider 并产生费用 |
| `HAIFA_LADDER_AGENT` | Coding Agent 启动器；未设置时自动发现 `~/.haifa-agent/coding/haifa-coding(.cmd)` |
| 凭据变量 | 由模型 id 推断（`glm-*` → `BIGMODEL_API_KEY`、`kimi-*` → `KIMI_API_KEY` 等）；`deepseek`/`gpt-*` 等经 `~/.haifa-agent/auth.json` 认证的模型无需设置 |

可选：`HAIFA_LADDER_MODEL`、`HAIFA_LADDER_APPROVAL`（默认 `auto`，只接受 `auto` 或 `deny`：评测以关闭的 stdin 运行 Agent，`ask` 会让每次审批被拒，体检阶段直接拒绝）、`HAIFA_LADDER_CASES`、
`HAIFA_LADDER_REPEAT`、`HAIFA_LADDER_TIMEOUT_SCALE`、`HAIFA_LADDER_OUTPUT`、`HAIFA_LADDER_CACHE_DIR`、
`HAIFA_LADDER_ASSETS_DIR`；每个变量都有同名 `--kebab-case` 参数，参数优先。

`--assets-dir` 指定的目录必须与 `assets.lock.json` 的 manifest 摘要一致，否则体检直接失败：题集不是锁定的那一版，结果无法与基线比较；确实要用未锁定的题集时加 `--allow-unpinned-assets`，体检会以 `UNPINNED` 标注继续。

```powershell
# 只体检：环境变量、工具链、按锁下载并校验资产、Runner 单测、NOP/oracle 门
.\haifa-agent-testing\haifa-agent-autonomous-delivery\tools\run-ladder.ps1 check

# 体检通过后评测全部 23 题
.\haifa-agent-testing\haifa-agent-autonomous-delivery\tools\run-ladder.ps1 run

# 彩排：用参考解跑通整条链路，不调用模型、不产生费用
.\haifa-agent-testing\haifa-agent-autonomous-delivery\tools\run-ladder.ps1 run --rehearse --skip-gates
```

评测过程中的输出：每题开始时的三维标签与变体、Agent 预算与日志文件名、每 15 秒一次的心跳（已用时间/预算、
输出行数、最后一行输出）、每题结束时的状态（`PASSED` / `FAILED` / `INCOMPLETE_BUDGET`）、通过的检查数、
Agent 耗时与退出码、改动的源文件；失败时列出失败检查名及其原因。每题之后打印总进度与累计通过率，
结束时按 level 汇总，并写出 `ladder-report.json`、`run-records.jsonl` 与每题 Agent 日志；报告的 `mode` 区分 `agent` 与 `rehearse`，彩排结果不会被误读为真实评测。

Windows 上 `haifa-coding.cmd` 会自动替换为同目录的 `haifa-agent.jar`：批处理启动器经 cmd.exe 传参会把
多行题面截断到第一行。控制台与报告不输出题面、凭据值或完整模型响应，日志中出现的凭据值会被替换为 `***`。

实施契约（工件布局、分级配方、变体机制、验收与结果契约、题目质量门、实施切片）见
[`34-autonomous-delivery-ladder-case-authoring-prompt.md`](../../docs/prompts/34-testing-architecture-simplification/34-autonomous-delivery-ladder-case-authoring-prompt.md)；
已确认决策：题面语言英文、L6 保留 1 道 Java/Maven 题、历史 17 题冻结为 Legacy、失败恢复变体以“初始红 + 一次错误修复尝试”实现（不依赖工作区 Git 历史）。
每题验收输出遵循 [`acceptance-result.schema.json`](../haifa-agent-test-fixtures/src/main/resources/fixtures/autonomous-delivery/schemas/acceptance-result.schema.json)（与冻结的历史 17 题结果共用一个 `oneOf` 契约），并由 `tools/run_case.py` 在每次运行后校验结果契约；L4/L5 题带 AST、分层依赖与冻结文件约束检查，L3/L5/L6 题带改动文件数与时间预算检查。历史 17 题 fixture 冻结保留在 `haifa-agent-test-fixtures`。
