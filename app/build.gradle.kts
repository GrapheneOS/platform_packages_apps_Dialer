import com.google.protobuf.gradle.proto
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val ownedKotlin = files("src", "../java/com/android/dialer/binary/aosp/AospDialerApplication.kt")

detekt {
    basePath.set(rootDir)
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoredBuildTypes = listOf("release")
    parallel = true
    source.setFrom(ownedKotlin)
}

tasks.withType<Detekt>().configureEach {
    setSource(ownedKotlin)
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/build/**")
}

configure<KtlintExtension> {
    val ownedKotlinFiles = ownedKotlin.files
    filter {
        exclude { element ->
            !element.isDirectory &&
                element.file.extension != "kts" &&
                ownedKotlinFiles.none { element.file.toPath().startsWith(it.toPath()) }
        }
    }
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("ktlintCheck"))
}

android {
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    namespace = "com.android.dialer"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        resValues = true
    }

    defaultConfig {
        minSdk = 37
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 2900000
        versionName = "23.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "applicationLabel", "Phone d")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../proguard.flags",
                "../java/com/android/dialer/common/proguard.flags",
                "../java/com/android/dialer/proguard/proguard.flags",
                "../java/com/android/incallui/answer/impl/proguard.flags",
                "../java/com/android/voicemail/impl/proguard.flags",
                "proguard-rules.pro"
            )
        }
    }

    sourceSets.getByName("main") {
        manifest.srcFile("../AndroidManifest.xml")
        java.directories.add("../java")
        kotlin.directories.add("../java")
        aidl.directories.add("../java")
        proto { srcDir("../java") }
        res.directories.addAll(
            listOf("../assets", "../java").flatMap { root ->
                file(root).walkTopDown()
                    .filter { it.isDirectory && it.name == "res" }
                    .map { it.relativeTo(projectDir).invariantSeparatorsPath }
                    .toList()
            }.sorted()
        )
    }

    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
        jniLibs.useLegacyPackaging = false
    }

    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("UnusedResources", "UnusedIds")
        checkReleaseBuilds = false
    }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        create("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
            task.plugins { create("grpc") { option("lite") } }
        }
    }
}

ksp {
    arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.interpolator)
    implementation(libs.androidx.legacy.support.v13)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.loader)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.transition)
    implementation(libs.androidx.viewpager)
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.dagger)
    implementation(libs.hilt.android)
    implementation(libs.javax.inject)

    compileOnly(libs.auto.value.annotations)

    annotationProcessor(libs.auto.value)
    annotationProcessor(libs.glide.compiler)
    ksp(libs.dagger.compiler)
    ksp(libs.hilt.compiler)

    implementation(libs.commons.io)
    implementation(libs.error.prone.annotations)
    implementation(libs.glide)
    implementation(libs.guava)
    implementation(libs.guava.listenablefuture)
    implementation(libs.jsr305)
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.mime4j.core)
    implementation(libs.mime4j.dom)
    implementation(libs.shortcutbadger)
    implementation(libs.volley)
    implementation(libs.zxing.core)

    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.javalite)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.mockk.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.turbine)
}
