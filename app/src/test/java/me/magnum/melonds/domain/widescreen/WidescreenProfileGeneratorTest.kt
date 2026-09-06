package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidescreenProfileGeneratorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sourcesJson by lazy { resourceText("widescreen_sources.json") }
    private val profilesJson by lazy { resourceText("widescreen_profiles.json") }
    private val canonical by lazy {
        WidescreenManifestValidator.parseAndValidate(sourcesJson, profilesJson)
    }

    @Test
    fun generationIsByteForByteDeterministicAcrossDirectories() {
        val firstDirectory = temporaryFolder.newFolder("first").toPath()
        val secondDirectory = temporaryFolder.newFolder("second").toPath()
        val firstOutput = generateFromFiles(firstDirectory)
        val secondOutput = generateFromFiles(secondDirectory)
        val firstBytes = Files.readAllBytes(firstOutput)
        val secondBytes = Files.readAllBytes(secondOutput)

        assertArrayEquals(firstBytes, secondBytes)
        assertEquals(sha256(firstBytes), sha256(secondBytes))
        assertTrue(String(firstBytes, UTF_8).endsWith('\n'))
    }

    @Test
    fun invalidApprovedManifestFailsWithoutAcceptingStaleOutput() {
        val directory = temporaryFolder.newFolder("stale-output").toPath()
        val output = generateFromFiles(directory)
        val validOutput = Files.readAllBytes(output)
        val profiles = directory.resolve("widescreen_profiles.json")
        val invalidProfilesJson = profilesJson.replaceFirst(
            "\"ar\": \"SUPPORTED\"",
            "\"ar\": \"UNSUPPORTED\"",
        )
        assertFalse(invalidProfilesJson == profilesJson)
        Files.write(profiles, invalidProfilesJson.toByteArray(UTF_8))

        assertGenerationRejected("AR capability mismatch") {
            WidescreenProfileGenerator.write(
                directory.resolve("widescreen_sources.json"),
                profiles,
                output,
            )
        }
        assertArrayEquals(validOutput, Files.readAllBytes(output))
    }

    @Test
    fun thousandApprovedProfilesUseBoundedDeterministicChunks() {
        val template = canonical.profiles.profiles.first()
        val profiles = (0 until 1_000).map { index ->
            template.copy(
                id = "synthetic-profile-${index.toString().padStart(4, '0')}",
                rom = template.rom.copy(
                    gameCode = "TEST",
                    headerChecksum32 = index.toString(16).uppercase().padStart(8, '0'),
                ),
            )
        }
        val generated = WidescreenProfileGenerator.generate(
            canonical.sources,
            canonical.profiles.copy(profiles = profiles),
        )
        val chunks = Regex("(?ms)^    private fun addProfiles\\d+\\(\\n.*?^    \\}\\n")
            .findAll(generated)
            .map { it.value }
            .toList()

        assertEquals(1_000, Regex("(?m)^            id = ").findAll(generated).count())
        assertEquals(8, chunks.size)
        assertTrue(
            chunks.all {
                Regex("(?m)^            id = ").findAll(it).count() <=
                    GENERATED_WIDESCREEN_PROFILE_CHUNK_SIZE
            },
        )
    }

    @Test
    fun duplicateApprovedRomKeyFailsGeneration() {
        val mario = canonical.profiles.profiles.first()
        val duplicate = mario.copy(id = "duplicate-approved-rom-key")

        assertGenerationRejected("Duplicate active ROM key") {
            WidescreenProfileGenerator.generate(
                canonical.sources,
                canonical.profiles.copy(profiles = listOf(mario, duplicate)),
            )
        }
    }

    @Test
    fun disabledCandidateIsValidatedButExcluded() {
        val mario = canonical.profiles.profiles.first()
        val pokemon = canonical.profiles.profiles.last()
        val blocked = mario.copy(
            id = "blocked-synthetic-candidate",
            rom = mario.rom.copy(headerChecksum32 = "FFFFFFFF"),
            targetScreen = CanonicalTargetScreen.UNRESOLVED,
            validation = mario.validation.copy(
                identity = IdentityValidation.UNRESOLVED,
                ratio = RatioValidation.NEEDS_REVIEW,
                targetScreen = TargetScreenValidation.UNRESOLVED,
                compatibility = CompatibilityValidation.UNKNOWN,
                trust = TrustValidation.UNVALIDATED,
                activation = ActivationValidation.DISABLED,
            ),
        )

        val generated = WidescreenProfileGenerator.generate(
            canonical.sources,
            canonical.profiles.copy(profiles = listOf(mario, blocked, pokemon)),
        )

        assertEquals(2, Regex("(?m)^            id = ").findAll(generated).count())
        assertFalse(generated.contains(blocked.id))
    }

    @Test
    fun approvedUnsupportedActionReplayFailsGeneration() {
        val mario = canonical.profiles.profiles.first()
        val unsupportedLines = listOf("C1000000 00000000")
        val invalid = mario.copy(
            id = "approved-unsupported-ar",
            rom = mario.rom.copy(headerChecksum32 = "FFFFFFFF"),
            patch = mario.patch.copy(
                byteLength = 8,
                sha256 = actionReplaySha256(unsupportedLines),
                actionReplayLines = unsupportedLines,
            ),
            validation = mario.validation.copy(
                ar = ActionReplayValidation.UNSUPPORTED,
                activation = ActivationValidation.APPROVED,
            ),
        )

        assertGenerationRejected("has unsupported AR") {
            WidescreenProfileGenerator.generate(
                canonical.sources,
                canonical.profiles.copy(profiles = listOf(mario, invalid)),
            )
        }
    }

    private fun generateFromFiles(directory: java.nio.file.Path): java.nio.file.Path {
        val sources = directory.resolve("widescreen_sources.json")
        val profiles = directory.resolve("widescreen_profiles.json")
        val output = directory.resolve("generated/GeneratedWidescreenProfiles.kt")
        Files.write(sources, sourcesJson.toByteArray(UTF_8))
        Files.write(profiles, profilesJson.toByteArray(UTF_8))
        WidescreenProfileGenerator.write(sources, profiles, output)
        return output
    }

    private fun resourceText(name: String): String {
        return requireNotNull(javaClass.classLoader?.getResource(name)) {
            "Missing test resource $name"
        }.readText()
    }

    private fun actionReplaySha256(lines: List<String>): String {
        val bytes = ByteBuffer.allocate(lines.size * 8).order(LITTLE_ENDIAN).apply {
            lines.forEach { line ->
                line.split(' ').forEach { putInt(it.toUInt(16).toInt()) }
            }
        }.array()
        return sha256(bytes)
    }

    private fun sha256(bytes: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun assertGenerationRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected generation failure containing: $expectedMessage")
        } catch (exception: WidescreenManifestValidationException) {
            assertTrue(exception.message.orEmpty().contains(expectedMessage))
        }
    }
}
