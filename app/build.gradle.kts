plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    jacoco
}

// Short git hash of the working tree ("unknown" outside a checkout), appended to the version name.
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }

android {
    namespace = "org.mundhenk.cambackup.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.mundhenk.cambackup"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-$gitSha"    // the hash tells installed builds apart (Settings footer, first log line)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: app/release.keystore + env (CI secrets). Without it the release build is
    // debug-signed so the artifact is still installable.
    val keystore = file("release.keystore")
    signingConfigs {
        if (keystore.isFile) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // instrumented (Espresso) coverage, see jacocoAndroidTestReport below
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.isFile) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// ---------------------------------------------------------------------------------------------
// Coverage of the instrumented tests (the only tests of this module: everything here touches
// Android). AGP writes the JaCoCo .ec files when connectedDebugAndroidTest runs with
// enableAndroidTestCoverage; these tasks turn them into a report and a 95 % line gate.
// Excluded: generated view bindings, R, BuildConfig.
jacoco { toolVersion = "0.8.12" }

val coverageClassDirs = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/databinding/**", "**/BuildConfig*", "**/R.class", "**/R\$*.class", "**/TestDocumentsProvider*")
    }
)
val coverageExecData = fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) { include("**/*.ec") }
val coverageSources = files("src/main/kotlin")

tasks.register<JacocoReport>("jacocoAndroidTestReport") {
    group = "verification"
    description = "Coverage report of the instrumented tests (run connectedDebugAndroidTest first)"
    dependsOn("compileDebugKotlin"); mustRunAfter("connectedDebugAndroidTest")
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(coverageSources)
    executionData.setFrom(coverageExecData)
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JacocoCoverageVerification>("jacocoAndroidTestCoverageVerification") {
    group = "verification"
    description = "Fails when the instrumented tests cover less than 95 % of the app's lines"
    dependsOn("compileDebugKotlin"); mustRunAfter("connectedDebugAndroidTest", "jacocoAndroidTestReport")
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(coverageSources)
    executionData.setFrom(coverageExecData)
    violationRules { rule { limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.95".toBigDecimal() } } }
}
