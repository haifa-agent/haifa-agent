========================================================================
Haifa Personal Assistant Server - Linux x64 服务器包
========================================================================

本包面向 Linux 服务器常驻部署：不内置 JRE（使用系统 JDK 21），
前端由 nginx 直接提供静态文件，后端只监听 127.0.0.1:20001 并反代对外。

包内结构：

  app.jar                             后端可执行 JAR（不含前端静态资源）
  web/                                前端 SPA 产物，API 基址为相对路径 /api/v1 与 /v1/admin
  conf/pa.env                         运行环境变量（数据目录、可信宿主开关、Codex 标识）
  conf/haifa-personal-assistant.service  systemd 单元（默认按 /root/app/pa 编写）
  conf/pa.conf                        nginx 站点模板（按域名/证书修改）
  bin/start.sh                        前台启动（无 systemd 时手工运行）
  bin/publish-web.sh                  web/ -> /var/www/pa（nginx 可读目录）
  bin/check.sh                        自检：临时端口 + 临时数据目录启动并探活

【1. 前置条件】

- Linux x86_64，JDK 21（`java -version` 需为 21）；
- nginx，且已有可用证书（本模板使用 /etc/letsencrypt 布局，可自行替换）。

【2. 安装】

假设安装到 /root/app/pa（其他目录需同步修改 conf/ 内路径）：

  mkdir -p /root/app/pa
  # 解包（注意保留目录结构）
  unzip haifa-personal-assistant-server-linux-x64-v<version>.zip -d /tmp/pa-unpack
  # 若服务器未安装 unzip：python3 -m zipfile -e <zip> /tmp/pa-unpack
  cp -R /tmp/pa-unpack/haifa-personal-assistant-server-v<version>/. /root/app/pa/

  mkdir -p /root/app/pa/data /root/app/pa/logs
  sh /root/app/pa/bin/publish-web.sh            # 发布前端到 /var/www/pa

  install -m 0644 /root/app/pa/conf/haifa-personal-assistant.service \
      /etc/systemd/system/haifa-personal-assistant.service
  systemctl daemon-reload
  systemctl enable --now haifa-personal-assistant
  curl -fsS http://127.0.0.1:20001/actuator/health

  # nginx：按实际域名/证书改好 conf/pa.conf 后
  cp /etc/nginx/conf.d/pa.conf /etc/nginx/conf.d/pa.conf.bak-$(date +%Y%m%d-%H%M%S)
  install -m 0644 /root/app/pa/conf/pa.conf /etc/nginx/conf.d/pa.conf
  nginx -t && systemctl reload nginx

【3. 启动前自检】

  sh /root/app/pa/bin/check.sh            # 用 20999 端口跑一次并探活，不影响正式实例

【4. 数据与凭据】

- 数据目录：/root/app/pa/data（SQLite personal-assistant.sqlite、images/、audio/、artifacts/）；
- 日志：/root/app/pa/logs/personal-assistant.log 与 journalctl -u haifa-personal-assistant；
- 模型凭据：Linux 没有系统凭据库，服务把凭据写到 ~/.haifa-agent/auth.json
  （权限 0600、文件锁、原子替换）。在 Web 界面“模型 / 模型连接”里保存的 API Key 就落在该文件；
  若不想落盘，可把 provider 的 credential-reference 换成 env://<变量名>，由环境变量提供。

【5. 安全须知（重要）】

- 后端没有内建认证，信任边界是“回环 + 可信宿主”：
  HTTPS 站点一旦对外开放，任何能访问该域名的人都能消耗模型额度；
- conf/pa.env 里 HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true 表示模型可在
  本机执行宿主 shell（bash/sh）。真要在公网暴露，至少启用 conf/pa.conf 末尾的
  basic auth（htpasswd），或只允许内网/VPN 访问；
- 需要更强隔离时，把服务放进容器运行：只挂载 data 目录、以非 root 用户运行、
  `--network host` 改为 `-p 127.0.0.1:20001:20001`、限制出站网络，
  这样“宿主 shell”只能在容器内生效，不会直接作用于宿主机。

【6. 升级】

  systemctl stop haifa-personal-assistant
  cp 新的 app.jar -> /root/app/pa/app.jar
  cp -R 新的 web/. -> /root/app/pa/web/
  sh /root/app/pa/bin/publish-web.sh
  systemctl start haifa-personal-assistant
  # data/ 与 ~/.haifa-agent/auth.json 不需要替换

【7. 回滚】

  cp /etc/nginx/conf.d/pa.conf.bak-<timestamp> /etc/nginx/conf.d/pa.conf
  systemctl reload nginx
  systemctl disable --now haifa-personal-assistant

【8. 容器化运行（可选，推荐用于隔离宿主命令执行）】

包内 docker/ 提供离线镜像与 compose 定义：

  docker/build-offline-base-image.sh   用宿主 JDK 21 组装基础镜像 pa-base:local（不访问 registry）
  docker/Dockerfile                    以 pa-base:local 为基础，COPY 包根目录的 app.jar
  docker/docker-compose.yml            只发布 127.0.0.1:20001、数据卷与 HOME 单独挂载、只读根文件系统

步骤如下（在包根目录执行）：

  sh docker/build-offline-base-image.sh           # 生成 pa-base:local（约 1.3GB）
  mkdir -p /root/app/pa/data /root/app/pa/home
  chown -R 10001:10001 /root/app/pa/data /root/app/pa/home
  docker compose -f docker/docker-compose.yml up -d --build
  curl -fsS http://127.0.0.1:20001/actuator/health

容器化的已知约束（都是踩过的坑，勿改）：

- HOME 不能放在数据目录内：host 执行边界会拒绝（HOST_USER_HOME_UNAVAILABLE），故单独挂 /home/pa；
- /tmp 必须 exec：sqlite-jdbc 会把原生库解压到 java.io.tmpdir 再 dlopen；
- 启动必须用 JDK 绝对路径：/usr/bin/java 是符号链接，JVM 会解析出错误的 java.home，
  报 "Error loading java.security file"；
- 基础镜像必须带 /etc/java-21-openjdk：Debian 系把 conf/security/java.security 指到那里；
- 宿主双栈但 IPv6 出网不通时，容器内也要 IPv4 优先（镜像已写入 /etc/gai.conf 与 -Djava.net.preferIPv4Stack）。

容器内执行命令使用的是容器自己的 bash（uid 10001），数据卷之外的宿主文件系统不可见，
但容器以内网身份可访问外网与宿主可达的内网服务——认证与出站策略仍需自行把关。

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
