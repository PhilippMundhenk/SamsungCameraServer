// Shared JaCoCo setup for the JVM modules (core, server): report + 95 % line-coverage gate.
// Applied from the module build files with `apply(from = "../gradle/coverage.gradle.kts")`.
//
// Excluded: the `main` process wrappers (MainKt) — they block the JVM forever and only call
// into tested code (Cli / CamBackupServer).

apply(plugin = "jacoco")

configure<JacocoPluginExtension> { toolVersion = "0.8.12" }

val coverageExcludes = listOf("**/MainKt.class")

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.withType<Test>())
    reports { xml.required.set(true); html.required.set(true) }
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
}

tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn(tasks.withType<Test>())
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
    violationRules {
        rule {
            limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.95".toBigDecimal() }
        }
    }
}

tasks.named("check") { dependsOn("jacocoTestReport", "jacocoTestCoverageVerification") }
