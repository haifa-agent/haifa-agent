# Haifa Agent Model Core

模型目录、治理与确定性选择实现。

- `ImmutableModelCatalog` 校验 Provider/Model 全局唯一性并保持配置顺序；
- `DeterministicModelSelector` 按显式内部 Model ID 校验 Provider/Model 状态、能力和访问策略；
- 选择结果生成稳定配置摘要并形成 `ResolvedModelSnapshot`；
- Provider dialect、地域/Endpoint scope 和模型 capability profile 作为 options 一并冻结，运行期不得回读当前目录补齐；
- 首版不进行隐式 fallback、轮询或动态路由；
- `InMemoryProviderHealthRegistry` 将瞬时健康与静态配置分离；
- `ModelAdapterRegistry` 按 Adapter Type 解耦协议实现。
