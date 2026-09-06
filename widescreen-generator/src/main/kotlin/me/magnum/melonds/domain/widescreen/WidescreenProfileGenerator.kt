package me.magnum.melonds.domain.widescreen

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

const val GENERATED_WIDESCREEN_PROFILE_CHUNK_SIZE = 128

object WidescreenProfileGenerator {
    fun generate(
        sourcesJson: String,
        profilesJson: String,
    ): String = render(WidescreenManifestValidator.parseAndValidate(sourcesJson, profilesJson))

    fun generate(
        sources: WidescreenSourcesManifest,
        profiles: WidescreenProfilesManifest,
    ): String = render(WidescreenManifestValidator.validate(sources, profiles))

    fun write(
        sourcesFile: Path,
        profilesFile: Path,
        outputFile: Path,
    ) {
        val generated = generate(
            sourcesJson = Files.readString(sourcesFile, UTF_8),
            profilesJson = Files.readString(profilesFile, UTF_8),
        )
        outputFile.parent?.let(Files::createDirectories)
        Files.write(outputFile, generated.toByteArray(UTF_8))
    }

    private fun render(manifests: ValidatedWidescreenManifests): String {
        val approvedProfiles = manifests.profiles.profiles
            .filter { it.validation.activation == ActivationValidation.APPROVED }
        val chunks = approvedProfiles.chunked(GENERATED_WIDESCREEN_PROFILE_CHUNK_SIZE)

        return buildString {
            line("package me.magnum.melonds.domain.widescreen")
            line()
            line("// Generated from app/widescreen manifests. Do not edit.")
            line("internal object GeneratedWidescreenProfiles {")
            line("    val profiles: Map<WidescreenRomKey, WidescreenProfile> =")
            line("        buildMap<WidescreenRomKey, WidescreenProfile> {")
            chunks.indices.forEach { index ->
                line("            addProfiles$index(this)")
            }
            line("        }")

            chunks.forEachIndexed { index, profiles ->
                line()
                line("    private fun addProfiles$index(")
                line("        target: MutableMap<WidescreenRomKey, WidescreenProfile>,")
                line("    ) {")
                profiles.forEach { profile -> appendProfile(profile) }
                line("    }")
            }

            line()
            line("    private fun MutableMap<WidescreenRomKey, WidescreenProfile>.addProfile(")
            line("        id: String,")
            line("        gameCode: String,")
            line("        headerChecksum: UInt,")
            line("        targetRatio: WidescreenRatio,")
            line("        targetScreen: WidescreenTargetScreen,")
            line("        actionReplayCode: String,")
            line("    ) {")
            line("        val romKey = WidescreenRomKey(gameCode, headerChecksum)")
            line("        put(")
            line("            romKey,")
            line("            WidescreenProfile(")
            line("                id = id,")
            line("                romKey = romKey,")
            line("                targetRatio = targetRatio,")
            line("                targetScreen = targetScreen,")
            line("                actionReplayCode = actionReplayCode,")
            line("            ),")
            line("        )")
            line("    }")
            line("}")
        }
    }

    private fun StringBuilder.appendProfile(profile: CanonicalWidescreenProfile) {
        line("        target.addProfile(")
        line("            id = \"${profile.id}\",")
        line("            gameCode = \"${profile.rom.gameCode}\",")
        line("            headerChecksum = 0x${profile.rom.headerChecksum32}u,")
        line("            targetRatio = WidescreenRatio.${profile.targetRatio.name},")
        line("            targetScreen = WidescreenTargetScreen.${profile.targetScreen.name},")
        line("            actionReplayCode = \"${profile.patch.actionReplayLines.joinToString(" ")}\",")
        line("        )")
    }

    private fun StringBuilder.line(value: String = "") {
        append(value).append('\n')
    }
}

object WidescreenProfileGeneratorMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "Usage: WidescreenProfileGeneratorMain <sources.json> <profiles.json> <output.kt>"
        }
        WidescreenProfileGenerator.write(
            sourcesFile = Path.of(args[0]),
            profilesFile = Path.of(args[1]),
            outputFile = Path.of(args[2]),
        )
    }
}
