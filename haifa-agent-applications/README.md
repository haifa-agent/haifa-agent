# Haifa Agent Applications

面向具体 Agent 产品的高层应用适配层。这里可以组合 Kernel、Context、Execution 与 Runtime API，但不向底层模块反向泄漏产品概念或具体 Provider。

- `haifa-agent-project-application`：Project/Workspace Coding 产品能力的应用层组合；
- `haifa-agent-cli`：用于本地验证 Runtime、OpenAI-compatible 模型与受控文件工具的一次性 Coding Agent CLI。
