import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("org.mundhenk.cambackup.server.MainKt")
    applicationName = "cambackup-server"
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

apply(from = "../gradle/coverage.gradle.kts")
