package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val USRCHEAT_OFFICIAL_REPOSITORY =
    "https://bitbucket.org/DeadSkullzJr/nds-i-cheat-databases"
const val USRCHEAT_DISTRIBUTION_REPOSITORY =
    "https://github.com/szTheory/NDS-Cheat-Databases.git"
const val USRCHEAT_DISTRIBUTION_COMMIT = "0173af14d33e2c045e7c5e30c71d369624a33020"
const val USRCHEAT_SNAPSHOT_LABEL = "DeadSkullzJr changelog 2021-12-25"
const val USRCHEAT_ARTIFACT_PATH = "Cheat Databases/usrcheat.dat"
const val USRCHEAT_ARTIFACT_SIZE = 42_983_604L
const val USRCHEAT_ARTIFACT_SHA256 =
    "28ad3272d3578ba2f493e78990a3d6defe9dfebe37d9f53501679be9498eafae"
const val USRCHEAT_REPOSITORY_LICENSE = "AGPL-3.0"
const val USRCHEAT_ARTIFACT_LICENSE = "NOASSERTION"

private const val PINNED_TWILIGHT_CANDIDATES_SHA256 =
    "ccb1d0c2177df73653224716017364c1d9fbbf3d667425fa799fe70f7af5d000"
private const val TWILIGHT_UPSTREAM_IDENTITY_NAMESPACE = "TWILIGHT_WIDESCREEN_FILENAME_V1"
internal val GAME_CODE_PATTERN = Regex("^[A-Z0-9]{4}$")
private val CHECKSUM16_PATTERN = Regex("^[0-9A-F]{4}$")
private val CHECKSUM32_PATTERN = Regex("^[0-9A-F]{8}$")

@Serializable
data class UsrcheatSource(
    val project: String = "DeadSkullzJr/NDS(i) Cheat Databases",
    val officialRepository: String = USRCHEAT_OFFICIAL_REPOSITORY,
    val distributionRepository: String = USRCHEAT_DISTRIBUTION_REPOSITORY,
    val distributionRole: String = "PINNED_SECONDARY_MIRROR",
    val version: String = USRCHEAT_SNAPSHOT_LABEL,
    val commit: String = USRCHEAT_DISTRIBUTION_COMMIT,
    val artifactPath: String = USRCHEAT_ARTIFACT_PATH,
    val repositoryLicense: String = USRCHEAT_REPOSITORY_LICENSE,
    val artifactLicense: String = USRCHEAT_ARTIFACT_LICENSE,
)

