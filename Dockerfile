# 家庭账房 · v0.7 多阶段镜像
# 构建层:maven 编译出 app.jar(复用阿里云 mirror 加速);运行层:JRE + mysql client(db/apply.sh 迁移要用)

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY deploy/maven-settings.xml ./maven-settings.xml
# 先拉依赖,利用层缓存(源码变动不重拉依赖)
RUN mvn -s maven-settings.xml -B -q dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -s maven-settings.xml -B -DskipTests clean package

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre
# db/apply.sh 需 mysql client;healthcheck 需 curl;脚本用 bash;时区
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-mysql-client curl ca-certificates tzdata bash \
 && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"
WORKDIR /app
COPY --from=build /src/target/app.jar /app/app.jar
COPY db /app/db
COPY docker/entrypoint.sh /app/entrypoint.sh
COPY docker/backup.sh /app/backup.sh
COPY docker/clean-dev-data.sh /app/clean-dev-data.sh
# 非 root 运行 + 数据目录
RUN chmod +x /app/entrypoint.sh /app/backup.sh /app/clean-dev-data.sh /app/db/apply.sh \
 && useradd -r -u 10001 finance \
 && mkdir -p /data/uploads /data/backups \
 && chown -R finance:finance /app /data
USER finance
EXPOSE 20000
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:20000/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
