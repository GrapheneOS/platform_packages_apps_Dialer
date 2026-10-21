import dialer.signing.SharedKey
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application)
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
    namespace = "com.android.dialer.integration.connection"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.android.dialer.integration.connection"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("shared") {
            val sharedKey = SharedKey.fromProperties(providers)
            if (sharedKey == null) {
                initWith(getByName("debug"))
            } else {
                storeFile = rootProject.file(sharedKey.storeFile)
                storePassword = sharedKey.storePassword
                keyAlias = sharedKey.keyAlias
                keyPassword = sharedKey.keyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    sourceSets.getByName("main") {
        java.directories.add("../tests/integration/helper/src")
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}
