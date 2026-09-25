// 得一即无限背包（SN Infinite Pack）—— Minecraft 1.12.2 版本
// 构建：RetroFuturaGradle（RFG）—— 运行时零依赖，仅依赖 Forge 1.12.2 核心。
plugins {
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.3"
}

group = "com.infpack"
version = "1.0.3"

// 关键：1.12.2 的 FML 用 ASM 5.2，只支持 Java 8（class major 52）字节码。
// 必须用 Java 8 toolchain 编译（容器 JAVA8_HOME=/opt/jdk8），否则 JDK25 会产出 Java 25 字节码导致 mod 无法被加载。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

minecraft {
    mcVersion.set("1.12.2")
    // RFG 对 1.12.2 的内置默认：MCP stable_39 + Forge 1.12.2-14.23.5.2847 + JVM/Java 8
    // forgeVersion 由 RFG 按 mcVersion 决定（只读），与测试实例 14.23.5.2864 同系列、SRG 映射一致
    // 注意：必须用 project.version（= 本模组版本 1.0.3）。
    // 裸写 version 会解析到 MinecraftExtension 已废弃的 version（= mcVersion），
    // 导致 Tags.VERSION 变成 "1.12.2"（1.0.2 及以前的实际产物就是这么错的）。
    injectedTags.put("VERSION", project.version.toString())
}

repositories {
    mavenCentral()
    mavenLocal()
}

// 生成 com.infpack.Tags.VERSION（与 1.7.10 工程一致，@Mod 注解用）
tasks.injectTags.configure {
    outputClassName.set("com.infpack.Tags")
}

dependencies {
    // 运行时零依赖：不引入任何第三方模组/库（NEI/GTNH 等均不需要）
}

// jar 命名与 1.7.10 工程一致：jar 任务产出 dev（classifier=dev），reobfJar 产出正式（无 classifier）。
// dist 复制阶段统一加 mc 版本后缀（见 scripts/build_release_1.12.2.sh）。
tasks.jar {
    archiveBaseName.set("sninfinitepack")
}
