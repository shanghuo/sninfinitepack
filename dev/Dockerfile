# MC 1.7.10 (GTNH) 模组开发/测试容器
# - JDK 25: 用于 GTNHGradle 2.x 构建（Gradle 9.3.1 + toolchain 25，jabel 降级 J8 字节码）
# - JDK 8:  用于运行 Forge 1.7.10 专用测试服务器（最兼容，无需 --add-opens）
FROM eclipse-temurin:25-jdk

ARG PROXY=

# 设置代理（仅用于本次构建阶段；运行时由 compose 传入）
ENV http_proxy=${PROXY} https_proxy=${PROXY} \
    HTTP_PROXY=${PROXY} HTTPS_PROXY=${PROXY} \
    NO_PROXY=localhost,127.0.0.1 no_proxy=localhost,127.0.0.1

# 基础工具
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
       git curl unzip zip ca-certificates procps file \
    && rm -rf /var/lib/apt/lists/*

# JDK 8（用于 Forge 1.7.10 测试服务器运行）
RUN curl -fsSL -o /tmp/jdk8.tar.gz "https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    && mkdir -p /opt/jdk8 \
    && tar -xzf /tmp/jdk8.tar.gz -C /opt/jdk8 --strip-components=1 \
    && rm -f /tmp/jdk8.tar.gz \
    && /opt/jdk8/bin/java -version 2>&1

ENV JAVA8_HOME=/opt/jdk8 \
    JAVA_HOME=/opt/java/openjdk \
    PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /work
CMD ["bash"]