@Serializable
data class IdentityArtifactReference(
    val filename: String,
    val sourcePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class UsrcheatIdentity(
    val gameCode: String,
    val headerChecksum32: String,
    val recordOffset: Long,
)

@Serializable
data class UsrcheatIndexCounts(
    val entryCount: Int,
    val distinctGameCodeCount: Int,
    val singleChecksumGameCodeCount: Int,
    val multipleChecksumGameCodeCount: Int,
    val maxChecksumsPerGameCode: Int,
    val maxChecksumGameCodes: List<String>,
    val exactDuplicateGroupCount: Int,
    val duplicateEntriesBeyondFirst: Int,
    val indexTerminatorOffset: Long,
)

data class UsrcheatIndex(
    val identities: List<UsrcheatIdentity>,
    val counts: UsrcheatIndexCounts,
) {
    val checksumsByGameCode: Map<String, List<String>> = identities
        .groupBy { it.gameCode }
        .mapValues { (_, entries) -> entries.map { it.headerChecksum32 }.distinct().sorted() }
        .toSortedMap()
}

@Serializable
data class UsrcheatIdentitiesDocument(
    val schemaVersion: Int = 1,
    val source: UsrcheatSource,
    val artifact: IdentityArtifactReference,
    val format: String = "R4_USRCHEAT_INDEX_V1",
    val checksumAlgorithm: String = "CRC32_JAMCRC_FIRST_512_BYTES",
    val counts: UsrcheatIndexCounts,
    val identities: List<UsrcheatIdentity>,
)

@Serializable
enum class UsrcheatMatchStatus {
    NO_USRCHEAT_CANDIDATE,
    ONE_USRCHEAT_CANDIDATE,
    MULTIPLE_USRCHEAT_CANDIDATES,
    UPSTREAM_WILDCARD,
}

@Serializable
enum class IdentityEvidenceType {
    RUNTIME_OBSERVED,
    PUBLIC_DUAL_IDENTITY_SOURCE,
    LOCAL_ROM_HEADER,
    MANUALLY_RATIFIED,
}

@Serializable
enum class IdentityEvidenceStatus {
    NO_EVIDENCE,
    CANDIDATE_ONLY,
    AMBIGUOUS,
    EXACT_EVIDENCE,
    CONFLICT,
}

@Serializable
enum class ResolvedRuntimeIdentityStatus {
    UNRESOLVED,
    AMBIGUOUS,
    RESOLVED,
    CONFLICT,
}

@Serializable
data class IdentityEvidenceRecord(
    val schemaVersion: Int = 1,
    val type: IdentityEvidenceType,
    val sourceRef: String,
    val gameCode: String,
    val upstreamHeaderCrc16: String,
    val headerChecksum32: String,
)

data class TWiLightIdentityJoin(
    val candidate: TWiLightWidescreenCandidate,
    val usrcheatCandidateChecksums32: List<String>,
    val twilightVariantCrc16s: List<String>,
    val matchStatus: UsrcheatMatchStatus,
)

@Serializable
data class TWiLightIdentityResolutionRecord(
    val sourceCandidatePath: String,
    val upstreamTid: String?,
    val upstreamHeaderCrc16: String?,
    val upstreamWildcard: Boolean,
    val usrcheatCandidateChecksums32: List<String>,
    val candidateCount: Int,
    val twilightVariantCrc16s: List<String>,
    val matchStatus: UsrcheatMatchStatus,
    val evidence: List<IdentityEvidenceRecord>,
    val evidenceStatus: IdentityEvidenceStatus,
    val resolvedHeaderChecksum32: String?,
    val runtimeIdentityStatus: ResolvedRuntimeIdentityStatus,
    val diagnostics: List<String>,
)

@Serializable
data class TWiLightIdentityResolutionDocument(
    val schemaVersion: Int = 1,
    val twilightSource: TWiLightWidescreenSource,
    val usrcheatSource: UsrcheatSource,
    val inputs: List<IdentityArtifactReference>,
    val resolutions: List<TWiLightIdentityResolutionRecord>,
)

@Serializable
data class ArIdentityCount(
    val arValidationStatus: String,
    val runtimeIdentityStatus: ResolvedRuntimeIdentityStatus,
    val count: Int,
)

@Serializable
data class TWiLightIdentitySummary(
    val schemaVersion: Int = 1,
    val twilightSource: TWiLightWidescreenSource,
    val usrcheatSource: UsrcheatSource,
    val inputs: List<IdentityArtifactReference>,
    val outputs: List<IdentityArtifactReference>,
    val usrcheatCounts: UsrcheatIndexCounts,
    val twilightCandidateCount: Int,
    val evidenceRecordCount: Int,
    val matchStatusDistribution: List<TWiLightValueCount>,
    val evidenceStatusDistribution: List<TWiLightValueCount>,
    val runtimeIdentityStatusDistribution: List<TWiLightValueCount>,
    val singleCandidateButNotProvenCount: Int,
    val arIdentityDistribution: List<ArIdentityCount>,
    val supportedResolvedCount: Int,
    val resolvedCandidatePaths: List<String>,
    val conflictCandidatePaths: List<String>,
)

data class TWiLightIdentityArtifacts(
    val usrcheatIdentitiesBytes: ByteArray,
    val resolutionBytes: ByteArray,
    val summaryBytes: ByteArray,
    val resolutions: List<TWiLightIdentityResolutionRecord>,
)

data class TWiLightIdentityRunMetrics(
    val usrcheatParsingMillis: Double,
    val joinMillis: Double,
    val evidenceResolutionMillis: Double,
    val evidenceMergeMillis: Double,
    val resolverTotalMillis: Double,
    val totalMillis: Double,
)

data class LocalRomResolutionStats(
    val localEvidenceUniqueCount: Int,
    val localEvidenceMatchingTWiLightCount: Int,
    val localEvidenceWithoutTWiLightCandidateCount: Int,
    val localEvidenceAbsentFromUsrcheatCount: Int,
    val newlyResolvedCandidateCount: Int,
    val newlyResolvedSupportedCount: Int,
    val conflictCandidateCount: Int,
)

data class LocalRomIdentityRunResult(
    val evidencePath: Path,
    val evidenceSizeBytes: Long,
    val evidenceSha256: String,
    val scan: LocalRomScanResult,
    val resolutionStats: LocalRomResolutionStats,
)

data class TWiLightIdentityRunResult(
    val usrcheatIdentitiesPath: Path,
    val resolutionPath: Path,
    val summaryPath: Path,
    val usrcheatIdentitiesSizeBytes: Long,
    val usrcheatIdentitiesSha256: String,
    val resolutionSizeBytes: Long,
    val resolutionSha256: String,
    val summarySizeBytes: Long,
    val summarySha256: String,
    val summary: TWiLightIdentitySummary,
    val metrics: TWiLightIdentityRunMetrics,
    val localRom: LocalRomIdentityRunResult?,
)

object UsrcheatDatParser {
    private val expectedHeader = "R4 CheatCode".toByteArray(US_ASCII) + byteArrayOf(0, 1, 0, 0)

    fun parse(path: Path): UsrcheatIndex {
        FileChannel.open(path, READ).use { channel ->
            val fileSize = channel.size()
            require(fileSize >= 0x110) { "usrcheat.dat is too small: $fileSize bytes" }
            require(channel.readExactly(0, 16).contentEquals(expectedHeader)) {
                "Invalid usrcheat.dat magic/version header"
            }

            val identities = mutableListOf<UsrcheatIdentity>()
            var recordOffset = 0x100L
            var terminatorOffset: Long? = null
            while (recordOffset + 16 <= fileSize) {
                val record = ByteBuffer.wrap(channel.readExactly(recordOffset, 16)).order(LITTLE_ENDIAN)
                val gameCodeBytes = ByteArray(4).also(record::get)
                val checksum = record.int.toUInt()
                val cheatDataOffset = record.int.toUInt().toLong()
                val reserved = record.int.toUInt()
                if (gameCodeBytes.all { it == 0.toByte() }) {
                    require(checksum == 0u && cheatDataOffset == 0L && reserved == 0u) {
                        "Malformed usrcheat index terminator at 0x${recordOffset.toString(16)}"
                    }
                    terminatorOffset = recordOffset
                    break
                }

                val gameCode = String(gameCodeBytes, US_ASCII)
                require(GAME_CODE_PATTERN.matches(gameCode)) {
                    "Invalid usrcheat game code at 0x${recordOffset.toString(16)}: $gameCode"
                }
                require(cheatDataOffset in 0x100L until fileSize) {
                    "Invalid usrcheat data offset at 0x${recordOffset.toString(16)}: " +
                        "0x${cheatDataOffset.toString(16)}"
                }
                require(reserved == 0u) {
                    "Non-zero reserved usrcheat index word at 0x${recordOffset.toString(16)}"
                }
                identities += UsrcheatIdentity(
                    gameCode = gameCode,
                    headerChecksum32 = checksum.toHex8(),
                    recordOffset = recordOffset,
                )
                recordOffset += 16
            }
            val finalTerminatorOffset = requireNotNull(terminatorOffset) {
                "Missing usrcheat index terminator"
            }

            val sortedIdentities = identities.sortedWith(
                compareBy<UsrcheatIdentity>({ it.gameCode }, { it.headerChecksum32 }, { it.recordOffset }),
            )
            val checksumsByGameCode = sortedIdentities
                .groupBy { it.gameCode }
                .mapValues { (_, entries) -> entries.map { it.headerChecksum32 }.distinct() }
            val maxChecksums = checksumsByGameCode.values.maxOfOrNull { it.size } ?: 0
            val duplicates = sortedIdentities.groupingBy { it.gameCode to it.headerChecksum32 }
                .eachCount()
                .filterValues { it > 1 }
            return UsrcheatIndex(
                identities = sortedIdentities,
                counts = UsrcheatIndexCounts(
                    entryCount = sortedIdentities.size,
                    distinctGameCodeCount = checksumsByGameCode.size,
                    singleChecksumGameCodeCount = checksumsByGameCode.count { it.value.size == 1 },
                    multipleChecksumGameCodeCount = checksumsByGameCode.count { it.value.size > 1 },
                    maxChecksumsPerGameCode = maxChecksums,
                    maxChecksumGameCodes = checksumsByGameCode
                        .filterValues { it.size == maxChecksums }
                        .keys
                        .sorted(),
                    exactDuplicateGroupCount = duplicates.size,
                    duplicateEntriesBeyondFirst = duplicates.values.sumOf { it - 1 },
                    indexTerminatorOffset = finalTerminatorOffset,
                ),
            )
        }
    }

    private fun FileChannel.readExactly(offset: Long, size: Int): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        position(offset)
        while (buffer.hasRemaining()) {
            require(read(buffer) >= 0) { "Unexpected end of usrcheat.dat at offset $offset" }
        }
        return buffer.array()
    }
}

