package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TWiLightIdentityResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun usrcheatParserReadsAndValidatesOnlyTheIndex() {
        val path = writeUsrcheat(
            "valid.dat",
            listOf(
                "TEST" to "00000001",
                "TEST" to "FFFFFFFF",
                "ONCE" to "12345678",
                "ONCE" to "12345678",
            ),
        )

        val index = UsrcheatDatParser.parse(path)

        assertEquals(4, index.counts.entryCount)
        assertEquals(2, index.counts.distinctGameCodeCount)
        assertEquals(1, index.counts.singleChecksumGameCodeCount)
        assertEquals(1, index.counts.multipleChecksumGameCodeCount)
        assertEquals(2, index.counts.maxChecksumsPerGameCode)
        assertEquals(listOf("TEST"), index.counts.maxChecksumGameCodes)
        assertEquals(1, index.counts.exactDuplicateGroupCount)
        assertEquals(1, index.counts.duplicateEntriesBeyondFirst)
        assertEquals(0x140L, index.counts.indexTerminatorOffset)
        assertEquals(listOf("00000001", "FFFFFFFF"), index.checksumsByGameCode.getValue("TEST"))
    }

    @Test
    fun usrcheatParserFailsClosedOnMalformedIndexStructures() {
        val valid = Files.readAllBytes(writeUsrcheat("base.dat", listOf("TEST" to "12345678")))
        val malformed = listOf(
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[0x100] = 't'.code.toByte() },
            valid.copyOf().apply { putInt(0x108, size + 1) },
            valid.copyOf().apply { putInt(0x10C, 1) },
            valid.copyOf().apply { putInt(0x114, 1) },
            valid.copyOf(0x110).apply { putInt(0x108, 0x100) },
        )

        malformed.forEachIndexed { index, bytes ->
            val path = temporaryFolder.newFile("invalid-$index.dat").toPath()
            Files.write(path, bytes)
            assertThrows(IllegalArgumentException::class.java) { UsrcheatDatParser.parse(path) }
        }
    }

    @Test
    fun joinDistinguishesNoOneAndMultipleCandidatesWithoutTreatingOneAsProof() {
        val candidates = listOf(
            candidate("NONE-1111.bin", "NONE", "1111"),
            candidate("ONE-2222.bin", "ONE1", "2222"),
            candidate("MULT-3333.bin", "MULT", "3333"),
        )
        val resolutions = resolve(
            candidates,
            identityIndex(
                "ONE1" to "11111111",
                "MULT" to "22222222",
                "MULT" to "33333333",
            ),
        ).associateBy { it.sourceCandidatePath }

        assertEquals(UsrcheatMatchStatus.NO_USRCHEAT_CANDIDATE, resolutions.getValue("NONE-1111.bin").matchStatus)
        assertEquals(UsrcheatMatchStatus.ONE_USRCHEAT_CANDIDATE, resolutions.getValue("ONE-2222.bin").matchStatus)
        assertEquals(IdentityEvidenceStatus.CANDIDATE_ONLY, resolutions.getValue("ONE-2222.bin").evidenceStatus)
        assertEquals(ResolvedRuntimeIdentityStatus.UNRESOLVED, resolutions.getValue("ONE-2222.bin").runtimeIdentityStatus)
        assertNull(resolutions.getValue("ONE-2222.bin").resolvedHeaderChecksum32)
        assertEquals(
            UsrcheatMatchStatus.MULTIPLE_USRCHEAT_CANDIDATES,
            resolutions.getValue("MULT-3333.bin").matchStatus,
        )
        assertEquals(ResolvedRuntimeIdentityStatus.AMBIGUOUS, resolutions.getValue("MULT-3333.bin").runtimeIdentityStatus)
    }

    @Test
    fun exactEvidenceIsTheOnlyPathToResolvedEvenWhenUsrcheatDoesNotContainIt() {
        val candidate = candidate("TEST-1234.bin", "TEST", "1234")
        val resolution = resolve(
            listOf(candidate),
            identityIndex("TEST" to "11111111"),
            listOf(evidence("TEST", "1234", "22222222")),
        ).single()

        assertEquals(IdentityEvidenceStatus.EXACT_EVIDENCE, resolution.evidenceStatus)
        assertEquals(ResolvedRuntimeIdentityStatus.RESOLVED, resolution.runtimeIdentityStatus)
        assertEquals("22222222", resolution.resolvedHeaderChecksum32)
        assertEquals(listOf("EXACT_EVIDENCE_NOT_PRESENT_IN_PINNED_USRCHEAT"), resolution.diagnostics)
    }

    @Test
    fun disagreeingExactEvidenceFailsClosedAsConflict() {
        val resolution = resolve(
            listOf(candidate("TEST-1234.bin", "TEST", "1234")),
            identityIndex("TEST" to "11111111"),
            listOf(
                evidence("TEST", "1234", "11111111", "first"),
                evidence("TEST", "1234", "22222222", "second"),
            ),
        ).single()

        assertEquals(IdentityEvidenceStatus.CONFLICT, resolution.evidenceStatus)
        assertEquals(ResolvedRuntimeIdentityStatus.CONFLICT, resolution.runtimeIdentityStatus)
        assertNull(resolution.resolvedHeaderChecksum32)
    }

    @Test
    fun wildcardCannotResolveFromASingleUsrcheatCandidate() {
        val resolution = resolve(
            listOf(candidate("WILD-FFFF.bin", "WILD", "FFFF")),
            identityIndex("WILD" to "12345678"),
        ).single()

        assertEquals(UsrcheatMatchStatus.UPSTREAM_WILDCARD, resolution.matchStatus)
        assertEquals(ResolvedRuntimeIdentityStatus.UNRESOLVED, resolution.runtimeIdentityStatus)
        assertNull(resolution.resolvedHeaderChecksum32)
    }

    @Test
    fun multipleTwilightVariantsExposeTheFullMatrixWithoutOrdinalMatching() {
        val candidates = listOf(
            candidate("MULT-0001.bin", "MULT", "0001"),
            candidate("MULT-0002.bin", "MULT", "0002"),
        )
        val resolutions = resolve(
            candidates,
            identityIndex("MULT" to "AAAAAAAA", "MULT" to "BBBBBBBB"),
        )

        assertTrue(resolutions.all { it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.AMBIGUOUS })
        assertTrue(resolutions.all { it.twilightVariantCrc16s == listOf("0001", "0002") })
        assertTrue(resolutions.all { it.usrcheatCandidateChecksums32 == listOf("AAAAAAAA", "BBBBBBBB") })
        assertTrue(resolutions.all { it.resolvedHeaderChecksum32 == null })
    }

    @Test
    fun pokemonRuntimeEvidenceResolvesTheKnownSentinel() {
        val resolution = resolve(
            listOf(candidate("IRAF-BC1D.bin", "IRAF", "BC1D")),
            identityIndex("IRAF" to "031EF208"),
            listOf(evidence("IRAF", "BC1D", "031EF208", "pokemon-white-fra-v1-0")),
        ).single()

        assertEquals(ResolvedRuntimeIdentityStatus.RESOLVED, resolution.runtimeIdentityStatus)
        assertEquals("031EF208", resolution.resolvedHeaderChecksum32)
        assertEquals(listOf("USRCHEAT_CORROBORATES_EXACT_EVIDENCE"), resolution.diagnostics)
    }

    @Test
    fun canonicalManifestEvidenceAndEncodedArtifactsAreDeterministic() {
        val evidence = canonicalEvidence()
        val pokemonEvidence = evidence.filter { it.gameCode == "IRAF" && it.upstreamHeaderCrc16 == "BC1D" }
        val superMarioEvidence = evidence.filter { it.gameCode == "ASMP" && it.upstreamHeaderCrc16 == "477C" }
        assertEquals(22, evidence.size)
        assertEquals(21, evidence.count { it.type == IdentityEvidenceType.LOCAL_ROM_HEADER })
        assertEquals(1, evidence.count { it.type == IdentityEvidenceType.RUNTIME_OBSERVED })
        assertEquals(2, pokemonEvidence.size)
        assertEquals(setOf("031EF208"), pokemonEvidence.map { it.headerChecksum32 }.toSet())
        assertEquals(listOf(IdentityEvidenceType.LOCAL_ROM_HEADER), superMarioEvidence.map { it.type })
        assertEquals(listOf("D3D9F14A"), superMarioEvidence.map { it.headerChecksum32 })

        val candidatesDocument = TWiLightWidescreenCandidatesDocument(
            source = TWiLightWidescreenSource(),
            candidates = listOf(candidate("IRAF-BC1D.bin", "IRAF", "BC1D")),
        )
        val index = identityIndex("IRAF" to "031EF208")
        val resolutions = resolve(candidatesDocument.candidates, index, pokemonEvidence)
        val artifact = IdentityArtifactReference("usrcheat.dat", USRCHEAT_ARTIFACT_PATH, 123, "0".repeat(64))
        val inputs = listOf(artifact)
        val first = TWiLightIdentityResolver.encodeArtifacts(
            index,
            candidatesDocument,
            pokemonEvidence,
            resolutions,
            UsrcheatSource(),
            artifact,
            inputs,
        )
        val second = TWiLightIdentityResolver.encodeArtifacts(
            index,
            candidatesDocument,
            pokemonEvidence,
            resolutions,
            UsrcheatSource(),
            artifact,
            inputs,
        )

        assertArrayEquals(first.usrcheatIdentitiesBytes, second.usrcheatIdentitiesBytes)
        assertArrayEquals(first.resolutionBytes, second.resolutionBytes)
        assertArrayEquals(first.summaryBytes, second.summaryBytes)
    }

    @Test
    fun canonicalManifestResolvesTwentyOneMappingsWithoutLocalEvidence() {
        val evidence = canonicalEvidence()
        val mappings = evidence.distinctBy { it.gameCode to it.upstreamHeaderCrc16 }
        val partiallySupported = setOf("AY9P", "IPGF", "IPKF", "YV5P", "YVIP")
        val candidates = mappings.map { record ->
            candidate(
                "${record.gameCode}-${record.upstreamHeaderCrc16}.bin",
                record.gameCode,
                record.upstreamHeaderCrc16,
                if (record.gameCode in partiallySupported) "PARTIALLY_SUPPORTED" else "SUPPORTED",
            )
        }
        val index = identityIndex(*mappings.map { it.gameCode to it.headerChecksum32 }.toTypedArray())
        val resolutions = resolve(candidates, index, evidence)
        val resolutionsByPath = resolutions.associateBy { it.sourceCandidatePath }

        assertEquals(21, mappings.size)
        assertTrue(resolutions.all { it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED })
        assertEquals(
            16,
            candidates.count {
                it.arValidationStatus == "SUPPORTED" &&
                    resolutionsByPath.getValue(it.sourcePath).runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED
            },
        )
    }

    private fun resolve(
        candidates: List<TWiLightWidescreenCandidate>,
        index: UsrcheatIndex,
        evidence: List<IdentityEvidenceRecord> = emptyList(),
    ): List<TWiLightIdentityResolutionRecord> {
        return TWiLightIdentityResolver.resolve(TWiLightIdentityResolver.join(candidates, index), evidence)
    }

    private fun evidence(
        gameCode: String,
        crc16: String,
        checksum32: String,
        source: String = "fixture",
    ) = IdentityEvidenceRecord(
        id = "runtime-observed-$source",
        type = IdentityEvidenceType.RUNTIME_OBSERVED,
        gameCode = gameCode,
        upstreamHeaderCrc16 = crc16,
        headerChecksum32 = checksum32,
        sourceRef = "runtime-validation:$source",
    )

    private fun candidate(
        filename: String,
        gameCode: String,
        crc16: String,
        validationStatus: String = "SUPPORTED",
    ) =
        TWiLightWidescreenCandidate(
            sourceRef = "fixture",
            sourcePath = filename,
            sourceFilename = filename,
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
            arValidationStatus = validationStatus,
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
            .mapValues { (_, entries) -> entries.map { it.headerChecksum32 }.distinct() }
        val max = byGameCode.values.maxOfOrNull { it.size } ?: 0
        val duplicates = records.groupingBy { it.gameCode to it.headerChecksum32 }
            .eachCount()
            .filterValues { it > 1 }
        return UsrcheatIndex(
            identities = records.sortedWith(compareBy({ it.gameCode }, { it.headerChecksum32 }, { it.recordOffset })),
            counts = UsrcheatIndexCounts(
                entryCount = records.size,
                distinctGameCodeCount = byGameCode.size,
                singleChecksumGameCodeCount = byGameCode.count { it.value.size == 1 },
                multipleChecksumGameCodeCount = byGameCode.count { it.value.size > 1 },
                maxChecksumsPerGameCode = max,
                maxChecksumGameCodes = byGameCode.filterValues { it.size == max }.keys.sorted(),
                exactDuplicateGroupCount = duplicates.size,
                duplicateEntriesBeyondFirst = duplicates.values.sumOf { it - 1 },
                indexTerminatorOffset = 0x100L + records.size * 16,
            ),
        )
    }

    private fun writeUsrcheat(filename: String, identities: List<Pair<String, String>>): Path {
        val bytes = ByteArray(0x400)
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        buffer.put("R4 CheatCode".toByteArray(US_ASCII))
        buffer.put(byteArrayOf(0, 1, 0, 0))
        identities.forEachIndexed { index, (gameCode, checksum) ->
            buffer.position(0x100 + index * 16)
            buffer.put(gameCode.toByteArray(US_ASCII))
            buffer.putInt(checksum.toUInt(16).toInt())
            buffer.putInt(0x300 + index * 4)
            buffer.putInt(0)
        }
        val path = temporaryFolder.newFile(filename).toPath()
        return Files.write(path, bytes)
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        ByteBuffer.wrap(this).order(LITTLE_ENDIAN).putInt(offset, value)
    }

    private fun repositoryFile(relativeToApp: String): Path {
        return listOf(Path.of(relativeToApp), Path.of("app").resolve(relativeToApp))
            .firstOrNull(Files::isRegularFile)
            ?: error("Cannot locate repository file $relativeToApp")
    }

    private fun canonicalEvidence(): List<IdentityEvidenceRecord> {
        val evidencePath = repositoryFile("widescreen/widescreen_identity_evidence.json")
        return WidescreenIdentityEvidenceValidator.parseAndValidate(
            String(Files.readAllBytes(evidencePath), UTF_8),
        ).records
    }
}
