# Stringer 服务端镜像构建流程
#
# 1. 可执行 jar：stringer-server/target/stringer-v1.0-beta.1.jar
# 2. 把该 jar 与本 Dockerfile 放在同一目录，构建镜像：
#      docker build -t stringer-server:v1.0-beta.1 .
# 3. 运行：
#      docker run -d --name stringer -p 9527:9527 \
#        -v stringer-config:/var/lib/stringer/config \
#        stringer-server:v1.0-beta.1
#
# 约定：
#   1. 默认端口 9527，可用 SERVER_PORT 环境变量或 --server.port= 覆盖；
#   2. 配置目录默认 /var/lib/stringer/config，**必须挂卷**：账号、模型设置、存储连接都在这里，
#      不挂卷则重建容器等于账号回落种子、所有配置重填；
#   3. 日志默认只输出控制台（docker logs -f stringer）。要落盘再加：
#        --logging.config=classpath:logback-file.xml   并挂 -v stringer-logs:/var/log/stringer

FROM eclipse-temurin:21-jre

RUN groupadd -r stringer \
 && useradd -r -g stringer -d /opt/stringer -s /sbin/nologin stringer \
 && mkdir -p /opt/stringer /var/lib/stringer/config /var/log/stringer \
 && chown -R stringer:stringer /opt/stringer /var/lib/stringer /var/log/stringer

WORKDIR /opt/stringer
COPY --chown=stringer:stringer stringer-v1.0-beta.1.jar app.jar

USER stringer
EXPOSE 9527

ENV TZ=Asia/Shanghai \
    STRINGER_LOG_LEVEL=INFO \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

# 存活探针打免鉴权的 GET /health —— /api/agent/health 需要凭证，编排系统的探针配不了请求头。
# 基础镜像（Ubuntu 基底的 temurin）没有 curl，用 bash 的 /dev/tcp 发一个最简 HTTP 请求，只看首行是否 200。
# 换成 alpine 基底（musl）需要改写法；K8s 请直接用原生 httpGet 探针，不需要镜像内有任何工具。
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9527 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && head -1 <&3 | grep -q 200'

# exec 让 java 成为 PID 1：否则 SIGTERM 落在 sh 上，JVM 收不到，优雅关闭失效
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
