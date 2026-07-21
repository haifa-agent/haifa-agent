# Haifa Agent

Haifa Agent 是面向 Java / Spring 生态的通用 Agent Runtime 与 Agent 产品开发平台。

当前仓库处于 `0.1.0-SNAPSHOT` 的架构骨架阶段。工程仅初始化已经具有明确职责和可测试 API 的基础模块，后续模块将按依赖顺序逐步加入，避免产生无意义的空模块。

## 当前模块

```text
build-support/
  haifa-agent-bom/             纯 Java 与内部模块依赖管理
  haifa-agent-spring-bom/      Spring 生态依赖管理
haifa-agent-contract/          对外协议对象
haifa-agent-kernel/
  haifa-agent-common/          无框架基础类型
  haifa-agent-core/            稳定 Agent 领域模型
  haifa-agent-runtime-api/     Runtime 生命周期契约
```

依赖方向固定为：

```text
common <- core <- runtime-api
   ^
   └──── contract
```

`contract` 不依赖 `core`，所有已初始化的 Kernel 模块均保持纯 Java。

## 构建

要求 Java 21。仓库自带固定为 Maven 3.9.15 的 Wrapper，无需预先安装 Maven。

```bash
./mvnw -T 1C -Pci-fast clean verify
```

常用命令：

```bash
./mvnw test
./mvnw -pl haifa-agent-kernel/haifa-agent-core -am verify
./mvnw -Prelease verify
```

Windows PowerShell 中将 `./mvnw` 替换为 `.\\mvnw.cmd`。

## 架构文档

项目定位与模块边界见 [`docs/01-product-positioning-and-overall-architecture.md`](docs/01-product-positioning-and-overall-architecture.md) 和 [`docs/02-repository-modules-and-dependencies.md`](docs/02-repository-modules-and-dependencies.md)。
