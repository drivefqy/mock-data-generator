plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.github.mockdatagen"
version = "1.0.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("datagripHome"))
        bundledPlugin("com.intellij.database")
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "com.github.mockdatagen"
        name = "Mock Data Generator"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

// 纯逻辑冒烟测试:不启动 IDE,直接验证规则生成/参数编辑器模型
// 源码位于 src/test/kotlin,不会打进插件产物
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "运行 Mock 数据生成核心逻辑冒烟测试"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("SmokeTestKt")
    workingDir = projectDir
}