object TWiLightIdentityResolver {
    fun join(
        candidates: List<TWiLightWidescreenCandidate>,
        usrcheatIndex: UsrcheatIndex,
    ): List<TWiLightIdentityJoin> {
        val variantsByTid = candidates
            .filter { it.upstreamTid != null && it.upstreamHeaderCrc16 != null }
            .groupBy { requireNotNull(it.upstreamTid) }
            .mapValues { (_, variants) -> variants.map { requireNotNull(it.upstreamHeaderCrc16) }.distinct().sorted() }
        return candidates.sortedBy { it.sourcePath }.map { candidate ->
            val checksums = candidate.upstreamTid
                ?.let { usrcheatIndex.checksumsByGameCode[it] }
                .orEmpty()
            TWiLightIdentityJoin(
                candidate = candidate,
                usrcheatCandidateChecksums32 = checksums,
                twilightVariantCrc16s = candidate.upstreamTid?.let(variantsByTid::get).orEmpty(),
                matchStatus = when {
                    candidate.upstreamWildcard -> UsrcheatMatchStatus.UPSTREAM_WILDCARD
                    checksums.isEmpty() -> UsrcheatMatchStatus.NO_USRCHEAT_CANDIDATE
                    checksums.size == 1 -> UsrcheatMatchStatus.ONE_USRCHEAT_CANDIDATE
                    else -> UsrcheatMatchStatus.MULTIPLE_USRCHEAT_CANDIDATES
                },
            )
        }
    }

