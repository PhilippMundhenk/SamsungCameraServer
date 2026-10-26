import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// No toolchain requirement: compile with whatever JDK runs Gradle (17+), emit Java 17 bytecode
// so the same classes run on Android (desugared by AGP) and in the server container.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
