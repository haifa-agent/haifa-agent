# Haifa Agent 开发索引

本文件是主仓的 Coding Agent 入口，适用于整个根仓。它只保留稳定约束、事实源和任务路由；
详细设计、模块清单和命令矩阵转入对应文档。公开 `docs/` 属于主仓并遵守本文件；`private-docs/`
是独立私有文档仓 `haifa-agent-internal-docs` 的工作副本，`test-config/` 是独立私有仓库
`haifa-agent-test-config`；两者各自遵守其仓库的 `AGENTS.md`。

## 开始工作前

1. 先读根目录 [`README.md`](README.md)，确认当前已实现范围和常用入口。
2. 根据根/聚合 `pom.xml` 定位模块，并阅读最近的模块 `README.md`、`pom.xml` 和架构测试。
3. 涉及架构、模块边界或新能力时，先读 [`docs/architecture/overview.md`](docs/architecture/overview.md) 与
   [`docs/architecture/runtime-and-module-boundaries.md`](docs/architecture/runtime-and-module-boundaries.md)，再按 [`docs/README.md`](docs/README.md) 进入对应专题。
4. 开始修改前分别检查本次涉及仓库的 `git status --short`，保留用户已有改动。

## 仓库与交付边界

- 根仓功能开发必须使用 `feat-` 开头的特性分支，不得直接在 `main` 或 `dev` 上开发；默认向 `dev`
  发起 Pull Request。`0.1.2-SNAPSHOT` 开发线默认不设 baseline 分支：每个功能从最新 `origin/dev` 拉 `feat-*`，
  验证通过后向 `dev` 发起 PR。例外：相互交叉的多个任务可由主控智能体从 `dev` 拉一个 `feat-*` 集成分支统一汇合，
  子分支由主控验收后合入集成分支，集成分支再向 `dev` 发起 PR 并由人工合并；该集成分支只服务指定任务，合并进
  `dev` 后即删除。当前唯一的集成分支是 `feat-012-sh-sdk-gaps`（038-01、038-02）。
- Git Commit Message 和 GitHub Pull Request 说明必须使用英文。大任务 PR 说明最多 7 条；小修改最多
  2 条，验证结果也计入上限。
- GitHub 平台操作必须使用 GitHub CLI（`gh`）；本地 Git 和远端分支操作使用 `git`。
- `docs/` 是主仓公开文档目录，必须与相关代码通过同一分支和 Pull Request 演进；内部设计历史、Prompt、PRD、复盘和未定型方案继续保留在独立私有文档仓 `haifa-agent-internal-docs`，不复制进公开 `docs/`。
- `private-docs/` 是独立私有文档仓 `haifa-agent-internal-docs`；使用 `git -C private-docs ...` 并遵守其
  `AGENTS.md`，直接在其 `main` 上提交并推送，不建特性分支、不走 PR。
- `test-config/` 是独立私有仓库 `haifa-agent-test-config`；使用 `git -C test-config ...`并遵守其
  `AGENTS.md`，默认在其 `main` 上直接提交并推送。
- 根仓（含 `docs/`）、`private-docs/` 与 `test-config/` 必须分别检查、暂存、提交和推送；禁止把私有文档或
  私有测试配置混入主仓。
- 建立git worktree使用父目录统一在../haifa-agent-worktrees/
- Haifa Agent Super Harness（`../haifa-agent-super-harness`）是独立产品仓，只消费本地 Maven 仓库中的公开
  JAR/BOM `0.1.2-SNAPSHOT`。为其补 SDK 缺口时，以
  `private-docs/prompts/038-sdk-012-super-harness-gaps/README.md` 为需求索引，每项按上述 `feat-*` → `dev`
  流程交付；完成后将公开 JAR/BOM 发布到本地 Maven 仓库，并在 Super Harness 的 `docs/todo.md` 记录 SDK
  commit、PR 与验证状态。这类 PR 由人工审查合并，智能体不得自行合并。

## 事实源与任务路由

项目事实按以下顺序判断：

1. 当前任务的明确需求和验收标准；
2. 当前代码、自动化测试、根/聚合 `pom.xml` 与架构测试所体现的已实现行为；
3. 模块 `README.md` 与公开 [`docs/`](docs/README.md) 中的当前行为和稳定边界；
4. `private-docs/` 中的私有架构历史、专题设计、开发提示词和开发报告（仅作为历史/背景证据，不得覆盖当前代码事实）。

当前能力和产品入口见 [`README.md`](README.md)；Reactor 模块以根/聚合 `pom.xml` 为准；公开架构入口见
[`docs/architecture/overview.md`](docs/architecture/overview.md)；专题文档从 [`docs/README.md`](docs/README.md) 进入。
不得将未采纳或未实现的设计稿当作现有行为。公开文档与代码不一致时，以当前源码、POM 和测试为事实源并同步修正文档；
私有历史设计不能覆盖当前实现。`private-docs/` 与 `test-config/` 的版本状态仍在其各自独立仓库中核对。

## 全局实现约束

- 新能力先从当前产品的具体场景和失败模式出发。不得仅因“可审计、可追踪、企业级、未来扩展”而新增
  领域对象、持久化 Store、状态机、通用 SPI 或跨产品抽象；这些结构须有当前不可替代的不变量，或至少两个
  已确认消费者。审计/恢复/扩展只按实际副作用、重启需求和产品承诺建模，详细检查见
  [`docs/architecture/design-principles.md`](docs/architecture/design-principles.md)。
