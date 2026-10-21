import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("dialer.device-tests")
    alias(libs.plugins.android.test)
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
    namespace = "com.android.dialer.integrationtests"
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 37
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    installation {
        installOptions += "-g"
    }

    buildTypes {
        create("systemTest") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets.getByName("main") {
        java.directories.add("../tests/integration/src")
        kotlin.directories.add("../tests/integration/src")
    }
}

androidComponents {
    beforeVariants { variant ->
        variant.enable = variant.buildType == "systemTest"
    }

    onVariants { variant ->
        val testedApks = configurations.getByName("${variant.name}TestedApks")
        variant.compileConfiguration.shouldResolveConsistentlyWith(testedApks)
        variant.runtimeConfiguration.shouldResolveConsistentlyWith(testedApks)
    }
}

deviceTests {
    adb.set(androidComponents.sdkComponents.adb)
    installTasks.add(":dialer-integration-connection:installDebug")
    packagesToUninstall.add("com.android.dialer.integration.connection")
}

dependencies {
    implementation(libs.protobuf.javalite)

    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.truth)
}
