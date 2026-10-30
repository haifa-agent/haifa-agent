========================================================================
Haifa Personal Assistant - Linux x64 服务器包
========================================================================

本包面向 Linux 常驻部署：不内置 JRE（使用系统 JDK 21），前端由 nginx 直接提供，
后端只监听 127.0.0.1:20001 并反代对外。与 Windows 便携包共用同一套目录约定。

【1. 包内结构】

  app.jar                  后端可执行 JAR（不含前端静态资源）
  web/                     前端 SPA 产物（API 基址为相对路径 /api/v1 与 /v1/admin）
  conf/pa.env              运行环境变量（与 Windows 包同名同格式）
  conf/haifa-personal-assistant.service  systemd 单元（普通用户运行）
  conf/pa.conf             nginx 站点模板（按域名/证书调整）
  bin/start.sh             前台启动（systemd 也调用它）
  bin/check.sh             自检：临时端口 + 临时数据目录启动并探活
  bin/publish-web.sh       可选：把 web/ 复制到其它目录（默认不复制）
  docker/                  可选：离线基础镜像、Dockerfile、compose
  README.txt               本文件

运行期生成（首次启动后出现）：

  data/personal-assistant/ 数据目录（SQLite、images/、audio/、artifacts/、tmp/）
  logs/personal-assistant.log 运行日志

【2. 前置条件】

- Linux x86_64，JDK 21（`java -version` 需为 21）；
- nginx（由你自行安装；本包只提供站点模板）；
- 不需要 root：普通用户即可安装与运行。

【3. 安装】

3.1 用一个普通服务用户安装（推荐，不需要 root 运行服务）

  sudo useradd --system --create-home --home-dir /opt/haifa-personal-assistant \
      --shell /usr/sbin/nologin haifa-pa
  sudo install -d -o haifa-pa -g haifa-pa /opt/haifa-personal-assistant
  sudo -u haifa-pa python3 -m zipfile -e haifa-personal-assistant-linux-x64-v<version>.zip /tmp/pa-unpack
  sudo -u haifa-pa cp -R /tmp/pa-unpack/haifa-personal-assistant-v<version>/. /opt/haifa-personal-assistant/
  sudo -u haifa-pa mkdir -p /opt/haifa-personal-assistant/data/personal-assistant /opt/haifa-personal-assistant/logs

  若服务器未安装 unzip：用上面 `python3 -m zipfile -e` 的方式解包。

3.2 以当前用户直接运行（临时/内测，不想建系统用户）

  解包到任意可写目录即可，bin/start.sh 只用安装目录内的路径：
    python3 -m zipfile -e <zip> ./pa && ./pa/haifa-personal-assistant-v<version>/bin/start.sh

3.3 注册 systemd（可选）

  sudo install -m 0644 /opt/haifa-personal-assistant/conf/haifa-personal-assistant.service \
      /etc/systemd/system/haifa-personal-assistant.service
  # 若安装路径或服务用户不是 haifa-pa，先改 User/Group/WorkingDirectory/EnvironmentFile/ExecStart
  sudo systemctl daemon-reload
  sudo systemctl enable --now haifa-personal-assistant

【4. 启动与停止】

  systemd：  sudo systemctl start|stop|status haifa-personal-assistant
  手工前台： bin/start.sh                 （Ctrl+C 结束）
  容器：     docker compose -f docker/docker-compose.yml up -d|down

【5. 自检】

  sh bin/check.sh                 # 临时端口 20999 + 临时数据目录，不影响正式实例
  curl -fsS http://127.0.0.1:20001/actuator/health

【6. 配置项（conf/pa.env）】

与 Windows 包同名同格式（KEY=VALUE，# 开头为注释），系统环境变量优先：

  HAIFA_PERSONAL_DATA_DIR                      必须为绝对路径，默认值以 /opt/haifa-personal-assistant 为例
  HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED 必须为 true，否则服务拒绝启动（fail closed）
  HAIFA_CODEX_ORIGINATOR / HAIFA_CODEX_USER_AGENT  openai-codex 绑定所需的客户端标识

安装到其它路径时，同步修改 conf/pa.env 与 conf/haifa-personal-assistant.service。

【7. 数据与凭据】

1. 数据目录：<安装目录>/data/personal-assistant/
   - SQLite 数据库：personal-assistant.sqlite
   - 多模态图片：images/        音频：audio/        研报与产物：artifacts/
   - 运行临时目录：tmp/（java.io.tmpdir，sqlite-jdbc 在此解压原生库）
2. 日志：<安装目录>/logs/personal-assistant.log 与 journalctl -u haifa-personal-assistant
3. 模型凭据：Linux 没有系统凭据库，服务把凭据写到 $HOME/.haifa-agent/auth.json
   （0600、文件锁、原子替换）。容器运行时 HOME 单独挂载为 <安装目录>/home。
   若不想落盘，可把 provider 的 credential-reference 换成 env://<变量名>。

【8. nginx（平台差异：Windows 包没有这一层）】

  conf/pa.conf 是完整站点模板，按域名/证书调整后启用：

    sudo install -m 0644 conf/pa.conf /etc/nginx/conf.d/pa.conf
    sudo nginx -t && sudo systemctl reload nginx

  关键点：后端只接受回环 Host/Origin，模板里已把 Host 改写为 127.0.0.1:20001 并清空 Origin；
  SSE 端点单独关闭缓冲；静态目录默认指向 <安装目录>/web（不需要复制到 /var/www）。

【9. 安全须知】

- 后端没有内建认证，信任边界是「回环 + 可信宿主」：HTTPS 站点一旦对外开放，
  任何能访问该域名的人都能消耗模型额度；
- conf/pa.env 里 HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true 表示模型可在本机
  执行宿主 shell（bash/sh）。对公网暴露时至少启用 conf/pa.conf 末尾的 basic auth，
  或只允许内网/VPN 访问；
- 服务用户只需读写安装目录，不要用 root 运行。

【10. 容器化（可选）】

  sh docker/build-offline-base-image.sh      # 用宿主 JDK 组装 pa-base:local（不访问 registry）
  docker compose -f docker/docker-compose.yml up -d --build
  curl -fsS http://127.0.0.1:20001/actuator/health

数据卷相对 compose 文件放在 <安装目录>/data 与 <安装目录>/home，不依赖 /root。
容器化的四个硬约束（已固化，改动会导致启动失败）：JDK 必须绝对路径、基础镜像需带
/etc/java-21-openjdk、HOME 不能落在数据目录内、模型 shell 需要 /tmp 可写可执行。

【11. 升级】

  systemctl stop haifa-personal-assistant          # 或 docker compose -f docker/docker-compose.yml down
  用新版 app.jar 覆盖 <安装目录>/app.jar
  用新版 web/ 覆盖 <安装目录>/web/
  systemctl start haifa-personal-assistant         # 容器：up -d --build
  data/ 与 $HOME/.haifa-agent/auth.json 不需要替换

【12. 回滚】

  保留上一版整个安装目录：停服后把旧目录改回原路径再启动；
  容器回滚用上一版镜像 tag（docker compose -f docker/docker-compose.yml up -d）。

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
