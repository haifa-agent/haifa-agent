# Release Notes

仓库根目录的 [CHANGELOG](../../CHANGELOG.md) 是 Release History 的权威文档。

本页对应源码版本 `0.1.5`。Coding Agent 默认启用统一上下文策略，并可用 legacy 策略保留历史读回。
从 0.1.4 升级、恢复旧暂停 Run 和回退的步骤见 [升级说明](upgrading.md#014-升级到-015)。

标准发布流程构建 Windows x64 Coding Agent、Windows x64 Personal Assistant、Personal Assistant Linux x64 服务器包
和 SDK 文件仓库。Coding Agent Linux x64 便携包可按 [打包说明](../../dist-support/README.md) 单独构建。
制品是否已发布，以 [GitHub Releases](https://github.com/haifa-agent/haifa-agent/releases) 为准。

本公开文档与源码一起版本化。因此，当你阅读某个 Feature Branch 上的文档时，应把它理解成描述该 Branch，而不是描述已经发布到公共 Repository 的 Artifact。

在使用 Non-SNAPSHOT Dependency 之前，请先确认对应 Release / Tag 与 Artifact Repository，避免直接复制当前开发分支中的 Snapshot 坐标。
