import com.google.protobuf.gradle.proto
import dev.detekt.gradle.Detekt

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Robolectric refuses to build a sandbox for Android SDK 36 on anything below Java 21, so the
// tests need a newer launcher than the toolchain the app compiles against.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

detekt {
    basePath.set(rootDir)
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoredBuildTypes = listOf("release")
    parallel = true
    source.setFrom(files("../java", "src"))
}

tasks.withType<Detekt>().configureEach {
    setSource(files("../java", "src"))
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/build/**")
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("ktlintCheck"))
}

android {
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    namespace = "com.android.dialer"

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        resValues = true
    }

    // No testOptions { unitTests { isIncludeAndroidResources = true } } here, unlike the sibling
    // apps. With binary resources on, Robolectric parses the packaged unit-test manifest and
    // rejects minSdkVersion 37 while it emulates API 36, and @Config(manifest = Config.NONE) does
    // not exempt a test from that parse. Revisit once Robolectric supports API 37.

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
                // The set Soong matched with proguard_flags_files: ["**/proguard.flags"].
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
        // Keep generated build directories outside protoc's source root.
        proto { srcDir("../java") }
        // Every res directory under assets/ and java/, sorted. aapt2 overlays later directories
        // over earlier ones, and upstream's Android.bp RES_DIRS is itself sorted, so sorting
        // reproduces its precedence and picks up directories upstream adds. A directory that has
        // to overlay everything else goes in a separate addAll call after this one.
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
        // mime4j-core and mime4j-dom each ship one; neither is used at runtime.
        resources.excludes += "META-INF/DEPENDENCIES"
        // Preserve legacy native-library packaging without a manifest attribute rejected by AGP.
        jniLibs.useLegacyPackaging = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("UnusedResources", "UnusedIds")
        // AGP cannot serialize vital-lint locations from the external main source directory.
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
    // Dialer's 33 @Modules belong to AospDialerRootComponent, not to Hilt's graph, and Hilt
    // otherwise demands @InstallIn on every @Module it compiles. Annotating them instead would
    // mean editing upstream AOSP files forever. A new Hilt module that forgets @InstallIn still
    // fails to compile as a missing binding, unless all it contributes is multibindings.
    arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core)
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

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.dagger)
    implementation(libs.hilt.android)
    implementation(libs.javax.inject)

    compileOnly(libs.auto.value.annotations)

    // AutoValue has no KSP processor, and Glide's lives in a separate artifact not worth
    // adopting for one empty AppGlideModule, so both stay on javac. Dagger does have one, and
    // hilt-compiler drags dagger-compiler onto the KSP classpath: leaving Dagger on javac too
    // would run its component processor twice and emit DaggerAospDialerRootComponent twice.
    annotationProcessor(libs.auto.value)
    annotationProcessor(libs.glide.compiler)
    ksp(libs.dagger.compiler)
    ksp(libs.hilt.compiler)

    implementation(libs.commons.io)
    implementation(libs.error.prone.annotations)
    implementation(libs.glide)
    implementation(libs.guava)
    // Loses to guava on the classpath; present only so the two do not clash.
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
}
