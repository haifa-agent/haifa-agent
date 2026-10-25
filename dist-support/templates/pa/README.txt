========================================================================
Haifa Personal Assistant - Windows x64 便携版
========================================================================

本包为开箱即用的绿色免安装版，内置精简 Java 21 运行时（JRE）与已打包的前端页面，
无需预装 Java 或 Node.js。与 Linux 服务器包共用同一套目录约定。

【1. 包内结构】

  app.jar                  后端可执行 JAR（已内嵌前端 SPA）
  conf/pa.env              运行环境变量（与 Linux 包同名同格式）
  bin/start.bat            前台启动（带控制台日志）
  bin/start-background.vbs 后台静默启动（无命令行窗口）
  bin/status.bat           查看运行状态
  bin/stop.bat             停止服务
  jre/                     内置 Java 21 运行时
  README.txt               本文件

运行期生成（首次启动后出现）：

  data/personal-assistant/ 数据目录（SQLite、images/、audio/、artifacts/）
  logs/personal-assistant.log 运行日志

【2. 前置条件】

- Windows 10/11 x64；无需安装 Java（使用内置 jre/）。

【3. 启动与停止】

1. 前台启动（带控制台日志）：
   双击 bin/start.bat。启动就绪后会自动打开：
   http://127.0.0.1:20001/index.html
2. 后台静默启动（无黑框）：
   双击 bin/start-background.vbs
3. 查看状态：双击 bin/status.bat
4. 停止服务：双击 bin/stop.bat
   （关闭命令行窗口不等于停止服务，请用 stop.bat）

【4. 自检】

启动后访问 http://127.0.0.1:20001/actuator/health 应返回 200；
或使用 bin/status.bat 确认端口 20001 处于 LISTENING。

【5. 配置项（conf/pa.env）】

与 Linux 包同名同格式（KEY=VALUE，# 开头为注释），系统环境变量优先：

  HAIFA_PERSONAL_DATA_DIR                     数据目录，默认 ./data/personal-assistant
  HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED 必须为 true，否则服务拒绝启动（fail closed）
  HAIFA_CODEX_ORIGINATOR / HAIFA_CODEX_USER_AGENT  openai-codex 绑定所需的客户端标识

【6. 数据与凭据】

1. 数据目录：包根目录下的 data/personal-assistant/
   - SQLite 数据库：data/personal-assistant/personal-assistant.sqlite
   - 多模态图片：data/personal-assistant/images/
   - 音频数据：data/personal-assistant/audio/
   - 研报与产物：data/personal-assistant/artifacts/
2. 日志目录：logs/personal-assistant.log
3. 模型凭据：不收在包内，而是由 Windows 系统“凭据管理器”加密保存
   （Haifa 使用当前用户身份读写，不写明文文件）。

【7. 安全须知】

- 服务默认只监听 127.0.0.1，仅本机可访问；
- HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true 表示模型可在本机执行宿主命令
  （cmd/powershell）。这等价于把当前用户的执行能力交给模型调用方，请勿在不可信的
  共享机器上长期开启；
- 若要让别人访问本机服务，请自行加反向代理与认证，不要直接把端口暴露到公网。

【8. 升级】

1. 双击 bin/stop.bat 停止服务；
2. 用新版 app.jar 覆盖包内 app.jar（前端随 jar 一起更新）；
3. 保留 data/、logs/ 与 conf/pa.env 不动；
4. 双击 bin/start.bat 重新启动。

【9. 回滚】

保留上一版整个目录即可：停服后把旧目录改回原路径再启动。
data/ 与 conf/pa.env 与新版本兼容，可继续沿用。

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
