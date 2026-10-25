# Haifa Personal Assistant Server —— 容器镜像
#
# 构建（在包根目录执行，包根目录已含 app.jar）：
#   docker build -f docker/Dockerfile -t haifa-personal-assistant:local .
#
# 基础镜像 pa-base:local 由 docker/build-offline-base-image.sh 从宿主 JDK 组装，
# 不需要访问任何 registry（适用于无法拉取 Docker Hub 的服务器）。
FROM pa-base:local

# 容器内 IPv4 优先：宿主双栈但 IPv6 出网不通时，避免 TLS 握手卡在 AAAA
RUN printf '\nprecedence ::ffff:0:0/96  100\n' >> /etc/gai.conf

WORKDIR /app
COPY app.jar /app/app.jar
RUN chmod 0644 /app/app.jar

USER 10001:10001
ENV HOME=/home/pa \
    HAIFA_PERSONAL_DATA_DIR=/data/personal-assistant \
    HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true \
    HAIFA_CODEX_ORIGINATOR=haifa \
    HAIFA_CODEX_USER_AGENT=haifa-agent/1

EXPOSE 20001

# 1) 必须用 JDK 绝对路径：/usr/bin/java 是符号链接，JVM 会解析出错误的 java.home
# 2) HOME 不能落在数据目录内，否则 host 执行边界会拒绝（HOST_USER_HOME_UNAVAILABLE）
ENTRYPOINT ["/usr/lib/jvm/java-21-openjdk-amd64/bin/java", \
            "-Djava.net.preferIPv4Stack=true", "-Dfile.encoding=UTF-8", "-Duser.home=/home/pa", \
            "-Djava.io.tmpdir=/data/personal-assistant/tmp", \
            "-Xmx768m", "-XX:MaxMetaspaceSize=256m", "-XX:+UseSerialGC", \
            "-jar", "/app/app.jar", \
            "--server.address=0.0.0.0", "--server.port=20001"]
