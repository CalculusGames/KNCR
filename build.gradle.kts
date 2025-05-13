plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

group = "xyz.calcugames"
version = "1.0.0"
description = "Kotlin/Native CInterop Repository"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.github.oshai:kotlin-logging:7.0.7")
}

tasks {
    clean {
        delete("logs")
    }

    register("kncrRun", JavaExec::class) {
        group = "kncr"
        description = "Run the KNCR application"

        mainClass.set("xyz.calcugames.kncr.MainKt")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(
            layout.buildDirectory.file("kncr").get().asFile.absolutePath,
            "https://repo.calcugames.xyz/repository/kncr/",
            "calcugames",
            project.findProperty("mvnTask")?.toString() ?: "deploy",
            project.findProperty("parallelism")?.toString() ?: "0",
        )
        jvmArgs = listOf("-XX:+HeapDumpOnOutOfMemoryError")
    }
}