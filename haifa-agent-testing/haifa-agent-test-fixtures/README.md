# Haifa Agent Test Fixtures

跨模块共享的小型安全 Fixture 模块。Fixture 是测试开始前复制到独立运行目录的不可变输入，不是测试
运行生成的状态或证据。

准入条件：

- 至少被两个测试模块或多个产品级 Suite 复用；
- 规模小、可人工审查、无需 Git LFS；
- 不包含秘密、个人/生产数据、真实 Host Path、完整 Prompt、reasoning 或原始 Provider 响应；
- 不包含 `target/`、缓存、运行数据库、Trace、Transcript、lock 或 Quarantine；
- 外部来源内容必须记录来源、版本、许可证和内容摘要；
- 脚本默认不得访问公网，也不得操作复制后的 Fixture 根目录之外。

只被单个模块使用的 Fixture 应继续保存在该模块的 `src/test/resources`。大型、私有或受许可证限制的
数据集应保存在独立私有仓库或对象存储中，并以版本和内容摘要引用。

当前共享 Fixture：

- Coding E2E 的工程 Fixture 暂时继续位于 CLI 相邻测试资源，迁移前不复制；
- `ascii-art` Skill 属于私有 Live 输入，位于独立 `test-config`。
- `fixtures/autonomous-delivery/`：十七个合成 Coding Case 的 Prompt、初始 Workspace、外置
  Acceptance、版本化 Catalog、Harness Protocol 和结果 Schema。11～17 覆盖非 Go 临时目录、
  非 ZIP 安全失败副作用、SQLite 原子性、只读分析、合理第二次重试、非幂等 outcome unknown 和
  UNKNOWN Intent；资源不含运行产物。Case 03/04 的修正版 Acceptance 使用 `2.0.0`，不得与旧版本
  混合比较。
