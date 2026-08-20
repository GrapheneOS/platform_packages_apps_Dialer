plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.protobuf) apply false
}

buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}
