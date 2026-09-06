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
val usrcheatArtifact = providers.gradleProperty("usrcheatArtifact")
    .map(rootProject::file)
    .orElse(
        rootProject.layout.buildDirectory
            .file("widescreen-identity-upstream/NDS-Cheat-Databases/Cheat Databases/usrcheat.dat")
            .map { it.asFile },
    )
val twilightCandidates = providers.gradleProperty("twilightCandidates")
    .map(rootProject::file)
    .orElse(rootProject.layout.buildDirectory.file("widescreen-quarantine/twilight-widescreen-candidates.json").map { it.asFile })
val identityOutput = providers.gradleProperty("identityOutput")
    .map(rootProject::file)
    .orElse(rootProject.layout.buildDirectory.dir("widescreen-identity-resolution").map { it.asFile })

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

tasks.register<JavaExec>("resolveTWiLightWidescreenIdentities") {
    group = "widescreen maintenance"
    description = "Joins quarantined TWiLight identities with a pinned local usrcheat index"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("me.magnum.melonds.domain.widescreen.TWiLightIdentityResolverMain")
    inputs.file(usrcheatArtifact).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(twilightCandidates).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("app/widescreen/widescreen_sources.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("app/widescreen/widescreen_profiles.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(identityOutput.map { it.resolve("usrcheat-identities.json") })
    outputs.file(identityOutput.map { it.resolve("twilight-identity-resolution.json") })
    outputs.file(identityOutput.map { it.resolve("twilight-identity-summary.json") })
    doFirst {
        setArgs(
            listOf(
                usrcheatArtifact.get().absolutePath,
                twilightCandidates.get().absolutePath,
                rootProject.file("app/widescreen/widescreen_sources.json").absolutePath,
                rootProject.file("app/widescreen/widescreen_profiles.json").absolutePath,
                identityOutput.get().absolutePath,
            ),
        )
    }
}
