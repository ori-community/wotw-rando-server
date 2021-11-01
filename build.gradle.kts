buildscript {
    repositories {
        mavenCentral()
    }
}

val kotlin_version = "1.5.31"
val ktor_version = "1.6.4"
val logback_version = "1.2.6"
val exposed_version = "0.36.1"
val kotlinx_html_version = "0.7.1"
val serialization_version = "1.3.0"

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.5.31"
    kotlin("plugin.serialization") version "1.5.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$serialization_version")

    implementation("io.github.classgraph:classgraph:4.8.126")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-websockets:$ktor_version")
    implementation("io.ktor:ktor-html-builder:$ktor_version")
    implementation("io.ktor:ktor-serialization:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("io.ktor:ktor-auth:$ktor_version")
    implementation("io.ktor:ktor-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-sessions:$ktor_version")
    implementation("io.ktor:ktor-network:$ktor_version")

    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2-native-mt"){
        version {
            strictly("1.5.2-native-mt")
        }
    }

    implementation("ch.qos.logback:logback-classic:$logback_version")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")


    implementation("com.zaxxer:HikariCP:5.0.0")

    implementation("org.postgresql:postgresql:42.2.23")

    implementation("dev.kord:kord-core:0.7.4")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
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

//Generates self-signed test certificate
val generateJks = tasks.create<JavaExec>("generateJks") {
    group = "application"
    main = "wotw.build.main.CertificateGenerator"
    classpath(configurations["runtimeClasspath"], jvmJar)
}

tasks.create<JavaExec>("run") {
    group = "application"
    main = "wotw.server.main.WotwBackendServer"
    classpath(configurations["runtimeClasspath"], jvmJar)
}