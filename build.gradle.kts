import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
}

val ktorVersion = "3.2.0"
val logbackVersion = "1.5.18"
val exposedVersion = "0.61.0"
val serializationVersion = "1.8.1"
val cronutilsVersion = "9.2.1"
val semverVersion = "3.0.0"
val kordVersion = "0.15.0"

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$serializationVersion")
    implementation("com.cronutils:cron-utils:$cronutilsVersion")

    implementation("io.github.z4kn4fein:semver:$semverVersion")
    implementation("io.github.classgraph:classgraph:4.8.147")
    implementation("io.sentry:sentry:6.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")


    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.postgresql:postgresql:42.7.3")

    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auto-head-response:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-http-redirect:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-network-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")

    implementation("dev.kord:kord-core:$kordVersion")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

val jvmJar = tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    manifest {
        attributes(
            mapOf(
                "Main-Class" to "wotw.server.main.WotwBackendServer"
            )
        )
    }
    from(configurations["runtimeClasspath"].map { if (it.isDirectory) it else zipTree(it) })
}

tasks.register<JavaExec>("run") {
    group = "application"
    mainClass.set("wotw.server.main.WotwBackendServer")

    jvmArgs = listOf("-Xint")
    // jvmArgs = listOf("-XX:CompileCommand=exclude,wotw/server/game/GameSyncHandler.onPlayerPositionMessage")

    classpath(configurations["runtimeClasspath"], jvmJar)
}

val compileKotlin: KotlinCompile by tasks
