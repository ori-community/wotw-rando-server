// import com.google.protobuf.gradle.generateProtoTasks
// import com.google.protobuf.gradle.id
// import com.google.protobuf.gradle.ofSourceSet
// import com.google.protobuf.gradle.protobuf
// import com.google.protobuf.gradle.protoc
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
}

val kotlin_version = "1.6.10"
val ktor_version = "1.6.8"
val logback_version = "1.2.11"
val exposed_version = "0.37.3"
val serialization_version = "1.3.2"
val krontab_version = "0.7.1"
// val protobuf_version = "3.19.4"

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
    // id("com.google.protobuf") version "0.8.18"
    kotlin("plugin.serialization") version "1.6.10"
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
    implementation("dev.inmo:krontab:$krontab_version")

    implementation("io.github.classgraph:classgraph:4.8.143")
    implementation("io.sentry:sentry:5.7.0")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-websockets:$ktor_version")
    implementation("io.ktor:ktor-html-builder:$ktor_version")
    implementation("io.ktor:ktor-serialization:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("io.ktor:ktor-auth:$ktor_version")
    implementation("io.ktor:ktor-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-sessions:$ktor_version")
    implementation("io.ktor:ktor-network:$ktor_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-native-mt"){
        version {
            strictly("1.6.0-native-mt")
        }
    }

    implementation("ch.qos.logback:logback-classic:$logback_version")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")


    implementation("com.zaxxer:HikariCP:5.0.1")

    implementation("org.postgresql:postgresql:42.3.3")

    implementation("dev.kord:kord-core:0.7.4")

    // protobuf(files("./src/proto"))
    // implementation("com.google.protobuf:protobuf-gradle-plugin:0.8.18")
    // api("com.google.protobuf:protobuf-java-util:$protobuf_version")
    // api("com.google.protobuf:protobuf-kotlin:$protobuf_version")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
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

tasks.create<JavaExec>("run") {
    group = "application"
    main = "wotw.server.main.WotwBackendServer"

    jvmArgs = listOf("-Xint")
    // jvmArgs = listOf("-XX:CompileCommand=exclude,wotw/server/game/GameSyncHandler.onPlayerPositionMessage")

    classpath(configurations["runtimeClasspath"], jvmJar)
}
val compileKotlin: KotlinCompile by tasks

compileKotlin.kotlinOptions {
    languageVersion = "1.6"
}

// protobuf {
//     protoc {
//         artifact = "com.google.protobuf:protoc:$protobuf_version"
//     }
//
//     // Generates the java Protobuf-lite code for the Protobufs in this project. See
//     // https://github.com/google/protobuf-gradle-plugin#customizing-protobuf-compilation
//     // for more information.
//     generateProtoTasks {
//         ofSourceSet("main").forEach {
//             it.builtins {
//                 id("kotlin")
//             }
//         }
//     }
// }