    fun resolve(
        joinedCandidates: List<TWiLightIdentityJoin>,
        evidenceRecords: List<IdentityEvidenceRecord>,
    ): List<TWiLightIdentityResolutionRecord> {
        evidenceRecords.forEach(::validateEvidence)
        val evidenceByIdentity = evidenceRecords
            .sortedWith(compareBy({ it.gameCode }, { it.upstreamHeaderCrc16 }, { it.type.name }, { it.sourceRef }, { it.headerChecksum32 }))
            .groupBy { it.gameCode to it.upstreamHeaderCrc16 }
        return joinedCandidates.map { joined ->
            val candidate = joined.candidate
            val evidence = if (candidate.upstreamWildcard) {
                emptyList()
            } else {
                evidenceByIdentity[candidate.upstreamTid to candidate.upstreamHeaderCrc16].orEmpty()
            }
            val evidenceChecksums = evidence.map { it.headerChecksum32 }.distinct()
            val diagnostics = mutableListOf<String>()
            val evidenceStatus: IdentityEvidenceStatus
            val runtimeStatus: ResolvedRuntimeIdentityStatus
            val resolvedChecksum: String?

            when {
                candidate.upstreamWildcard -> {
                    evidenceStatus = IdentityEvidenceStatus.NO_EVIDENCE
                    runtimeStatus = ResolvedRuntimeIdentityStatus.UNRESOLVED
                    resolvedChecksum = null
                    diagnostics += "UPSTREAM_WILDCARD_CANNOT_IDENTIFY_A_ROM_REVISION"
                }
                evidenceChecksums.size > 1 -> {
                    evidenceStatus = IdentityEvidenceStatus.CONFLICT
                    runtimeStatus = ResolvedRuntimeIdentityStatus.CONFLICT
                    resolvedChecksum = null
                    diagnostics += "EXACT_EVIDENCE_RECORDS_DISAGREE"
                }
                evidenceChecksums.size == 1 -> {
                    evidenceStatus = IdentityEvidenceStatus.EXACT_EVIDENCE
                    runtimeStatus = ResolvedRuntimeIdentityStatus.RESOLVED
                    resolvedChecksum = evidenceChecksums.single()
                    diagnostics += if (resolvedChecksum in joined.usrcheatCandidateChecksums32) {
                        "USRCHEAT_CORROBORATES_EXACT_EVIDENCE"
                    } else {
                        "EXACT_EVIDENCE_NOT_PRESENT_IN_PINNED_USRCHEAT"
                    }
                }
                joined.usrcheatCandidateChecksums32.size > 1 || joined.twilightVariantCrc16s.size > 1 -> {
                    evidenceStatus = IdentityEvidenceStatus.AMBIGUOUS
                    runtimeStatus = ResolvedRuntimeIdentityStatus.AMBIGUOUS
                    resolvedChecksum = null
                    diagnostics += "MULTIPLE_IDENTITIES_REQUIRE_EXACT_EVIDENCE"
                }
                joined.usrcheatCandidateChecksums32.size == 1 -> {
                    evidenceStatus = IdentityEvidenceStatus.CANDIDATE_ONLY
                    runtimeStatus = ResolvedRuntimeIdentityStatus.UNRESOLVED
                    resolvedChecksum = null
                    diagnostics += "SINGLE_USRCHEAT_CANDIDATE_IS_NOT_REVISION_PROOF"
                }
                else -> {
                    evidenceStatus = IdentityEvidenceStatus.NO_EVIDENCE
                    runtimeStatus = ResolvedRuntimeIdentityStatus.UNRESOLVED
                    resolvedChecksum = null
                    diagnostics += "NO_USRCHEAT_IDENTITY_FOR_GAME_CODE"
                }
            }

            TWiLightIdentityResolutionRecord(
                sourceCandidatePath = candidate.sourcePath,
                upstreamTid = candidate.upstreamTid,
                upstreamHeaderCrc16 = candidate.upstreamHeaderCrc16,
                upstreamWildcard = candidate.upstreamWildcard,
                usrcheatCandidateChecksums32 = joined.usrcheatCandidateChecksums32,
                candidateCount = joined.usrcheatCandidateChecksums32.size,
                twilightVariantCrc16s = joined.twilightVariantCrc16s,
                matchStatus = joined.matchStatus,
                evidence = evidence,
                evidenceStatus = evidenceStatus,
                resolvedHeaderChecksum32 = resolvedChecksum,
                runtimeIdentityStatus = runtimeStatus,
                diagnostics = diagnostics,
            )
        }
    }

    fun runtimeObservedEvidence(sourcesJson: String, profilesJson: String): List<IdentityEvidenceRecord> {
        val manifests = WidescreenManifestValidator.parseAndValidate(sourcesJson, profilesJson)
        return manifests.profiles.profiles.mapNotNull { profile ->
            val upstream = profile.upstreamIdentity ?: return@mapNotNull null
            if (
                upstream.namespace != TWILIGHT_UPSTREAM_IDENTITY_NAMESPACE ||
                profile.validation.identity != IdentityValidation.RESOLVED ||
                profile.validation.trust != TrustValidation.RUNTIME_VALIDATED
            ) {
                return@mapNotNull null
            }
            IdentityEvidenceRecord(
                type = IdentityEvidenceType.RUNTIME_OBSERVED,
                sourceRef = "widescreen_profiles.json#${profile.id}",
                gameCode = upstream.gameCode,
                upstreamHeaderCrc16 = upstream.headerChecksum16,
                headerChecksum32 = profile.rom.headerChecksum32,
            )
        }.sortedWith(compareBy({ it.gameCode }, { it.upstreamHeaderCrc16 }, { it.sourceRef }))
    }

