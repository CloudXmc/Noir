plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "suki.mrhua269"
version = "1.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://mvn-repo.arim.space/lesser-gpl3/") {
        name = "arim-mvn-lgpl3"
    }
}

dependencies {
    // Folia 1.21.11 的 Bukkit/Paper API；运行时由 Folia 提供。
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.108.Final")
    compileOnly("com.zaxxer:HikariCP:6.3.0")
    compileOnly("com.mysql:mysql-connector-j:9.4.0")

    implementation("space.arim.morepaperlib:morepaperlib:0.4.3")

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("io.netty:netty-all:4.1.108.Final")
    testImplementation("org.yaml:snakeyaml:2.3")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("it.unimi.dsi:fastutil:8.5.15")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.zaxxer:HikariCP:6.3.0")
    testImplementation("com.mysql:mysql-connector-j:9.4.0")
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.test {
    useJUnitPlatform()
    // Windows 构建机页面文件较小时避免并发测试 JVM 抢占内存。
    maxParallelForks = 1
    maxHeapSize = "256m"
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
