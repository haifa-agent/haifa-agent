# Haifa Agent Model Core

## Parameter resolution

`DefaultModelParameterResolver` deterministically validates a typed request against a verified
`ModelBindingProfile`, applies explicit product defaults, and returns frozen `EffectiveModelParameters`. Unknown,
unavailable, out-of-range, or unsupported values fail closed. The resolver has no Personal Assistant or Coding Agent
dependency, so each product can own its preference vocabulary while reusing the same validation boundary.

模型目录、治理与确定性选择实现。

- `ImmutableModelCatalog` 校验 Provider/Model 全局唯一性并保持配置顺序；
- `DeterministicModelSelector` 按显式内部 Model ID 校验 Provider/Model 状态、能力和访问策略；
- 选择结果生成稳定配置摘要并形成 `ResolvedModelSnapshot`；
- Selector 由 `ModelDefinition.style` 定位同 Provider 下唯一 Binding，冻结 Style、实际 Dialect、Binding
  Endpoint 覆盖或 Provider 默认 Endpoint、共享 CredentialRef 与 `nativeStreaming`；
- 首版不进行隐式 fallback、轮询或动态路由；
- `InMemoryProviderHealthRegistry` 将瞬时健康与静态配置分离；
- `ModelApiStyles` 将内建 Style 确定性映射到 Adapter Type，Registry 再按精确 Adapter Type/Version 调用；
  Provider ID 不参与协议选择。

## 产品多模型公共能力

`StaticModelPlatform` 将现有静态 Catalog、确定性 Selector 和瞬时 Health 收敛为纯 Java
`ModelPlatform`：

- `listAvailable` 按可信 Tenant/Principal、必需能力和 `ModelAccessPolicy` 返回稳定有序的
  `ModelProviderView` / `ModelView`；
- View 只包含产品选择所需的 ID、版本、展示名、能力、Token 上限和健康摘要，不包含 Endpoint、
  `CredentialRef`、Provider Model ID、Options 或 Metadata；
- `select` 继续复用 `DeterministicModelSelector`，并按 Adapter Type 冻结精确 Adapter Version；
- 缺失 Adapter Version 时确定性返回 `ADAPTER_NOT_AVAILABLE`，不尝试其他 Provider 或 Model；
- Health 只用于展示和诊断，不过滤、不重排、不触发 fallback，也不改变模型快照摘要。

公共能力直接归属本模块，不新增其他 `haifa-agent-model-*` Maven Module。产品配置、Session/
Conversation 偏好、命令、HTTP API 和 UI 继续归属现有产品模块。

本阶段不提供 Catalog CRUD/持久化、动态发现、模型 Picker UI、自动路由、成本/配额、OAuth 或
Credential Lease 桥接。
