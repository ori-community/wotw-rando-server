buildscript {
    repositories {
        jcenter()
        mavenCentral()
    }
}

val kotlin_version = "1.5.10"
val ktor_version = "1.6.2"
val logback_version = "1.2.3"
val exposed_version = "0.31.1"
val kotlinx_html_version = "0.7.1"
val serialization_version = "1.2.1"

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.5.30"
    kotlin("plugin.serialization") version "1.5.10"
}

repositories {
    jcenter()
    maven("https://dl.bintray.com/kotlin/ktor")
    maven(url = "https://dl.bintray.com/kordlib/Kord")
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$serialization_version")

    implementation("io.github.classgraph:classgraph:4.8.87")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-websockets:$ktor_version")
    implementation("io.ktor:ktor-html-builder:$ktor_version")
    implementation("io.ktor:ktor-serialization:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("io.ktor:ktor-auth:$ktor_version")
    implementation("io.ktor:ktor-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-sessions:$ktor_version")

    implementation("ch.qos.logback:logback-classic:$logback_version")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("com.zaxxer:HikariCP:3.4.5")

    implementation("org.postgresql:postgresql:42.2.14")

    implementation("dev.kord:kord-core:0.7.0-RC3")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
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