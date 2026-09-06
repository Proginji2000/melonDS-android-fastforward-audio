package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRomIdentityScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileSmallerThanHeaderIsSkipped() {
        val root = newRoot()
        Files.write(root.resolve("small.nds"), ByteArray(LOCAL_ROM_HEADER_SIZE - 1))

        val result = LocalRomIdentityScanner.scan(root)

        assertEquals(1, result.stats.filesDiscovered)
        assertEquals(0, result.stats.filesAccepted)
        assertEquals(1, result.stats.filesTooSmall)
        assertTrue(result.identities.isEmpty())
    }

    @Test
    fun validGameCodeIsReadExactlyAndNdsAndSrlAreSupported() {
        val root = newRoot()
        writeRom(root.resolve("one.nds"), syntheticHeader("TEST", 0x1234))
        writeRom(root.resolve("nested/two.SRL"), syntheticHeader("AB12", 0x5678))
        Files.write(root.resolve("ignored.zip"), ByteArray(LOCAL_ROM_HEADER_SIZE))

        val result = LocalRomIdentityScanner.scan(root)

        assertEquals(2, result.stats.filesDiscovered)
        assertEquals(2, result.stats.filesAccepted)
        assertEquals(listOf("AB12", "TEST"), result.identities.map { it.gameCode })
    }

    @Test
    fun nulNonAsciiAndLowercaseGameCodesAreRejectedWithoutNormalization() {
        val root = newRoot()
        writeRom(root.resolve("nul.nds"), syntheticHeader("TEST", 0x1234).apply { this[NDS_GAME_CODE_OFFSET] = 0 })
        writeRom(root.resolve("non-ascii.nds"), syntheticHeader("TEST", 0x1234).apply {
            this[NDS_GAME_CODE_OFFSET] = 0x80.toByte()
        })
        writeRom(root.resolve("lowercase.nds"), syntheticHeader("test", 0x1234))

        val result = LocalRomIdentityScanner.scan(root)

        assertEquals(3, result.stats.filesDiscovered)
        assertEquals(0, result.stats.filesAccepted)
        assertEquals(3, result.stats.filesInvalidHeader)
        assertTrue(result.evidenceRecords.isEmpty())
    }

    @Test
    fun twilightCrc16IsTheStoredLittleEndianHeaderField() {
        val root = newRoot()
        writeRom(root.resolve("stored.nds"), syntheticHeader("TEST", 0xABCD))

        val identity = LocalRomIdentityScanner.scan(root).identities.single()

        assertEquals("ABCD", identity.upstreamHeaderCrc16)
        assertEquals("NDS_HEADER_STORED_U16_LE_AT_0x15E", TWILIGHT_WIDESCREEN_CRC16_V1)
    }

    @Test
    fun headerChecksum32MatchesIndependentFixedJamcrcVectors() {
        val root = newRoot()
        writeRom(root.resolve("zero-filled.nds"), syntheticHeader("TEST", 0x1234))
        val patterned = ByteArray(LOCAL_ROM_HEADER_SIZE) { ((it * 37 + 11) and 0xFF).toByte() }
        "ABCD".toByteArray(US_ASCII).copyInto(patterned, NDS_GAME_CODE_OFFSET)
        ByteBuffer.wrap(patterned).order(LITTLE_ENDIAN).putShort(NDS_HEADER_CRC16_OFFSET, 0xBEEF.toShort())
        writeRom(root.resolve("patterned.nds"), patterned)

        val identities = LocalRomIdentityScanner.scan(root).identities.associateBy { it.gameCode }

        assertEquals("39FC5058", identities.getValue("TEST").headerChecksum32)
        assertEquals("83708799", identities.getValue("ABCD").headerChecksum32)
    }

    @Test
    fun duplicateTriplesAreDeduplicatedAndBytesAfterHeaderAreIrrelevant() {
        val root = newRoot()
        val header = syntheticHeader("TEST", 0x1234)
        writeRom(root.resolve("a.nds"), header + byteArrayOf(1, 2, 3))
        writeRom(root.resolve("nested/b.nds"), header + ByteArray(4096) { 0x7F })

        val result = LocalRomIdentityScanner.scan(root)

        assertEquals(2, result.stats.filesAccepted)
        assertEquals(1, result.stats.duplicateLocalEvidenceCount)
        assertEquals(1, result.identities.size)
        assertEquals(1, result.evidenceRecords.size)
    }

    @Test
    fun divergentLocalChecksumsForSameGameCodeAndCrcResolveAsConflict() {
        val root = newRoot()
        writeRom(root.resolve("first.nds"), syntheticHeader("TEST", 0x1234))
        writeRom(root.resolve("second.nds"), syntheticHeader("TEST", 0x1234).apply { this[0] = 1 })
        val scan = LocalRomIdentityScanner.scan(root)

        val resolution = resolve(candidate("TEST", "1234"), identityIndex(), scan.evidenceRecords)

        assertEquals(2, scan.evidenceRecords.size)
        assertEquals(ResolvedRuntimeIdentityStatus.CONFLICT, resolution.runtimeIdentityStatus)
        assertNull(resolution.resolvedHeaderChecksum32)
    }

    @Test
    fun ffffLocalHeaderIsRetainedForDiagnosticsButNeverBecomesEvidence() {
        val root = newRoot()
        writeRom(root.resolve("wild.nds"), syntheticHeader("WILD", 0xFFFF))
        val scan = LocalRomIdentityScanner.scan(root)

        val resolution = resolve(candidate("WILD", "FFFF"), identityIndex(), scan.evidenceRecords)

        assertEquals(1, scan.stats.wildcardIdentityCount)
        assertEquals(1, scan.identities.size)
        assertTrue(scan.evidenceRecords.isEmpty())
        assertEquals(ResolvedRuntimeIdentityStatus.UNRESOLVED, resolution.runtimeIdentityStatus)
    }

    @Test
    fun localExactEvidenceResolvesAndUsrcheatCanCorroborateIt() {
        val root = newRoot()
        writeRom(root.resolve("exact.nds"), syntheticHeader("TEST", 0x1234))
        val evidence = LocalRomIdentityScanner.scan(root).evidenceRecords

        val resolution = resolve(candidate("TEST", "1234"), identityIndex("TEST" to "39FC5058"), evidence)

        assertEquals(ResolvedRuntimeIdentityStatus.RESOLVED, resolution.runtimeIdentityStatus)
        assertEquals("39FC5058", resolution.resolvedHeaderChecksum32)
        assertEquals(listOf("USRCHEAT_CORROBORATES_EXACT_EVIDENCE"), resolution.diagnostics)
    }

    @Test
    fun localExactChecksumAbsentFromUsrcheatIsKeptAndResolves() {
        val root = newRoot()
        writeRom(root.resolve("exact.nds"), syntheticHeader("TEST", 0x1234))
        val evidence = LocalRomIdentityScanner.scan(root).evidenceRecords

        val resolution = resolve(candidate("TEST", "1234"), identityIndex("TEST" to "11111111"), evidence)

        assertEquals(ResolvedRuntimeIdentityStatus.RESOLVED, resolution.runtimeIdentityStatus)
        assertEquals("39FC5058", resolution.resolvedHeaderChecksum32)
        assertEquals(listOf("EXACT_EVIDENCE_NOT_PRESENT_IN_PINNED_USRCHEAT"), resolution.diagnostics)
    }

    @Test
    fun matchingRuntimeAndLocalEvidenceRemainExactWithBothReferences() {
        val root = newRoot()
        writeRom(root.resolve("exact.nds"), syntheticHeader("TEST", 0x1234))
        val local = LocalRomIdentityScanner.scan(root).evidenceRecords.single()
        val runtime = local.copy(
            id = "runtime-observed-fixture",
            type = IdentityEvidenceType.RUNTIME_OBSERVED,
            sourceRef = "runtime-validation:fixture",
        )
        val merged = TWiLightIdentityResolutionRunner.mergeEvidence(listOf(runtime, local), listOf(local))

        val resolution = resolve(
            candidate("TEST", "1234"),
            identityIndex("TEST" to "39FC5058"),
            merged,
        )

        assertEquals(ResolvedRuntimeIdentityStatus.RESOLVED, resolution.runtimeIdentityStatus)
        assertEquals(IdentityEvidenceStatus.EXACT_EVIDENCE, resolution.evidenceStatus)
        assertEquals(2, merged.size)
        assertEquals(2, resolution.evidence.size)
    }

    @Test
    fun divergentRuntimeAndLocalEvidenceConflictWithoutPriority() {
        val root = newRoot()
        writeRom(root.resolve("exact.nds"), syntheticHeader("TEST", 0x1234))
        val local = LocalRomIdentityScanner.scan(root).evidenceRecords.single()
        val runtime = local.copy(
            id = "runtime-observed-fixture",
            type = IdentityEvidenceType.RUNTIME_OBSERVED,
            sourceRef = "runtime-validation:fixture",
            headerChecksum32 = "11111111",
        )

        val resolution = resolve(
            candidate("TEST", "1234"),
            identityIndex("TEST" to "39FC5058"),
            listOf(runtime, local),
        )

        assertEquals(ResolvedRuntimeIdentityStatus.CONFLICT, resolution.runtimeIdentityStatus)
        assertNull(resolution.resolvedHeaderChecksum32)
        assertEquals(2, resolution.evidence.size)
    }

    @Test
    fun localEvidenceJsonIsDeterministicSortedAndContainsNoLocalMetadata() {
        val root = newRoot()
        writeRom(root.resolve("z-last.nds"), syntheticHeader("TEST", 0x1234))
        writeRom(root.resolve("a-first.nds"), syntheticHeader("ABCD", 0x5678))

        val first = LocalRomIdentityScanner.scan(root)
        val second = LocalRomIdentityScanner.scan(root)
        val json = String(first.evidenceBytes, UTF_8)

        assertArrayEquals(first.evidenceBytes, second.evidenceBytes)
        assertEquals(listOf("ABCD", "TEST"), first.identities.map { it.gameCode })
        assertFalse(json.contains("z-last.nds"))
        assertFalse(json.contains("a-first.nds"))
        assertFalse(json.contains(root.toString()))
        assertFalse(json.contains("headerSha256"))
        assertFalse(json.contains("fileName"))
        assertFalse(json.contains("fileSize"))
        assertFalse(json.contains("path"))
    }

    @Test
    fun absentLocalModeKeepsTheExactV1EvidenceAndArtifactBytes() {
        val runtimeEvidence = listOf(
            IdentityEvidenceRecord(
                id = "runtime-observed-fixture",
                type = IdentityEvidenceType.RUNTIME_OBSERVED,
                gameCode = "TEST",
                upstreamHeaderCrc16 = "1234",
                headerChecksum32 = "39FC5058",
                sourceRef = "runtime-validation:fixture",
            ),
        )
        val merged = TWiLightIdentityResolutionRunner.mergeEvidence(runtimeEvidence, null)
        assertSame(runtimeEvidence, merged)

        val candidates = TWiLightWidescreenCandidatesDocument(
            source = TWiLightWidescreenSource(),
            candidates = listOf(candidate("TEST", "1234")),
        )
        val index = identityIndex("TEST" to "39FC5058")
        val resolutions = TWiLightIdentityResolver.resolve(TWiLightIdentityResolver.join(candidates.candidates, index), merged)
        val artifact = IdentityArtifactReference("fixture.dat", "fixture.dat", 1, "0".repeat(64))
        val before = TWiLightIdentityResolver.encodeArtifacts(
            index, candidates, runtimeEvidence, resolutions, UsrcheatSource(), artifact, listOf(artifact),
        )
        val after = TWiLightIdentityResolver.encodeArtifacts(
            index, candidates, merged, resolutions, UsrcheatSource(), artifact, listOf(artifact),
        )

        assertArrayEquals(before.usrcheatIdentitiesBytes, after.usrcheatIdentitiesBytes)
        assertArrayEquals(before.resolutionBytes, after.resolutionBytes)
        assertArrayEquals(before.summaryBytes, after.summaryBytes)
    }

    private fun newRoot(): Path = temporaryFolder.newFolder().toPath()

    private fun writeRom(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    private fun syntheticHeader(gameCode: String, crc16: Int): ByteArray =
        ByteArray(LOCAL_ROM_HEADER_SIZE).also { header ->
            gameCode.toByteArray(US_ASCII).copyInto(header, NDS_GAME_CODE_OFFSET)
            ByteBuffer.wrap(header).order(LITTLE_ENDIAN).putShort(NDS_HEADER_CRC16_OFFSET, crc16.toShort())
        }

    private fun resolve(
        candidate: TWiLightWidescreenCandidate,
        index: UsrcheatIndex,
        evidence: List<IdentityEvidenceRecord>,
    ): TWiLightIdentityResolutionRecord =
        TWiLightIdentityResolver.resolve(TWiLightIdentityResolver.join(listOf(candidate), index), evidence).single()

    private fun candidate(gameCode: String, crc16: String) = TWiLightWidescreenCandidate(
        sourceRef = "fixture",
        sourcePath = "$gameCode-$crc16.bin",
        sourceFilename = "$gameCode-$crc16.bin",
        extension = ".bin",
        sizeBytes = 8,
        sourceSha256 = "0".repeat(64),
        upstreamTid = gameCode,
        upstreamHeaderCrc16 = crc16,
        upstreamHeaderCrc16Original = crc16,
        upstreamWildcard = crc16 == "FFFF",
        filenameStatus = "RECOGNIZED",
        decodeStatus = "DECODED",
        decodeDiagnostics = emptyList(),
        actionReplayLines = listOf("D2000000 00000000"),
        canonicalArBinarySha256 = "0".repeat(64),
        arValidationStatus = "SUPPORTED",
        usedOpcodes = listOf("D2"),
        arDiagnostics = emptyList(),
        instructionCount = 1,
        payloadBytes = 0,
        addressNormalizedStructureSha256 = "0".repeat(64),
        runtimeIdentityStatus = "UNRESOLVED",
        headerChecksum32 = null,
        classificationStatus = "UNCLASSIFIED",
        targetScreenStatus = "UNRESOLVED",
        conversionStatus = "NOT_EVALUATED",
        promotionStatus = "QUARANTINED",
        patchLicense = "NOASSERTION",
    )

    private fun identityIndex(vararg identities: Pair<String, String>): UsrcheatIndex {
        val records = identities.mapIndexed { index, (gameCode, checksum) ->
            UsrcheatIdentity(gameCode, checksum, 0x100L + index * 16)
        }
        val byGameCode = records.groupBy { it.gameCode }
        val max = byGameCode.values.maxOfOrNull { entries -> entries.map { it.headerChecksum32 }.distinct().size } ?: 0
        return UsrcheatIndex(
            identities = records,
            counts = UsrcheatIndexCounts(
                entryCount = records.size,
                distinctGameCodeCount = byGameCode.size,
                singleChecksumGameCodeCount = byGameCode.count { it.value.map { record -> record.headerChecksum32 }.distinct().size == 1 },
                multipleChecksumGameCodeCount = byGameCode.count { it.value.map { record -> record.headerChecksum32 }.distinct().size > 1 },
                maxChecksumsPerGameCode = max,
                maxChecksumGameCodes = byGameCode.filterValues {
                    it.map { record -> record.headerChecksum32 }.distinct().size == max
                }.keys.sorted(),
                exactDuplicateGroupCount = 0,
                duplicateEntriesBeyondFirst = 0,
                indexTerminatorOffset = 0x100L + records.size * 16,
            ),
        )
    }
}