- Core 对象不是 JPA Entity，公共 API 不暴露框架、Provider SDK 或 Runtime Core 类型。Spring Framework
  从 Adapter/Integration 边界引入，Spring Boot 只进入 Starter 和最高层 Application。
- `AgentRun` 生命周期只由 Core 的命名行为决定；Runtime 不维护第二份状态转换表，也不绕过聚合行为。
- ID 和时间由可注入边界生成。持久化、事件、协议和对外输出统一使用 UTC epoch milliseconds；禁止在
  领域对象直接调用随机生成器或 `Instant.now()`，也不得将微秒/纳秒写入稳定载荷。
- Run 创建时冻结 Definition 版本和不可变配置快照。`AgentRunSnapshot` 是运行视图，`AgentRunResult`
  是最终结果，`AgentRunHandle` 只是便利层。公共 `AgentRunRequest` 不得注入 Tenant、Principal 或快照引用。
- Tool Call/Result 必须保留关联 ID；有副作用且结果不确定的工具不得盲目自动重放。
- Thinking/reasoning 必须遵守 `HAIFA-ADR-023` 及当前产品 Binding；签名、密文、opaque reference 和
  `PROTECTED_CONTINUATION` 不得进入公共 DTO、日志、Activity、Admin、测试输出或浏览器。ADR-023 精确矩阵
  允许 Adapter 从 DeepSeek 可读 CoT 或厂商明确生成的 summary 复制独立 display lane；它必须经精确 Binding 和
  Contract Test，不能复用、解密、替代或从 display lane 重建 continuation。
- 测试、异常和日志不得输出 API Key、完整 Prompt、凭据明文或原始供应商响应。
- 新增依赖前确定归属纯 Java BOM 还是 Spring BOM，并确认没有破坏模块边界。

## 修改与测试工作流

- 优先做满足需求的最小变更；不顺带重命名、重排包结构或扩大公共 API。
- 所有新建、修改或由工具生成的文件必须采用 UTF-8 无 BOM 编码（UTF-8 without BOM），严禁生成
  UTF-8 with BOM 或 UTF-16LE。在 Windows 环境下使用脚本写文件时，禁止直接使用 Windows
  PowerShell 5.1 的重定向操作符 `>`（默认 UTF-16LE）或 `Out-File -Encoding utf8`（默认带 BOM）；
  优先使用 Agent 文件写入工具、Python 脚本或指定 `[System.Text.UTF8Encoding]::new($false)` 写入。
- 脚本的公共业务逻辑使用 Python，`.ps1` 与 `.sh` 只作为原样透传参数的薄入口；两端统一使用
  小写位置动作和 `--kebab-case` 长参数。
- 修改公共行为时补充相邻单元测试；修改依赖边界时更新 ArchUnit/Maven Enforcer 约束。Surefire/Failsafe
  命名与分层见 [`build-support/README.md`](build-support/README.md)。
- 先运行受影响模块，再运行与 CI 一致的全仓验证。行为、边界或用法变化时同步更新模块
  `README.md`；对用户可见的版本变化再更新 `CHANGELOG.md`。
- 不修改生成目录 `target/`，不提交日志、IDE 配置、运行产物或密钥文件。

## 构建与验证

本地开发优先使用 `build-support/scripts/invoke-haifa-maven.ps1`（Windows）或对应的 `.sh`
入口，以获得固定并发、超时分类和脱敏指标。

### Windows PowerShell 参数规则

- Maven 参数分隔符必须写成字面量 `'--'`；每个 `-D...` 参数必须整体加单引号。
- 长命令或动态参数使用字符串数组和 splatting；禁止拼接命令字符串或使用 `Invoke-Expression`。

```bash
# 精确测试：按任务替换示例中的模块名和测试类
./build-support/scripts/invoke-haifa-maven.sh --layer L1 -- \
  -pl :haifa-agent-runtime-core -am \
  -Dtest=RuntimeCoreTest -Dsurefire.failIfNoSpecifiedTests=false test

# 受影响模块完整测试
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- \
  -pl :haifa-agent-runtime-core -am test

# 同一 SHA 的最终门禁
./build-support/scripts/invoke-haifa-maven.sh --layer L3 -- \
  -Pci-fast clean verify
```

精确测试使用 L1；模块完整测试和全仓增量测试使用 L2；最终门禁使用 L3。Windows 上 L2/L3 构建成本较高，
开发过程中优先运行受影响模块的构建与模块验证；仅在最终交付阶段运行 L2/L3，除非用户另行要求提前执行。不得按本机 CPU 数直接使用
`-T 1C`。慢测、Integration、Release Artifact 和发布验证的命令及前置条件见
[`build-support/README.md`](build-support/README.md)。`ci-integration-only` 和 `release-artifacts` 不能单独作为代码正确性门禁。
运行 `-Prelease verify` 或 `-Prelease-artifacts verify` 必须使用 `-pl` 指定受影响模块，禁止全仓直接执行。

真实 Provider 测试必须有显式开关和凭据，会访问外部服务并可能产生费用；未获得当前任务授权时不得运行。
普通开发和 CI 默认只使用本地 Stub/Fake。

## 完成标准

交付前确认：

- 修改范围与任务一致，未覆盖用户已有改动；
- 模块依赖方向和纯 Java 边界未被破坏；
- 受影响测试通过，架构测试未被绕过；
- 同一 Git SHA 的 L3 `-Pci-fast clean verify` 通过，或已明确说明未运行/未通过的原因；
- 文档、日志和测试输出不包含秘密或敏感原文；
- 最终说明列出修改文件、验证命令和任何剩余风险。
