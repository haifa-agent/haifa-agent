========================================================================
Haifa Personal Assistant - Windows x64 便携版
========================================================================

本压缩包为开箱即用的绿色免安装版，内置专用精简 Java 21 运行时（JRE），
同时集成了完整 Web 前端页面，无需在系统预先安装 Java 或 Node.js。

【快速启动】

1. 前台启动（带控制台日志）：
   双击运行 bin/start.bat。
   脚本会自动启动服务，在服务启动就绪后自动打开系统浏览器访问：
   http://127.0.0.1:20001/index.html
   使用 bin\stop.bat 停止服务；关闭命令行窗口不会替代停止操作。

2. 后台静默启动（无黑框）：
   双击运行 bin/start-background.vbs。
   服务将在后台默默运行并唤起浏览器。

3. 服务管理：
   - 查看运行状态：双击 bin/status.bat
   - 停止服务：双击 bin/stop.bat

【数据与日志存储位置】

1. 数据目录 (Data Directory)：
   默认位于本便携包根目录下的 data/personal-assistant/
   - SQLite 数据库：data/personal-assistant/personal-assistant.sqlite
     (持久化保存所有对话会话、任务流状态、模型偏好等)
   - 多模态图片：data/personal-assistant/images/
   - 音频数据：data/personal-assistant/audio/
   - 研报与产物：data/personal-assistant/artifacts/
   提示：可通过环境变量 HAIFA_PERSONAL_DATA_DIR 自定义指定外部路径。

2. 日志目录 (Log Directory)：
   默认位于本便携包根目录下的 logs/
   - 完整运行日志：logs/personal-assistant.log

3. API 凭据存储 (Credential Store)：
   大模型 API Key 不以明文存放在磁盘上，而是通过 Windows API 加密保存在
   Windows 系统的“凭据管理器”（Credential Manager）中。

【默认运行设置（由 bin/start.bat 提供，可用环境变量覆盖）】
- HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true：为便携包显式开启受控宿主执行。
- HAIFA_CODEX_ORIGINATOR=haifa、HAIFA_CODEX_USER_AGENT=haifa-agent/1：内置 Codex 客户端标识。
- 如直接 java -jar 启动而非使用 start.bat，请自行设置上述变量。

【模型配置与使用】
- 打开 Web 界面后，点击右上角“模型 / 模型连接”按钮；
- 支持配置 DeepSeek、ChatGPT Codex、Google Gemini 等模型连接。

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
