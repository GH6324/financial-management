# =========================================================
# 富途 OpenD 网关 launcher(v1.17 · 可选组件)
#
# 这个镜像里【没有富途的任何文件】。它只装了:
#   - 一个下载器(curl)+ 校验工具(sha256sum,基底自带)
#   - 三个 shell 脚本:entrypoint.sh(下载校验+起进程)/ control-loop.sh(控制通道)
#   - 我们核对过的版本清单 releases.json(和仓库里 deploy/futu-opend-releases.json 同一份)
# OpenD 本体是【容器第一次启动时从富途官网下载的】,下载完先比对 sha256,不一致就拒绝启动。
# CI 里有一道检查扫镜像层,一旦出现富途制品就构建失败(scripts/scan-image-no-futu.sh)。
#
# 基底为什么是 debian:12-slim(2026-08-17 beta 实测):
#   - glibc 2.36 ≫ 官方包按 Ubuntu18.04(glibc 2.27)构建所需;三个可执行文件 ldd 零缺失
#   - tar / sha256sum / script 自带,只需补 curl + ca-certificates + openssl
#   - 不用 alpine:musl 跑不了 glibc 动态链接的官方包
#   - 不复用 app 镜像基底(temurin JRE 493MB):为一个 shell 下载器背一个 JRE 不合适
# =========================================================
FROM debian:12-slim

# openssl 用于生成 API 通道的 RSA 私钥(见 entrypoint.sh);curl 下载;ca-certificates 校验 TLS
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates openssl \
 && rm -rf /var/lib/apt/lists/*

# 【实测踩过的坑】OpenD 用 getpwuid(getuid()) 推家目录,运行 uid 如果不在 /etc/passwd 里
# 就直接段错误(Segmentation fault,没有任何有用信息)。所以镜像自己建用户,
# 并且【不要】在 compose 里用 user: 覆盖 —— entrypoint 会先自检并说人话。
RUN useradd -r -u 10002 -m -d /home/futu -s /usr/sbin/nologin futu

WORKDIR /opt/futu
COPY docker/futu-opend/entrypoint.sh docker/futu-opend/control-loop.sh ./
# 唯一来源:仓库里的 deploy/futu-opend-releases.json(app 也读同一份)
COPY deploy/futu-opend-releases.json ./releases.json
RUN chmod +x entrypoint.sh control-loop.sh \
 && mkdir -p /ctl \
 && chown -R futu:futu /opt/futu /ctl /home/futu

# 状态与安装物落 /home/futu(注意:OpenD 忽略 $HOME,按 passwd 里的家目录走,所以卷必须挂这里)
# 控制通道落 /ctl(只有 app 与本容器挂载它 —— 文件权限就是鉴权)
VOLUME ["/home/futu", "/ctl"]

USER futu

# 11111 = 富途 API(compose 内网可达;绝不对宿主 publish)
# 22222 = OpenD 控制口,【没有鉴权】→ 只绑容器内 127.0.0.1,故这里刻意【不】EXPOSE
EXPOSE 11111

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
  CMD grep -q '^phase=RUNNING' /ctl/status || grep -q '^phase=NEEDS_SMS' /ctl/status || exit 1

ENTRYPOINT ["/opt/futu/entrypoint.sh"]