    fun encodeArtifacts(
        usrcheatIndex: UsrcheatIndex,
        candidatesDocument: TWiLightWidescreenCandidatesDocument,
        evidenceRecords: List<IdentityEvidenceRecord>,
        resolutions: List<TWiLightIdentityResolutionRecord>,
        usrcheatSource: UsrcheatSource,
        usrcheatArtifact: IdentityArtifactReference,
        inputs: List<IdentityArtifactReference>,
    ): TWiLightIdentityArtifacts {
        val sortedInputs = inputs.sortedBy { it.sourcePath }
        val identitiesDocument = UsrcheatIdentitiesDocument(
            source = usrcheatSource,
            artifact = usrcheatArtifact,
            counts = usrcheatIndex.counts,
            identities = usrcheatIndex.identities,
        )
        val resolutionDocument = TWiLightIdentityResolutionDocument(
            twilightSource = candidatesDocument.source,
            usrcheatSource = usrcheatSource,
            inputs = sortedInputs,
            resolutions = resolutions,
        )
        val identitiesBytes = canonicalJson(identitiesDocument)
        val resolutionBytes = canonicalJson(resolutionDocument)
        val outputReferences = listOf(
            artifactReference("usrcheat-identities.json", identitiesBytes),
            artifactReference("twilight-identity-resolution.json", resolutionBytes),
        )
        val summary = buildSummary(
            candidatesDocument = candidatesDocument,
            usrcheatSource = usrcheatSource,
            inputs = sortedInputs,
            outputs = outputReferences,
            usrcheatIndex = usrcheatIndex,
            evidenceRecords = evidenceRecords,
            resolutions = resolutions,
        )
        return TWiLightIdentityArtifacts(
            usrcheatIdentitiesBytes = identitiesBytes,
            resolutionBytes = resolutionBytes,
            summaryBytes = canonicalJson(summary),
            resolutions = resolutions,
        )
    }

    private fun buildSummary(
        candidatesDocument: TWiLightWidescreenCandidatesDocument,
        usrcheatSource: UsrcheatSource,
        inputs: List<IdentityArtifactReference>,
        outputs: List<IdentityArtifactReference>,
        usrcheatIndex: UsrcheatIndex,
        evidenceRecords: List<IdentityEvidenceRecord>,
        resolutions: List<TWiLightIdentityResolutionRecord>,
    ): TWiLightIdentitySummary {
        val candidatesByPath = candidatesDocument.candidates.associateBy { it.sourcePath }
        val arOrder = listOf("SUPPORTED", "PARTIALLY_SUPPORTED", "UNSUPPORTED", "INVALID", "NOT_VALIDATED")
        val identityOrder = ResolvedRuntimeIdentityStatus.entries.withIndex().associate { it.value to it.index }
        val arIdentity = resolutions.groupingBy { resolution ->
            val ar = candidatesByPath.getValue(resolution.sourceCandidatePath).arValidationStatus ?: "NOT_VALIDATED"
            ar to resolution.runtimeIdentityStatus
        }.eachCount().entries
            .sortedWith(compareBy({ arOrder.indexOf(it.key.first) }, { identityOrder.getValue(it.key.second) }))
            .map { ArIdentityCount(it.key.first, it.key.second, it.value) }
        return TWiLightIdentitySummary(
            twilightSource = candidatesDocument.source,
            usrcheatSource = usrcheatSource,
            inputs = inputs,
            outputs = outputs,
            usrcheatCounts = usrcheatIndex.counts,
            twilightCandidateCount = resolutions.size,
            evidenceRecordCount = evidenceRecords.size,
            matchStatusDistribution = enumCounts(resolutions.map { it.matchStatus }),
            evidenceStatusDistribution = enumCounts(resolutions.map { it.evidenceStatus }),
            runtimeIdentityStatusDistribution = enumCounts(resolutions.map { it.runtimeIdentityStatus }),
            singleCandidateButNotProvenCount = resolutions.count {
                it.matchStatus == UsrcheatMatchStatus.ONE_USRCHEAT_CANDIDATE &&
                    it.runtimeIdentityStatus != ResolvedRuntimeIdentityStatus.RESOLVED
            },
            arIdentityDistribution = arIdentity,
            supportedResolvedCount = arIdentity.singleOrNull {
                it.arValidationStatus == "SUPPORTED" &&
                    it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED
            }?.count ?: 0,
            resolvedCandidatePaths = resolutions
                .filter { it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED }
                .map { it.sourceCandidatePath },
            conflictCandidatePaths = resolutions
                .filter { it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.CONFLICT }
                .map { it.sourceCandidatePath },
        )
    }

    private fun validateEvidence(evidence: IdentityEvidenceRecord) {
        require(evidence.schemaVersion == 1) { "Unsupported identity evidence schema" }
        require(GAME_CODE_PATTERN.matches(evidence.gameCode)) { "Invalid evidence gameCode" }
        require(CHECKSUM16_PATTERN.matches(evidence.upstreamHeaderCrc16)) { "Invalid evidence CRC16" }
        require(evidence.upstreamHeaderCrc16 != "FFFF") { "Wildcard evidence cannot identify a revision" }
        require(CHECKSUM32_PATTERN.matches(evidence.headerChecksum32)) { "Invalid evidence checksum32" }
        require(evidence.sourceRef.isNotBlank()) { "Evidence sourceRef must not be blank" }
    }

