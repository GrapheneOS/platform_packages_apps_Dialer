import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("dialer.device-tests")
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configure<KtlintExtension> {
    filter {
        exclude { element -> !element.isDirectory && element.file.extension != "kts" }
    }
}

android {
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    namespace = "com.android.dialer.outofprocess.tests"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 37
        testApplicationId = "com.android.dialer.outofprocess.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        targetSdk = 37
    }

    installation {
        installOptions += "-g"
    }
}

// Inside android {} this source set fails a cast to a legacy AGP type.
configure<LibraryExtension> {
    sourceSets.getByName("androidTest") {
        java.directories.add("../tests/outofprocess/src")
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

tasks.named("check") {
    dependsOn("assembleAndroidTest")
}

deviceTests {
    adb.set(androidComponents.sdkComponents.adb)
    installTasks.addAll(":app:installSystemTest", ":dialer-integration-connection:installDebug")
    packagesToUninstall.add("com.android.dialer.integration.connection")
}

dependencies {
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.truth)
}
