import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val widescreenGeneratorClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val widescreenSourcesManifest = layout.projectDirectory.file("widescreen/widescreen_sources.json")
val widescreenProfilesManifest = layout.projectDirectory.file("widescreen/widescreen_profiles.json")
val generatedWidescreenSourceDirectory =
    layout.buildDirectory.dir("generated/source/widescreen/main/kotlin")
val generatedWidescreenSource = generatedWidescreenSourceDirectory.map {
    it.file("me/magnum/melonds/domain/widescreen/GeneratedWidescreenProfiles.kt")
}
val generateWidescreenProfiles by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Validates widescreen manifests and generates the Kotlin runtime registry"
    classpath = widescreenGeneratorClasspath
    mainClass.set("me.magnum.melonds.domain.widescreen.WidescreenProfileGeneratorMain")
    inputs.files(widescreenSourcesManifest, widescreenProfilesManifest)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(generatedWidescreenSource)
    args(
        widescreenSourcesManifest.asFile.absolutePath,
        widescreenProfilesManifest.asFile.absolutePath,
        generatedWidescreenSource.get().asFile.absolutePath,
    )
}

android {
    signingConfigs {
        create("release") {
            val props = gradleLocalProperties(rootDir, providers)
            (props["MELONDS_KEYSTORE"] as String?)?.let { storeFile = file(it) }
            storePassword = props["MELONDS_KEYSTORE_PASSWORD"] as String? ?: ""
            keyAlias = props["MELONDS_KEY_ALIAS"] as String? ?: ""
            keyPassword = props["MELONDS_KEY_PASSWORD"] as String? ?: ""
        }
    }

    namespace = "me.magnum.melonds"
    compileSdk = AppConfig.compileSdkVersion
    ndkVersion = AppConfig.ndkVersion
    defaultConfig {
        applicationId = "me.magnum.melonds"
        minSdk = AppConfig.minSdkVersion
        targetSdk = AppConfig.targetSdkVersion
        versionCode = AppConfig.versionCode
        versionName = AppConfig.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -Wno-write-strings")
            }
        }
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".dev"
        }
    }

    flavorDimensions.add("version")
    flavorDimensions.add("build")
    productFlavors {
        create("playStore") {
            dimension = "version"
            versionNameSuffix = " PS"
        }
        create("gitHub") {
            dimension = "version"
            isDefault = true
            versionNameSuffix = " GH"
        }

        create("prod") {
            dimension = "build"
            isDefault = true
        }
        create("nightly") {
            dimension = "build"
            applicationIdSuffix = ".nightly"
            versionNameSuffix = " (NIGHTLY)"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main").kotlin.directories +=
            generatedWidescreenSourceDirectory.get().asFile.absolutePath
        // Adds exported schema location as test app assets.
        getByName("androidTest").assets.directories += "$projectDir/schemas"
        getByName("test").resources.directories += "$projectDir/widescreen"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    val gitHubImplementation by configurations

    add(widescreenGeneratorClasspath.name, projects.widescreenGenerator)

    implementation(projects.masterswitch)
    implementation(projects.rcheevosApi)
    implementation(projects.common)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.rxjava)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work)
    implementation(libs.android.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil)
    implementation(libs.gson)
    implementation(libs.hilt)
    implementation(libs.kotlin.serialization)
    implementation(libs.kotlinx.coroutines.rx)
    implementation(libs.picasso)
    implementation(libs.markwon)
    implementation(libs.markwon.imagepicasso)
    implementation(libs.markwon.linkify)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    gitHubImplementation(libs.retrofit)
    gitHubImplementation(libs.retrofit.converter.kotlinx)

    ksp(libs.hilt.compiler)
    ksp(libs.hilt.compiler.android)
    ksp(libs.room.compiler)

    testImplementation(projects.widescreenGenerator)
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateWidescreenProfiles)
}
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(generateWidescreenProfiles)
}
