# Haifa Agent Context

Pure Java context intermediate representation, prompt layering, single-call token budgeting,
deterministic selection, derived-asset text references, and redacted context tracing.
Conversation summaries remain rebuildable compression products. Governed long-term Memory stays in
the Memory capability and reaches this module only through the closed `MemoryReferenceContent` IR.

The module depends only on Common, Core, and Model API. It never emits provider DTOs and does not
invoke a model. Runtime is the sole owner of the `AgentContext` to `ModelMessage` conversion.

`BoundedTextAssetProcessor` 提供一个同步、无 I/O 的最小内容处理边界：可信调用方传入已经授权读取的
Asset 字节后，它只对 `text/plain`、`text/markdown` 和 `application/json` 做严格 UTF-8 解码，
并按冻结的字节/字符预算生成现有 `AssetDerivedTextContent`。非法 UTF-8、NUL、空白结果和超限输入
都会使用不含正文的稳定错误码失败；UTF-8 BOM 会被移除。它不拥有 Asset Store，不自动读取
`AssetRef`，也不提供 OCR、ASR、PDF/Office 解析、异步任务或原生多模态模型输入。

`PromptLayer.SKILL` 是低于 Identity、Safety、Policy、Tool Protocol 和 Runtime Instructions 的最弱可移除
指令层。Context 不依赖 Skill API；Runtime/Application Adapter 只在 Skill 已冻结并受控激活后把内容映射为
现有 `PromptComponent`。
