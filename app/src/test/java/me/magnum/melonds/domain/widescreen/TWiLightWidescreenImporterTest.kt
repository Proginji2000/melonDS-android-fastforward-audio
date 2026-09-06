package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TWiLightWidescreenImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = false }
    private val pokemonLines = listOf(
        "922822A8 00001555",
        "122822A8 0000199A",
        "D2000000 00000000",
    )

    @Test
    fun pokemonSentinelDecodesExactPinnedUpstreamPatch() {
        val fixture = createFixture("pokemon")
        val result = import(fixture, "output")
        val pokemon = candidates(result).single { it.sourceFilename == "IRAF-BC1D.bin" }

        assertEquals("IRAF", pokemon.upstreamTid)
        assertEquals("BC1D", pokemon.upstreamHeaderCrc16)
        assertEquals(24L, pokemon.sizeBytes)
        assertEquals(
            "74f06acc1af52765f1e87a4dd40473e7dc091c785a71eca5621205694e7de8ca",
            pokemon.sourceSha256,
        )
        assertEquals(pokemon.sourceSha256, pokemon.canonicalArBinarySha256)
        assertEquals(pokemonLines, pokemon.actionReplayLines)
        assertEquals("SUPPORTED", pokemon.arValidationStatus)
        assertEquals(listOf("9", "1", "D2"), pokemon.usedOpcodes)
        assertFalse(pokemon.upstreamWildcard)
    }

    @Test
    fun secondGoldenPatchDecodesExactPinnedUpstreamBytes() {
        val fixture = createFixture("second-golden")
        val result = import(fixture, "output")
        val golden = candidates(result).single { it.sourceFilename == "A3WE-54B8.bin" }

        assertEquals(
            "37b33e0f99289923b772772da87361d322a16e3ed909d8d8d7f61ee857bef213",
            golden.sourceSha256,
        )
        assertEquals(
            listOf(
                "9206EB58 00001555",
                "1206EB58 0000199A",
                "D2000000 00000000",
            ),
            golden.actionReplayLines,
        )
        assertEquals("SUPPORTED", golden.arValidationStatus)
    }

    @Test
    fun canonicalArtifactsAreDeterministicAcrossOutputDirectories() {
        val fixture = createFixture("deterministic", includeDiagnostics = true)
        val first = import(fixture, "first")
        val second = import(fixture, "second")

        assertArrayEquals(Files.readAllBytes(first.candidatesPath), Files.readAllBytes(second.candidatesPath))
        assertArrayEquals(Files.readAllBytes(first.summaryPath), Files.readAllBytes(second.summaryPath))
        assertEquals(first.candidatesSha256, second.candidatesSha256)
        assertEquals(first.summarySha256, second.summarySha256)
        val summary = json.decodeFromString<TWiLightWidescreenSummary>(
            String(Files.readAllBytes(first.summaryPath), UTF_8),
        )
        assertEquals(ACTION_REPLAY_CAPABILITY_VERSION, summary.actionReplayCapability.version)
        assertEquals(ACTION_REPLAY_ENGINE_CORE_COMMIT, summary.actionReplayCapability.coreCommit)
    }

    @Test
    fun wildcardVariantsAndNonPaddedCrcRemainUnresolvedQuarantineRecords() {
        val fixture = createFixture("identity")
        val result = import(fixture, "output")
        val candidates = candidates(result)

        val wildcard = candidates.single { it.sourceFilename == "WILD-FFFF.bin" }
        assertTrue(wildcard.upstreamWildcard)
        assertEquals("UNRESOLVED", wildcard.runtimeIdentityStatus)
        assertEquals("QUARANTINED", wildcard.promotionStatus)

        val variants = candidates.filter { it.upstreamTid == "MULT" }
        assertEquals(listOf("0001", "0002"), variants.mapNotNull { it.upstreamHeaderCrc16 }.sorted())
        assertTrue(variants.all { it.runtimeIdentityStatus == "AMBIGUOUS" })

        val nonPadded = candidates.single { it.sourceFilename == "ABCD-ABC.bin" }
        assertEquals("ABC", nonPadded.upstreamHeaderCrc16Original)
        assertEquals("0ABC", nonPadded.upstreamHeaderCrc16)
        assertNull(nonPadded.headerChecksum32)
        assertTrue(candidates.all { it.classificationStatus == "UNCLASSIFIED" })
        assertTrue(candidates.all { it.targetScreenStatus == "UNRESOLVED" })
        assertTrue(candidates.all { it.conversionStatus == "NOT_EVALUATED" })
        assertTrue(candidates.all { it.promotionStatus == "QUARANTINED" })
    }

    @Test
    fun atypicalAndUndecodableFilesAreReportedInsteadOfDropped() {
        val fixture = createFixture("diagnostics", includeDiagnostics = true)
        val result = import(fixture, "output")
        val candidates = candidates(result)

        val malformed = candidates.single { it.sourceFilename == "bad-name.bin" }
        assertEquals("UNRECOGNIZED_FILENAME", malformed.filenameStatus)
        assertEquals("DECODE_FAILED", malformed.decodeStatus)
        assertTrue(malformed.decodeDiagnostics.any { "multiple of 8" in it })

        val readme = candidates.single { it.sourceFilename == "README.md" }
        assertEquals("UNRECOGNIZED_FILENAME", readme.filenameStatus)
        assertEquals("UNSUPPORTED_FORMAT", readme.decodeStatus)
        assertTrue(result.counts.atypicalFilenameCount >= 2)
        assertTrue(result.counts.undecodableCount >= 2)
    }

    @Test
    fun importCannotMutateProductionManifestOrPromoteRuntimeProfiles() {
        val profilesPath = repositoryFile("widescreen/widescreen_profiles.json")
        val sourcesPath = repositoryFile("widescreen/widescreen_sources.json")
        val before = Files.readAllBytes(profilesPath)
        val fixture = createFixture("production-invariant")

        val result = import(fixture, "output")
        val generated = WidescreenProfileGenerator.generate(
            String(Files.readAllBytes(sourcesPath), UTF_8),
            String(Files.readAllBytes(profilesPath), UTF_8),
        )

        assertArrayEquals(before, Files.readAllBytes(profilesPath))
        assertEquals(2, Regex("(?m)^            id = ").findAll(generated).count())
        assertTrue(candidates(result).all { it.promotionStatus == "QUARANTINED" })
    }

    private fun createFixture(name: String, includeDiagnostics: Boolean = false): Path {
        val checkout = temporaryFolder.newFolder(name).toPath()
        val corpus = Files.createDirectories(checkout.resolve("resources/widescreen"))
        writeActionReplay(corpus.resolve("IRAF-BC1D.bin"), pokemonLines)
        writeActionReplay(
            corpus.resolve("A3WE-54B8.bin"),
            listOf(
                "9206EB58 00001555",
                "1206EB58 0000199A",
                "D2000000 00000000",
            ),
        )
        writeActionReplay(corpus.resolve("ABCD-ABC.bin"), listOf("02000000 00000001"))
        writeActionReplay(corpus.resolve("WILD-FFFF.bin"), listOf("02000004 00000002"))
        writeActionReplay(corpus.resolve("MULT-1.bin"), listOf("02000008 00000003"))
        writeActionReplay(corpus.resolve("MULT-0002.bin"), listOf("0200000C 00000004"))
        if (includeDiagnostics) {
            Files.write(corpus.resolve("bad-name.bin"), byteArrayOf(1, 2, 3))
            Files.write(corpus.resolve("README.md"), "fixture\n".toByteArray(UTF_8))
        }
        return checkout
    }

    private fun import(checkout: Path, outputName: String): TWiLightImportResult {
        return TWiLightWidescreenImporter.importCheckout(
            checkout,
            checkout.resolve("outputs/$outputName"),
        )
    }

    private fun candidates(result: TWiLightImportResult): List<TWiLightWidescreenCandidate> {
        return json.decodeFromString<TWiLightWidescreenCandidatesDocument>(
            String(Files.readAllBytes(result.candidatesPath), UTF_8),
        ).candidates
    }

    private fun writeActionReplay(path: Path, lines: List<String>) {
        val bytes = ByteBuffer.allocate(lines.size * 8).order(LITTLE_ENDIAN).apply {
            lines.forEach { line ->
                putInt(line.substring(0, 8).toUInt(16).toInt())
                putInt(line.substring(9, 17).toUInt(16).toInt())
            }
        }.array()
        Files.write(path, bytes)
    }

    private fun repositoryFile(relativeToApp: String): Path {
        val candidates = listOf(
            Path.of(relativeToApp),
            Path.of("app").resolve(relativeToApp),
        )
        return candidates.firstOrNull(Files::isRegularFile)
            ?: error("Cannot locate repository file $relativeToApp from ${Path.of("").toAbsolutePath()}")
    }
}
