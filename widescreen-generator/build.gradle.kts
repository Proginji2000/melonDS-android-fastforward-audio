import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
    }
}

dependencies {
    implementation(libs.kotlin.serialization)
}

val twilightCheckout = providers.gradleProperty("twilightCheckout")
    .map(rootProject::file)
    .orElse(rootProject.layout.buildDirectory.dir("widescreen-upstream/TWiLightMenu").map { it.asFile })
val twilightOutput = providers.gradleProperty("twilightOutput")
    .map(rootProject::file)
    .orElse(rootProject.layout.buildDirectory.dir("widescreen-quarantine").map { it.asFile })

tasks.register<JavaExec>("importTWiLightWidescreenCandidates") {
    group = "widescreen maintenance"
    description = "Analyzes a pinned local TWiLightMenu checkout into build-only quarantine JSON"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("me.magnum.melonds.domain.widescreen.TWiLightWidescreenImporterMain")
    inputs.dir(twilightCheckout.map { it.resolve("resources/widescreen") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(twilightOutput.map { it.resolve("twilight-widescreen-candidates.json") })
    outputs.file(twilightOutput.map { it.resolve("twilight-widescreen-summary.json") })
    doFirst {
        setArgs(listOf(twilightCheckout.get().absolutePath, twilightOutput.get().absolutePath))
    }
}
