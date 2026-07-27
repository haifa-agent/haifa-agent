# Haifa Coding Terminal

Coding Agent 的 JLine 交互产品层。布局、信息层级和交互严格以
`docs/prd/pi-coding-agent-terminal-low-fi-prototype` 评审原型为准，不自行发明 Sidebar、
Dashboard、Scenario toolbar 或另一套产品 UI。

## 模块定位

三个模块的职责固定如下：

```text
haifa-agent-cli
  最高层生产装配、参数/配置、稳定 Workspace 身份、唯一 shaded 可执行 JAR
        |
        v
haifa-agent-coding-terminal
  JLine 生命周期、编辑器、KeyMap、Selector、Reducer、Renderer
  只通过 CodingSessionClient 读取和提交产品事实
        |
        v
haifa-agent-coding-agent
  CodingSessionService、Project/Workspace、Session/Queue/Cursor、Policy、
  MyBatis/SQLite 产品持久化
```

Terminal 不装配第二套 Runtime，不直接访问 SQLite、文件系统或进程，不依赖 Runtime Core、
SQLite Mapper、Sandbox Provider、`ProcessBuilder` 或 CLI 包，也不产生第二个胖 JAR。最终可执行制品
只有：

```text
haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0-SNAPSHOT.jar
```

## 原型映射与交互

固定信息顺序：

```text
Header
Loaded Resources / Diagnostics
Transcript
Pending Messages
Status
Widgets Above
Editor or Selector
Widgets Below
Footer
```

终端采用 JLine 3.30.0 的 `Terminal`、`LineReader`、`Display` 与 JNI Terminal Provider。Runtime
回调只写入有界 Action Queue；Reducer、Renderer、`LineReader` 和 `Display` 均由单一 UI 线程访问。
默认保留 scrollback，不进入 alternate screen；关闭时恢复 Attributes 和 Signal Handler。

- 普通首条消息创建真实 Coding Session/Run；
- Idle Enter 提交新 Turn，Active Enter 发送 Steer；
- Active Alt+Enter 写入持久 Follow-up Queue，Alt+Up 选择并恢复待发消息；
- Escape/Ctrl+C 请求取消活动 Run；
- `/resume` 选择并打开真实 Session；
- pending Approval 在同一 JLine input owner 中 approve/reject；
- `/settings`、`/trust`、`/model`、`/login`、`/tree`、`/compact` 在没有真实 API 时返回
  `CAPABILITY_NOT_IMPLEMENTED`，不显示装饰性选择器；
- `/quit` 退出；活动 Run 下 EOF 显示明确的退出选择。

## 构建与启动

```powershell
java -version # 必须是 Java 21
.\mvnw.cmd -pl :haifa-agent-cli -am package

$jar = ".\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar"
java -jar $jar --help
java -jar $jar --terminal `
  --workspace D:\haifa-agent-config\workspaces\terminal-manual `
  --config D:\haifa-agent-config\haifa-coding-terminal.yaml
```

无 `-m` 时默认进入同一 Terminal 路径。`D:\haifa-agent-config` 位于源码仓库之外；建议使用：

```text
D:\haifa-agent-config\
  haifa-coding-terminal.yaml
  data\
    coding-terminal.db
    transcripts\
  workspaces\
    terminal-manual\
```

Windows 上需要真实运行编译、包管理器或联网工具时，仅对明确检查和信任的测试 Workspace 配置
`host-guarded + network allow`。它以当前 OS 用户权限执行，不是容器或虚拟机；Approval 也不等于强
隔离。持久模式使用稳定的 `env://HAIFA_CONTINUATION_KEY`，其值必须是 Base64 编码的 32 字节 AES
key，并在所有重启间保持不变。

## Phase 2 人工验收

进入 Phase 3 前人工验证 Phase 2，不等到最后统一验证：

1. 启动后确认原型规定的 Header/Transcript/Editor/Footer 单列顺序；
2. 不提交模型 Turn，输入 `/quit`，确认 echo、cursor 与 scrollback 恢复；
3. 经用户明确授权后再执行一个真实联网 coding Turn：读取文件、修改代码、运行相邻测试；
4. 活动 Run 分别验证 Enter Steer、Alt+Enter Follow-up、Escape/Ctrl+C Cancel；
5. `/resume` 打开真实 Session，Selector 关闭后 editor buffer/cursor 恢复；
6. 重启后确认 Session、Queue、Cursor 不重复分派或渲染；
7. 默认 `approval=ask` 下验证实际 approve/reject；
8. 检查大输出有界，且不显示 Credential、完整 Tool 参数、Provider 原文或 reasoning。

真实模型和 Web Provider 可能产生费用；未经单独授权保持 **NOT RUN**。自动化验证只使用 Stub/Fake：

```powershell
.\mvnw.cmd -pl :haifa-agent-coding-agent,:haifa-agent-coding-terminal,:haifa-agent-cli -am test
```
