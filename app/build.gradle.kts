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

// Keep the original resource overlay order: aapt2 overlays later directories over earlier ones,
// and resource de-duplication relies on that precedence.
val resDirs = listOf(
    "../assets/product/res",
    "../assets/quantum/res",
    "../java/com/android/contacts/common/res",
    "../java/com/android/dialer/about/res",
    "../java/com/android/dialer/app/res",
    "../java/com/android/dialer/assisteddialing/res",
    "../java/com/android/dialer/assisteddialing/ui/res",
    "../java/com/android/dialer/blocking/res",
    "../java/com/android/dialer/blockreportspam/res",
    "../java/com/android/dialer/callcomposer/camera/camerafocus/res",
    "../java/com/android/dialer/callcomposer/cameraui/res",
    "../java/com/android/dialer/callcomposer/res",
    "../java/com/android/dialer/calldetails/res",
    "../java/com/android/dialer/calllog/ui/menu/res",
    "../java/com/android/dialer/calllog/ui/res",
    "../java/com/android/dialer/calllogutils/res",
    "../java/com/android/dialer/clipboard/res",
    "../java/com/android/dialer/common/preference/res",
    "../java/com/android/dialer/common/res",
    "../java/com/android/dialer/contactphoto/res",
    "../java/com/android/dialer/contacts/displaypreference/res",
    "../java/com/android/dialer/contacts/resources/res",
    "../java/com/android/dialer/contactsfragment/res",
    "../java/com/android/dialer/dialpadview/res",
    "../java/com/android/dialer/dialpadview/theme/res",
    "../java/com/android/dialer/enrichedcall/simulator/res",
    "../java/com/android/dialer/glidephotomanager/impl/res",
    "../java/com/android/dialer/historyitemactions/res",
    "../java/com/android/dialer/interactions/res",
    "../java/com/android/dialer/lettertile/res",
    "../java/com/android/dialer/main/impl/bottomnav/res",
    "../java/com/android/dialer/main/impl/res",
    "../java/com/android/dialer/main/impl/toolbar/res",
    "../java/com/android/dialer/notification/res",
    "../java/com/android/dialer/oem/res",
    "../java/com/android/dialer/phonenumberutil/res",
    "../java/com/android/dialer/postcall/res",
    "../java/com/android/dialer/preferredsim/impl/res",
    "../java/com/android/dialer/preferredsim/suggestion/res",
    "../java/com/android/dialer/promotion/impl/res",
    "../java/com/android/dialer/rtt/res",
    "../java/com/android/dialer/searchfragment/common/res",
    "../java/com/android/dialer/searchfragment/cp2/res",
    "../java/com/android/dialer/searchfragment/directories/res",
    "../java/com/android/dialer/searchfragment/list/res",
    "../java/com/android/dialer/searchfragment/nearbyplaces/res",
    "../java/com/android/dialer/searchfragment/remote/res",
    "../java/com/android/dialer/shortcuts/res",
    "../java/com/android/dialer/spam/promo/res",
    "../java/com/android/dialer/spannable/res",
    "../java/com/android/dialer/speeddial/res",
    "../java/com/android/dialer/theme/base/res",
    "../java/com/android/dialer/theme/common/res",
    "../java/com/android/dialer/theme/hidden/res",
    "../java/com/android/dialer/theme/res",
    "../java/com/android/dialer/util/res",
    "../java/com/android/dialer/voicemail/listui/error/res",
    "../java/com/android/dialer/voicemail/listui/res",
    "../java/com/android/dialer/voicemail/settings/res",
    "../java/com/android/dialer/widget/res",
    "../java/com/android/incallui/answer/impl/affordance/res",
    "../java/com/android/incallui/answer/impl/answermethod/res",
    "../java/com/android/incallui/answer/impl/hint/res",
    "../java/com/android/incallui/answer/impl/res",
    "../java/com/android/incallui/audioroute/res",
    "../java/com/android/incallui/autoresizetext/res",
    "../java/com/android/incallui/callpending/res",
    "../java/com/android/incallui/commontheme/res",
    "../java/com/android/incallui/contactgrid/res",
    "../java/com/android/incallui/disconnectdialog/res",
    "../java/com/android/incallui/hold/res",
    "../java/com/android/incallui/incall/impl/res",
    "../java/com/android/incallui/res",
    "../java/com/android/incallui/rtt/impl/res",
    "../java/com/android/incallui/sessiondata/res",
    "../java/com/android/incallui/spam/res",
    "../java/com/android/incallui/speakerbuttonlogic/res",
    "../java/com/android/incallui/telecomeventui/res",
    "../java/com/android/incallui/theme/res",
    "../java/com/android/incallui/video/impl/res",
    "../java/com/android/incallui/video/protocol/res",
    "../java/com/android/voicemail/impl/configui/res",
    "../java/com/android/voicemail/impl/res"
)

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
        res.directories.addAll(resDirs)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    implementation(libs.commons.io)
    implementation(libs.dagger)
    implementation(libs.error.prone.annotations)
    implementation(libs.glide)
    implementation(libs.guava)
    implementation(libs.hilt.android)
    implementation(libs.javax.inject)
    implementation(libs.jsr305)
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.mime4j.core)
    implementation(libs.mime4j.dom)
    implementation(libs.shortcutbadger)
    implementation(libs.volley)
    implementation(libs.zxing.core)

    implementation(libs.protobuf.javalite)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)

    compileOnly(libs.auto.value.annotations)

    // AutoValue has no KSP processor, and Glide's lives in a separate artifact not worth
    // adopting for one empty AppGlideModule, so both stay on javac. Dagger does have one, and
    // hilt-compiler drags dagger-compiler onto the KSP classpath: leaving Dagger on javac too
    // would run its component processor twice and emit DaggerAospDialerRootComponent twice.
    annotationProcessor(libs.auto.value)
    annotationProcessor(libs.glide.compiler)
    ksp(libs.dagger.compiler)
    ksp(libs.hilt.compiler)

    // Loses to guava on the classpath; present only so the two do not clash.
    implementation(libs.guava.listenablefuture)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    testImplementation(libs.junit)
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