    private inline fun <reified T : Enum<T>> enumCounts(values: List<T>): List<TWiLightValueCount> {
        val counts = values.groupingBy { it }.eachCount()
        return enumValues<T>().map { TWiLightValueCount(it.name, counts[it] ?: 0) }
    }

    private fun artifactReference(filename: String, bytes: ByteArray) = IdentityArtifactReference(
        filename = filename,
        sourcePath = "build/widescreen-identity-resolution/$filename",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )
}

object TWiLightIdentityResolutionRunner {
    const val USRCHEAT_IDENTITIES_FILENAME = "usrcheat-identities.json"
    const val RESOLUTION_FILENAME = "twilight-identity-resolution.json"
    const val SUMMARY_FILENAME = "twilight-identity-summary.json"

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun mergeEvidence(
        runtimeEvidence: List<IdentityEvidenceRecord>,
        localScan: LocalRomScanResult?,
    ): List<IdentityEvidenceRecord> = if (localScan == null) {
        runtimeEvidence
    } else {
        runtimeEvidence + localScan.evidenceRecords
    }

    fun run(
        usrcheatPath: Path,
        twilightCandidatesPath: Path,
        widescreenSourcesPath: Path,
        widescreenProfilesPath: Path,
        outputDirectory: Path,
        localRomDirectory: Path? = null,
    ): TWiLightIdentityRunResult {
        val totalStart = System.nanoTime()
        require(Files.isRegularFile(usrcheatPath)) { "Missing pinned usrcheat artifact: $usrcheatPath" }
        require(Files.size(usrcheatPath) == USRCHEAT_ARTIFACT_SIZE) { "Pinned usrcheat size mismatch" }
        val usrcheatSha = sha256(usrcheatPath)
        require(usrcheatSha == USRCHEAT_ARTIFACT_SHA256) { "Pinned usrcheat SHA-256 mismatch: $usrcheatSha" }

        val candidatesBytes = Files.readAllBytes(twilightCandidatesPath)
        val candidatesSha = sha256(candidatesBytes)
        require(candidatesSha == PINNED_TWILIGHT_CANDIDATES_SHA256) {
            "Pinned TWiLight candidates SHA-256 mismatch: $candidatesSha"
        }
        val candidatesDocument = json.decodeFromString<TWiLightWidescreenCandidatesDocument>(
            String(candidatesBytes, UTF_8),
        )
        require(candidatesDocument.source.commit == TWILIGHT_WIDESCREEN_COMMIT) {
            "Unexpected TWiLight candidates source commit"
        }

        val sourcesBytes = Files.readAllBytes(widescreenSourcesPath)
        val profilesBytes = Files.readAllBytes(widescreenProfilesPath)
        val runtimeEvidence = TWiLightIdentityResolver.runtimeObservedEvidence(
            String(sourcesBytes, UTF_8),
            String(profilesBytes, UTF_8),
        )
        val localScan = localRomDirectory?.let(LocalRomIdentityScanner::scan)
        val mergeStart = System.nanoTime()
        val evidence = mergeEvidence(runtimeEvidence, localScan)
        val mergeNanos = System.nanoTime() - mergeStart

        val parseStart = System.nanoTime()
        val usrcheatIndex = UsrcheatDatParser.parse(usrcheatPath)
        val parseNanos = System.nanoTime() - parseStart
        val joinStart = System.nanoTime()
        val joined = TWiLightIdentityResolver.join(candidatesDocument.candidates, usrcheatIndex)
        val joinNanos = System.nanoTime() - joinStart
        val resolverStart = System.nanoTime()
        val baselineResolutions = localScan?.let {
            TWiLightIdentityResolver.resolve(joined, runtimeEvidence)
        }
        val evidenceStart = System.nanoTime()
        val resolutions = TWiLightIdentityResolver.resolve(joined, evidence)
        val evidenceNanos = System.nanoTime() - evidenceStart
        val resolverNanos = System.nanoTime() - resolverStart

        val usrcheatSource = UsrcheatSource()
        val usrcheatArtifact = IdentityArtifactReference(
            filename = usrcheatPath.fileName.toString(),
            sourcePath = USRCHEAT_ARTIFACT_PATH,
            sizeBytes = Files.size(usrcheatPath),
            sha256 = usrcheatSha,
        )
        val inputs = listOf(
            usrcheatArtifact,
            IdentityArtifactReference(
                filename = twilightCandidatesPath.fileName.toString(),
                sourcePath = "build/widescreen-quarantine/${twilightCandidatesPath.fileName}",
                sizeBytes = candidatesBytes.size.toLong(),
                sha256 = candidatesSha,
            ),
            IdentityArtifactReference(
                filename = widescreenProfilesPath.fileName.toString(),
                sourcePath = "app/widescreen/${widescreenProfilesPath.fileName}",
                sizeBytes = profilesBytes.size.toLong(),
                sha256 = sha256(profilesBytes),
            ),
            IdentityArtifactReference(
                filename = widescreenSourcesPath.fileName.toString(),
                sourcePath = "app/widescreen/${widescreenSourcesPath.fileName}",
                sizeBytes = sourcesBytes.size.toLong(),
                sha256 = sha256(sourcesBytes),
            ),
        )
        val artifacts = TWiLightIdentityResolver.encodeArtifacts(
            usrcheatIndex = usrcheatIndex,
            candidatesDocument = candidatesDocument,
            evidenceRecords = evidence,
            resolutions = resolutions,
            usrcheatSource = usrcheatSource,
            usrcheatArtifact = usrcheatArtifact,
            inputs = inputs,
        )

        val pokemon = resolutions.singleOrNull {
            it.upstreamTid == "IRAF" && it.upstreamHeaderCrc16 == "BC1D"
        } ?: error("Pinned TWiLight candidates must contain exactly one IRAF/BC1D sentinel")
        val pokemonRuntimeSentinelPresent = pokemon.evidence.any {
            it.type == IdentityEvidenceType.RUNTIME_OBSERVED && it.headerChecksum32 == "031EF208"
        }
        if (localScan == null) {
            require(
                pokemon.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED &&
                    pokemon.resolvedHeaderChecksum32 == "031EF208" &&
                    pokemonRuntimeSentinelPresent,
            ) { "IRAF/BC1D runtime evidence sentinel did not resolve exactly" }
        } else {
            require(pokemonRuntimeSentinelPresent) { "IRAF/BC1D runtime evidence sentinel is missing" }
        }

        Files.createDirectories(outputDirectory)
        val identitiesPath = outputDirectory.resolve(USRCHEAT_IDENTITIES_FILENAME)
        val resolutionPath = outputDirectory.resolve(RESOLUTION_FILENAME)
        val summaryPath = outputDirectory.resolve(SUMMARY_FILENAME)
        Files.write(identitiesPath, artifacts.usrcheatIdentitiesBytes)
        Files.write(resolutionPath, artifacts.resolutionBytes)
        Files.write(summaryPath, artifacts.summaryBytes)
        val localRomResult = localScan?.let { scan ->
            val evidencePath = outputDirectory.resolve(LocalRomIdentityScanner.EVIDENCE_FILENAME)
            Files.write(evidencePath, scan.evidenceBytes)
            val exactCandidateKeys = candidatesDocument.candidates.asSequence()
                .filterNot { it.upstreamWildcard }
                .mapNotNull { candidate ->
                    val gameCode = candidate.upstreamTid ?: return@mapNotNull null
                    val crc16 = candidate.upstreamHeaderCrc16 ?: return@mapNotNull null
                    gameCode to crc16
                }
                .toSet()
            val baselineByPath = requireNotNull(baselineResolutions).associateBy { it.sourceCandidatePath }
            val newlyResolvedPaths = resolutions.asSequence()
                .filter { it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.RESOLVED }
                .filter { baselineByPath.getValue(it.sourceCandidatePath).runtimeIdentityStatus != ResolvedRuntimeIdentityStatus.RESOLVED }
                .map { it.sourceCandidatePath }
                .toSet()
            val supportedPaths = candidatesDocument.candidates.asSequence()
                .filter { it.arValidationStatus == "SUPPORTED" }
                .map { it.sourcePath }
                .toSet()
            LocalRomIdentityRunResult(
                evidencePath = evidencePath,
                evidenceSizeBytes = scan.evidenceBytes.size.toLong(),
                evidenceSha256 = sha256(scan.evidenceBytes),
                scan = scan,
                resolutionStats = LocalRomResolutionStats(
                    localEvidenceUniqueCount = scan.evidenceRecords.size,
                    localEvidenceMatchingTWiLightCount = scan.evidenceRecords.count {
                        it.gameCode to it.upstreamHeaderCrc16 in exactCandidateKeys
                    },
                    localEvidenceWithoutTWiLightCandidateCount = scan.evidenceRecords.count {
                        it.gameCode to it.upstreamHeaderCrc16 !in exactCandidateKeys
                    },
                    localEvidenceAbsentFromUsrcheatCount = scan.evidenceRecords.count {
                        it.headerChecksum32 !in usrcheatIndex.checksumsByGameCode[it.gameCode].orEmpty()
                    },
                    newlyResolvedCandidateCount = newlyResolvedPaths.size,
                    newlyResolvedSupportedCount = newlyResolvedPaths.count { it in supportedPaths },
                    conflictCandidateCount = resolutions.count {
                        it.runtimeIdentityStatus == ResolvedRuntimeIdentityStatus.CONFLICT
                    },
                ),
            )
        }
        val summary = json.decodeFromString<TWiLightIdentitySummary>(String(artifacts.summaryBytes, UTF_8))
        return TWiLightIdentityRunResult(
            usrcheatIdentitiesPath = identitiesPath,
            resolutionPath = resolutionPath,
            summaryPath = summaryPath,
            usrcheatIdentitiesSizeBytes = artifacts.usrcheatIdentitiesBytes.size.toLong(),
            usrcheatIdentitiesSha256 = sha256(artifacts.usrcheatIdentitiesBytes),
            resolutionSizeBytes = artifacts.resolutionBytes.size.toLong(),
            resolutionSha256 = sha256(artifacts.resolutionBytes),
            summarySizeBytes = artifacts.summaryBytes.size.toLong(),
            summarySha256 = sha256(artifacts.summaryBytes),
            summary = summary,
            metrics = TWiLightIdentityRunMetrics(
                usrcheatParsingMillis = parseNanos.toMillis(),
                joinMillis = joinNanos.toMillis(),
                evidenceResolutionMillis = evidenceNanos.toMillis(),
                evidenceMergeMillis = mergeNanos.toMillis(),
                resolverTotalMillis = resolverNanos.toMillis(),
                totalMillis = (System.nanoTime() - totalStart).toMillis(),
            ),
            localRom = localRomResult,
        )
    }
}

object TWiLightIdentityResolverMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 5..6) {
            "Usage: TWiLightIdentityResolverMain <usrcheat.dat> <TWiLight candidates JSON> " +
                "<widescreen sources JSON> <widescreen profiles JSON> <output directory> " +
                "[local ROM directory]"
        }
        val result = TWiLightIdentityResolutionRunner.run(
            usrcheatPath = Path.of(args[0]),
            twilightCandidatesPath = Path.of(args[1]),
            widescreenSourcesPath = Path.of(args[2]),
            widescreenProfilesPath = Path.of(args[3]),
            outputDirectory = Path.of(args[4]),
            localRomDirectory = args.getOrNull(5)?.let { Path.of(it) },
        )
        println(
            "Identity resolution: usrcheat=${result.summary.usrcheatCounts.entryCount}, " +
                "TWiLight=${result.summary.twilightCandidateCount}, " +
                "resolved=${result.summary.runtimeIdentityStatusDistribution.countFor("RESOLVED")}",
        )
        println(result.usrcheatIdentitiesPath.describe(result.usrcheatIdentitiesSizeBytes, result.usrcheatIdentitiesSha256))
        println(result.resolutionPath.describe(result.resolutionSizeBytes, result.resolutionSha256))
        println(result.summaryPath.describe(result.summarySizeBytes, result.summarySha256))
        result.localRom?.let { local ->
            val scan = local.scan.stats
            val resolution = local.resolutionStats
            println(local.evidencePath.describe(local.evidenceSizeBytes, local.evidenceSha256))
            println(
                "Local ROM scan: filesDiscovered=${scan.filesDiscovered}, filesAccepted=${scan.filesAccepted}, " +
                    "filesTooSmall=${scan.filesTooSmall}, filesInvalidHeader=${scan.filesInvalidHeader}, " +
                    "filesDuplicateIdentity=${scan.duplicateLocalEvidenceCount}, " +
                    "duplicateLocalEvidenceCount=${scan.duplicateLocalEvidenceCount}, " +
                    "wildcardsIgnored=${scan.wildcardIdentityCount}",
            )
            println(
                "Local evidence: localRomFilesScanned=${scan.filesAccepted}, " +
                    "localEvidenceRecords=${resolution.localEvidenceUniqueCount}, " +
                    "localEvidenceMatchingTWiLight=${resolution.localEvidenceMatchingTWiLightCount}, " +
                    "localEvidenceWithoutTWiLightCandidate=${resolution.localEvidenceWithoutTWiLightCandidateCount}, " +
                    "absentFromUsrcheat=${resolution.localEvidenceAbsentFromUsrcheatCount}, " +
                    "newlyResolvedCandidates=${resolution.newlyResolvedCandidateCount}, " +
                    "newlyResolvedSupportedCandidates=${resolution.newlyResolvedSupportedCount}, " +
                    "conflicts=${resolution.conflictCandidateCount}",
            )
            local.scan.diagnostics.forEach { println("Local ROM skipped: $it") }
            println(
                String.format(
                    Locale.ROOT,
                    "Local timing: scan=%.3f ms, header parsing=%.3f ms, evidence merge=%.3f ms, resolver total=%.3f ms",
                    local.scan.metrics.scanMillis,
                    local.scan.metrics.headerParseMillis,
                    result.metrics.evidenceMergeMillis,
                    result.metrics.resolverTotalMillis,
                ),
            )
        }
        println(
            String.format(
                Locale.ROOT,
                "Timing: usrcheat parsing=%.3f ms, join=%.3f ms, evidence=%.3f ms, total=%.3f ms",
                result.metrics.usrcheatParsingMillis,
                result.metrics.joinMillis,
                result.metrics.evidenceResolutionMillis,
                result.metrics.totalMillis,
            ),
        )
    }

    private fun Path.describe(sizeBytes: Long, sha256: String): String =
        "$this ($sizeBytes bytes, sha256=$sha256)"

    private fun List<TWiLightValueCount>.countFor(value: String): Int =
        single { it.value == value }.count
}

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

private inline fun <reified T> canonicalJson(value: T): ByteArray =
    (canonicalJson.encodeToString(value) + '\n').toByteArray(UTF_8)

private fun UInt.toHex8(): String = String.format(Locale.ROOT, "%08X", toLong())

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

private fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun Long.toMillis(): Double = this / 1_000_000.0
