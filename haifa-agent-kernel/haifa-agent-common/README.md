# Haifa Agent Common

提供无框架基础类型：标识符契约、可注入的 UUIDv7 生成器、时间提供者、可比较的 `major.minor` Schema 版本以及基础异常。

- 允许依赖：JDK；测试范围内允许 JUnit 和 AssertJ。
- 禁止依赖：其他 Haifa Agent 模块、Spring、Agent 领域对象和基础设施 SDK。
- 公共 API 位于 `io.haifa.agent.common` 下，模块不包含产品语义。
- `IdentifierGenerator` 和 `TimeProvider` 是生成边界；领域对象只接收已经生成的 ID 与时间。
- `UuidV7IdentifierGenerator` 可注入 `Clock` 和随机源，支持确定性测试与时间有序 ID。
- `SecureFilePermissions` 提供不跟随符号链接的跨平台存储权限基线：POSIX 目录/文件为
  `0700/0600`，ACL 文件系统只授予当前进程用户；无法设置并复核时抛出错误。
