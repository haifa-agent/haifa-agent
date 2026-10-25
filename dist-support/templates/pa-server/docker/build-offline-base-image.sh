#!/bin/sh
# 离线构建容器基础镜像：用宿主现有的 JDK 21 + 基础库组装 rootfs，docker import
# 适用于无法从 registry 拉取镜像（带宽极低 / 被墙）的服务器。
#
# 前提：宿主已安装 openjdk-21（Ubuntu 24.04 的路径为 /usr/lib/jvm/java-21-openjdk-amd64，
#       其它发行版请同步修改下面的 JDK 目录与 docker/Dockerfile 里的 ENTRYPOINT 路径）。
# 产出：本地镜像 pa-base:local（约 1.3GB），随后 docker/Dockerfile 以它为基础。
set -eu

RS=/tmp/pa-rootfs
rm -rf "$RS"
mkdir -p "$RS"/usr/bin "$RS"/usr/lib "$RS"/etc "$RS"/tmp "$RS"/var/tmp \
         "$RS"/data/home "$RS"/data/logs "$RS"/home/haifa-pa "$RS"/app "$RS"/root "$RS"/proc "$RS"/sys "$RS"/dev

echo '[1/6] 复制基础库与 JDK'
cp -a /usr/lib/x86_64-linux-gnu "$RS"/usr/lib/
cp -a /usr/lib/jvm "$RS"/usr/lib/

echo '[2/6] 复制常用命令行工具'
for b in bash dash env ls cat cp mv rm mkdir rmdir chmod chown ln id whoami uname hostname \
         sleep date tail head grep sed awk find xargs tar gzip gunzip touch cut sort tr wc tee \
         ps kill pgrep pkill curl openssl printf expr basename dirname readlink realpath seq \
         stat du df which mktemp dircolors; do
    if [ -e "/usr/bin/$b" ]; then
        cp -aL "/usr/bin/$b" "$RS"/usr/bin/ 2>/dev/null || true
    fi
done
ln -sf /usr/lib/jvm/java-21-openjdk-amd64/bin/java "$RS"/usr/bin/java
ln -sf /usr/bin/bash "$RS"/usr/bin/sh

echo '[3/6] 复制 etc 运行期文件'
# java-21-openjdk 必须带上：conf/security/java.security 是指向 /etc/java-21-openjdk 的符号链接，
# 缺了它会报 "Error loading java.security file"
for f in passwd group nsswitch.conf hosts gai.conf ssl alternatives services protocols localtime \
         ca-certificates.conf login.defs java-21-openjdk crypto-policies; do
    if [ -e "/etc/$f" ]; then
        cp -a "/etc/$f" "$RS"/etc/
    fi
done
ln -sf usr/bin "$RS"/bin
ln -sf usr/lib "$RS"/lib
mkdir -p "$RS"/usr/lib64
ln -sf ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 "$RS"/usr/lib64/ld-linux-x86-64.so.2
ln -sf usr/lib64 "$RS"/lib64

echo '[4/6] 创建运行用户与目录'
printf 'haifa-pa:x:10001:10001::/home/haifa-pa:/bin/bash\n' >> "$RS"/etc/passwd
printf 'haifa-pa:x:10001:\n' >> "$RS"/etc/group
chmod 1777 "$RS"/tmp "$RS"/var/tmp
chown 10001:10001 "$RS"/data "$RS"/data/home "$RS"/data/logs
chown 10001:10001 "$RS"/home/haifa-pa

echo '[5/6] 生成 rootfs 并导入镜像（约 900MB，需要数分钟）'
tar --numeric-owner -C "$RS" -cf - . | docker import \
    -c 'ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 HOME=/root' \
    - pa-base:local

echo '[6/6] 冒烟：容器内 shell 与 java'
docker run --rm pa-base:local /bin/bash -lc 'echo shell_ok; java -version 2>&1 | head -2'
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | head -5
