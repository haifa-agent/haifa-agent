========================================================================
Haifa Coding Agent - Windows x64 便携版
========================================================================

本压缩包为开箱即用的绿色免安装版，内置专用精简 Java 21 运行时（JRE），
无需在系统预先配置 Java 环境。

【快速开始】

1. (推荐) 配置全局环境变量：
   双击运行 bin/install.bat，脚本会自动将 bin/ 目录加入
   当前用户的 PATH 环境变量。
   配置后在任意终端输入 `haifa` 即可使用。

2. 直接运行：
   进入 bin 目录，在终端中执行：
   - 启动交互式 TUI 终端：
     haifa
   - 执行单次快速编程任务：
     haifa -m "分析当前工程结构"
   - 查看完整命令行选项：
     haifa --help

【终端体验推荐】
- 强烈推荐使用 Windows Terminal（Windows 11 自带，Win10 可在微软应用商店免费安装）。
- 交互式终端支持使用鼠标拖拽划词选择文本、Ctrl+C 复制、滚轮翻页回看历史。

【模型连接与凭据管理】
- 首次使用支持运行 `/login` 进行模型登录与 API Key 配置。
- 所有凭据均安全保存在 Windows 系统凭据管理器（Credential Manager）中，
  不会在磁盘明文保存。

【配置、数据与日志】
- 启动脚本会自动加载随包内置的 haifa-coding.yaml（无密钥的安全默认配置）。
- 默认审批策略为 ask + 高风险阈值（HIGH）；单条命令最长超时 2 小时，一次性任务
  总超时 30 分钟（实际单条命令仍受 Run 剩余 wall time 约束）。
- 运行时数据与日志统一保存在当前用户目录，而非解压目录：

    %USERPROFILE%\.haifa-agent\coding\
      data\
        runtime.db               # SQLite 权威存储（Session / Run / 授权状态）
        transcripts\             # JSONL 审计投影
      logs\                      # 有界轮转运行日志

- 数据默认持久化（SQLITE_WITH_JSONL），关闭后再次启动可恢复历史。
- 如需自定义位置，可在运行前设置 HAIFA_DATA_ROOT，或分别设置
  HAIFA_SQLITE_DATABASE_PATH、HAIFA_TRANSCRIPT_ROOT、HAIFA_LOG_DIR。

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
