package me.magnum.melonds.domain.widescreen

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.streams.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val TWILIGHT_WIDESCREEN_PROJECT = "DS-Homebrew/TWiLightMenu"
const val TWILIGHT_WIDESCREEN_REPOSITORY = "https://github.com/DS-Homebrew/TWiLightMenu.git"
const val TWILIGHT_WIDESCREEN_VERSION = "v27.24.1"
const val TWILIGHT_WIDESCREEN_COMMIT = "68d04c1a621a8d330e7233efcfcb93c94b30a3a6"
const val TWILIGHT_WIDESCREEN_SOURCE_REF =
    "twilightmenu-v27.24.1-68d04c1a621a8d330e7233efcfcb93c94b30a3a6"
const val TWILIGHT_WIDESCREEN_CORPUS_PATH = "resources/widescreen"
const val TWILIGHT_WIDESCREEN_PATCH_LICENSE = "NOASSERTION"

private const val POKEMON_SENTINEL_FILENAME = "IRAF-BC1D.bin"
private const val POKEMON_SENTINEL_SHA256 =
    "74f06acc1af52765f1e87a4dd40473e7dc091c785a71eca5621205694e7de8ca"
private val POKEMON_SENTINEL_LINES = listOf(
    "922822A8 00001555",
    "122822A8 0000199A",
    "D2000000 00000000",
)

@Serializable
data class TWiLightWidescreenSource(
    val ref: String = TWILIGHT_WIDESCREEN_SOURCE_REF,
    val project: String = TWILIGHT_WIDESCREEN_PROJECT,
    val repository: String = TWILIGHT_WIDESCREEN_REPOSITORY,
    val version: String = TWILIGHT_WIDESCREEN_VERSION,
    val commit: String = TWILIGHT_WIDESCREEN_COMMIT,
    val corpusPath: String = TWILIGHT_WIDESCREEN_CORPUS_PATH,
)

@Serializable
data class TWiLightWidescreenCandidate(
    val sourceRef: String,
    val sourcePath: String,
    val sourceFilename: String,
    val extension: String,
    val sizeBytes: Long,
    val sourceSha256: String,
    val upstreamTid: String?,
    val upstreamHeaderCrc16: String?,
    val upstreamHeaderCrc16Original: String?,
    val upstreamWildcard: Boolean,
    val filenameStatus: String,
    val decodeStatus: String,
    val decodeDiagnostics: List<String>,
    val actionReplayLines: List<String>,
    val canonicalArBinarySha256: String?,
    val arValidationStatus: String?,
    val usedOpcodes: List<String>,
    val arDiagnostics: List<String>,
    val instructionCount: Int?,
    val payloadBytes: Long?,
    val addressNormalizedStructureSha256: String?,
    val runtimeIdentityStatus: String,
    val headerChecksum32: String?,
    val classificationStatus: String,
    val targetScreenStatus: String,
    val conversionStatus: String,
    val promotionStatus: String,
    val patchLicense: String,
)

@Serializable
data class TWiLightWidescreenCandidatesDocument(
    val schemaVersion: Int = 1,
    val source: TWiLightWidescreenSource,
    val candidates: List<TWiLightWidescreenCandidate>,
)

@Serializable
data class TWiLightArtifactReference(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class TWiLightActionReplayCapability(
    val version: String,
    val coreCommit: String,
    val engineSourceSha256: String,
)

@Serializable
data class TWiLightValueCount(
    val value: String,
    val count: Int,
)

@Serializable
data class TWiLightFileDiagnostic(
    val sourcePath: String,
    val diagnostics: List<String>,
)

@Serializable
data class TWiLightCandidateGroup(
    val key: String,
    val candidates: List<String>,
)

@Serializable
data class TWiLightTidVariants(
    val tid: String,
    val crc16Values: List<String>,
    val candidates: List<String>,
)

@Serializable
data class TWiLightCorpusCounts(
    val totalFiles: Int,
    val binFiles: Int,
    val validBinFiles: Int,
    val decodedArPatches: Int,
    val distinctTids: Int,
    val multiVariantTidCount: Int,
    val maxVariantsPerTid: Int,
    val upstreamWildcardCount: Int,
    val nonPaddedCrc16Count: Int,
    val atypicalFilenameCount: Int,
    val undecodableCount: Int,
)

@Serializable
data class TWiLightCollisionSummary(
    val duplicateSourcePathGroups: List<TWiLightCandidateGroup>,
    val duplicateFilenameGroups: List<TWiLightCandidateGroup>,
    val duplicateUpstreamIdentityGroups: List<TWiLightCandidateGroup>,
)

@Serializable
data class TWiLightDuplicateAnalysis(
    val identicalSourceShaGroups: List<TWiLightCandidateGroup>,
    val identicalCanonicalArGroups: List<TWiLightCandidateGroup>,
    val identicalOpcodeSetGroups: List<TWiLightCandidateGroup>,
    val addressNormalizedStructureGroups: List<TWiLightCandidateGroup>,
    val sizeBytesDistribution: List<TWiLightValueCount>,
)

@Serializable
data class TWiLightCandidateExample(
    val sourcePath: String,
    val sourceSha256: String,
    val upstreamTid: String?,
    val upstreamHeaderCrc16: String?,
    val upstreamWildcard: Boolean,
    val arValidationStatus: String?,
    val actionReplayLines: List<String>,
)

@Serializable
data class TWiLightCorpusExamples(
    val pokemonSentinel: TWiLightCandidateExample,
    val secondGoldenPatch: TWiLightCandidateExample?,
    val wildcard: TWiLightCandidateExample?,
    val multiVariantTid: TWiLightTidVariants?,
)

@Serializable
data class TWiLightWidescreenSummary(
    val schemaVersion: Int = 1,
    val source: TWiLightWidescreenSource,
    val actionReplayCapability: TWiLightActionReplayCapability,
    val candidatesArtifact: TWiLightArtifactReference,
    val counts: TWiLightCorpusCounts,
    val arStatusDistribution: List<TWiLightValueCount>,
    val opcodePatchDistribution: List<TWiLightValueCount>,
    val blockingReasonDistribution: List<TWiLightValueCount>,
    val multiVariantTids: List<TWiLightTidVariants>,
    val filenameDiagnostics: List<TWiLightFileDiagnostic>,
    val decodeDiagnostics: List<TWiLightFileDiagnostic>,
    val collisions: TWiLightCollisionSummary,
    val duplicateAnalysis: TWiLightDuplicateAnalysis,
    val examples: TWiLightCorpusExamples,
)

data class TWiLightImportMetrics(
    val inventoryMillis: Double,
    val decodeMillis: Double,
    val actionReplayValidationMillis: Double,
    val totalMillis: Double,
)

data class TWiLightImportResult(
    val candidatesPath: Path,
    val summaryPath: Path,
    val candidatesSizeBytes: Long,
    val candidatesSha256: String,
    val summarySizeBytes: Long,
    val summarySha256: String,
    val counts: TWiLightCorpusCounts,
    val metrics: TWiLightImportMetrics,
)

object TWiLightWidescreenImporter {
    const val CANDIDATES_FILENAME = "twilight-widescreen-candidates.json"
    const val SUMMARY_FILENAME = "twilight-widescreen-summary.json"

    private val canonicalFilename = Regex("^([A-Z0-9]{4})-([0-9A-F]{1,4})\\.bin$")
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun importCheckout(checkoutDirectory: Path, outputDirectory: Path): TWiLightImportResult {
        val totalStart = System.nanoTime()
        val corpusDirectory = checkoutDirectory.resolve(TWILIGHT_WIDESCREEN_CORPUS_PATH)
        require(Files.isDirectory(corpusDirectory)) {
            "Missing pinned TWiLight widescreen corpus: $corpusDirectory"
        }

        val inventoryStart = System.nanoTime()
        val inventory = Files.walk(corpusDirectory).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { path -> inventoryEntry(checkoutDirectory, path) }
                .toList()
                .sortedBy { it.sourcePath }
        }
        require(inventory.isNotEmpty()) { "Pinned TWiLight widescreen corpus is empty" }
        val inventoryNanos = System.nanoTime() - inventoryStart

        var decodeNanos = 0L
        var validationNanos = 0L
        val preliminaryCandidates = inventory.map { entry ->
            var decoded: DecodedActionReplay? = null
            val decodeStart = System.nanoTime()
            val decodeFailure = when {
                entry.extension != ".bin" -> "UNSUPPORTED_FORMAT: expected a .bin file"
                entry.bytes.isEmpty() -> "DECODE_FAILED: empty .bin file"
                entry.bytes.size % 8 != 0 ->
                    "DECODE_FAILED: .bin size ${entry.bytes.size} is not a multiple of 8"
                else -> {
                    decoded = decodeActionReplay(entry.bytes)
                    null
                }
            }
            decodeNanos += System.nanoTime() - decodeStart

            val validation = decoded?.let {
                val validationStart = System.nanoTime()
                val result = ActionReplayValidator.validate(it.lines)
                validationNanos += System.nanoTime() - validationStart
                result
            }
            entry.toCandidate(decoded, decodeFailure, validation)
        }

        val variantCounts = preliminaryCandidates
            .filter { it.upstreamTid != null && it.upstreamHeaderCrc16 != null }
            .groupBy { requireNotNull(it.upstreamTid) }
            .mapValues { (_, candidates) ->
                candidates.map { requireNotNull(it.upstreamHeaderCrc16) }.distinct().size
            }
        val candidates = preliminaryCandidates.map { candidate ->
            val variantCount = candidate.upstreamTid?.let(variantCounts::get) ?: 0
            candidate.copy(
                runtimeIdentityStatus = if (!candidate.upstreamWildcard && variantCount > 1) {
                    "AMBIGUOUS"
                } else {
                    "UNRESOLVED"
                },
            )
        }
        validatePinnedPokemonSentinel(candidates)

        val source = TWiLightWidescreenSource()
        val candidatesDocument = TWiLightWidescreenCandidatesDocument(
            source = source,
            candidates = candidates,
        )
        val candidatesBytes = canonicalJson(candidatesDocument)
        val candidatesSha256 = sha256(candidatesBytes)
        val summary = buildSummary(source, candidates, candidatesBytes.size.toLong(), candidatesSha256)
        val summaryBytes = canonicalJson(summary)

        Files.createDirectories(outputDirectory)
        val candidatesPath = outputDirectory.resolve(CANDIDATES_FILENAME)
        val summaryPath = outputDirectory.resolve(SUMMARY_FILENAME)
        Files.write(candidatesPath, candidatesBytes)
        Files.write(summaryPath, summaryBytes)

        return TWiLightImportResult(
            candidatesPath = candidatesPath,
            summaryPath = summaryPath,
            candidatesSizeBytes = candidatesBytes.size.toLong(),
            candidatesSha256 = candidatesSha256,
            summarySizeBytes = summaryBytes.size.toLong(),
            summarySha256 = sha256(summaryBytes),
            counts = summary.counts,
            metrics = TWiLightImportMetrics(
                inventoryMillis = inventoryNanos.toMillis(),
                decodeMillis = decodeNanos.toMillis(),
                actionReplayValidationMillis = validationNanos.toMillis(),
                totalMillis = (System.nanoTime() - totalStart).toMillis(),
            ),
        )
    }

    private fun inventoryEntry(checkoutDirectory: Path, path: Path): InventoryEntry {
        val bytes = Files.readAllBytes(path)
        val filenameMatch = canonicalFilename.matchEntire(path.name)
        val originalCrc = filenameMatch?.groupValues?.get(2)
        return InventoryEntry(
            sourcePath = path.relativeTo(checkoutDirectory).toString().replace('\\', '/'),
            filename = path.name,
            extension = if (path.extension.isEmpty()) "" else ".${path.extension.lowercase(Locale.ROOT)}",
            bytes = bytes,
            sourceSha256 = sha256(bytes),
            tid = filenameMatch?.groupValues?.get(1),
            crc16 = originalCrc?.padStart(4, '0'),
            originalCrc16 = originalCrc,
        )
    }

    private fun decodeActionReplay(bytes: ByteArray): DecodedActionReplay {
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val lines = buildList(bytes.size / 8) {
            while (buffer.hasRemaining()) {
                add(String.format(Locale.ROOT, "%08X %08X", buffer.int, buffer.int))
            }
        }
        val canonicalBytes = ByteBuffer.allocate(bytes.size).order(LITTLE_ENDIAN).apply {
            lines.forEach { line ->
                putInt(line.substring(0, 8).toUInt(16).toInt())
                putInt(line.substring(9, 17).toUInt(16).toInt())
            }
        }.array()
        return DecodedActionReplay(lines, sha256(canonicalBytes))
    }

    private fun InventoryEntry.toCandidate(
        decoded: DecodedActionReplay?,
        decodeFailure: String?,
        validation: ActionReplayValidationResult?,
    ): TWiLightWidescreenCandidate {
        val filenameDiagnostic = if (tid == null) {
            listOf("UNRECOGNIZED_FILENAME: expected TID-CRC.bin")
        } else {
            emptyList()
        }
        return TWiLightWidescreenCandidate(
            sourceRef = TWILIGHT_WIDESCREEN_SOURCE_REF,
            sourcePath = sourcePath,
            sourceFilename = filename,
            extension = extension,
            sizeBytes = bytes.size.toLong(),
            sourceSha256 = sourceSha256,
            upstreamTid = tid,
            upstreamHeaderCrc16 = crc16,
            upstreamHeaderCrc16Original = originalCrc16,
            upstreamWildcard = crc16 == "FFFF",
            filenameStatus = if (tid == null) "UNRECOGNIZED_FILENAME" else "RECOGNIZED",
            decodeStatus = when {
                decodeFailure == null -> "DECODED"
                extension != ".bin" -> "UNSUPPORTED_FORMAT"
                else -> "DECODE_FAILED"
            },
            decodeDiagnostics = filenameDiagnostic + listOfNotNull(decodeFailure),
            actionReplayLines = decoded?.lines.orEmpty(),
            canonicalArBinarySha256 = decoded?.canonicalSha256,
            arValidationStatus = validation?.status?.name,
            usedOpcodes = validation?.usedOpcodes.orEmpty(),
            arDiagnostics = validation?.diagnostics.orEmpty(),
            instructionCount = validation?.instructionCount,
            payloadBytes = validation?.payloadBytes,
            addressNormalizedStructureSha256 = validation?.let(::addressNormalizedStructureSha256),
            runtimeIdentityStatus = "UNRESOLVED",
            headerChecksum32 = null,
            classificationStatus = "UNCLASSIFIED",
            targetScreenStatus = "UNRESOLVED",
            conversionStatus = "NOT_EVALUATED",
            promotionStatus = "QUARANTINED",
            patchLicense = TWILIGHT_WIDESCREEN_PATCH_LICENSE,
        )
    }

    private fun buildSummary(
        source: TWiLightWidescreenSource,
        candidates: List<TWiLightWidescreenCandidate>,
        candidatesSize: Long,
        candidatesSha256: String,
    ): TWiLightWidescreenSummary {
        val recognized = candidates.filter { it.upstreamTid != null && it.upstreamHeaderCrc16 != null }
        val byTid = recognized.groupBy { requireNotNull(it.upstreamTid) }.toSortedMap()
        val multiVariantTids = byTid.mapNotNull { (tid, variants) ->
            val crcValues = variants.map { requireNotNull(it.upstreamHeaderCrc16) }.distinct().sorted()
            if (crcValues.size <= 1) null else TWiLightTidVariants(
                tid = tid,
                crc16Values = crcValues,
                candidates = variants.map { it.sourcePath }.sorted(),
            )
        }
        val counts = TWiLightCorpusCounts(
            totalFiles = candidates.size,
            binFiles = candidates.count { it.extension == ".bin" },
            validBinFiles = candidates.count { it.extension == ".bin" && it.decodeStatus == "DECODED" },
            decodedArPatches = candidates.count { it.arValidationStatus != null },
            distinctTids = byTid.size,
            multiVariantTidCount = multiVariantTids.size,
            maxVariantsPerTid = byTid.values.maxOfOrNull { variants ->
                variants.mapNotNull { it.upstreamHeaderCrc16 }.distinct().size
            } ?: 0,
            upstreamWildcardCount = candidates.count { it.upstreamWildcard },
            nonPaddedCrc16Count = candidates.count {
                it.upstreamHeaderCrc16Original?.length?.let { length -> length < 4 } == true
            },
            atypicalFilenameCount = candidates.count { it.filenameStatus != "RECOGNIZED" },
            undecodableCount = candidates.count { it.decodeStatus != "DECODED" },
        )

        val pokemon = candidates.single { it.sourceFilename == POKEMON_SENTINEL_FILENAME }
        val secondGolden = candidates.asSequence()
            .filter { it.sourceFilename != POKEMON_SENTINEL_FILENAME }
            .filter { !it.upstreamWildcard }
            .filter { it.decodeStatus == "DECODED" && it.arValidationStatus == "SUPPORTED" }
            .sortedWith(compareBy<TWiLightWidescreenCandidate>({ it.instructionCount }, { it.sourcePath }))
            .firstOrNull()
        val wildcard = candidates.firstOrNull { it.upstreamWildcard }

        return TWiLightWidescreenSummary(
            source = source,
            actionReplayCapability = TWiLightActionReplayCapability(
                version = ACTION_REPLAY_CAPABILITY_VERSION,
                coreCommit = ACTION_REPLAY_ENGINE_CORE_COMMIT,
                engineSourceSha256 = ACTION_REPLAY_ENGINE_SOURCE_SHA256,
            ),
            candidatesArtifact = TWiLightArtifactReference(
                filename = CANDIDATES_FILENAME,
                sizeBytes = candidatesSize,
                sha256 = candidatesSha256,
            ),
            counts = counts,
            arStatusDistribution = valueCounts(
                values = candidates.mapNotNull { it.arValidationStatus },
                preferredOrder = listOf("SUPPORTED", "PARTIALLY_SUPPORTED", "UNSUPPORTED", "INVALID"),
            ),
            opcodePatchDistribution = valueCounts(candidates.flatMap { it.usedOpcodes.distinct() }),
            blockingReasonDistribution = blockingReasonCounts(candidates),
            multiVariantTids = multiVariantTids,
            filenameDiagnostics = candidates
                .filter { it.filenameStatus != "RECOGNIZED" }
                .map { TWiLightFileDiagnostic(it.sourcePath, it.decodeDiagnostics) },
            decodeDiagnostics = candidates
                .filter { it.decodeStatus != "DECODED" }
                .map { TWiLightFileDiagnostic(it.sourcePath, it.decodeDiagnostics) },
            collisions = TWiLightCollisionSummary(
                duplicateSourcePathGroups = groupedCandidates(candidates, { it.sourcePath }),
                duplicateFilenameGroups = groupedCandidates(candidates, { it.sourceFilename }),
                duplicateUpstreamIdentityGroups = groupedCandidates(
                    recognized,
                    { "${it.upstreamTid}-${it.upstreamHeaderCrc16}" },
                ),
            ),
            duplicateAnalysis = TWiLightDuplicateAnalysis(
                identicalSourceShaGroups = groupedCandidates(candidates, { it.sourceSha256 }),
                identicalCanonicalArGroups = groupedCandidates(
                    candidates.filter { it.canonicalArBinarySha256 != null },
                    { requireNotNull(it.canonicalArBinarySha256) },
                ),
                identicalOpcodeSetGroups = groupedCandidates(
                    candidates.filter { it.arValidationStatus != null },
                    { it.usedOpcodes.sorted().joinToString("+") },
                ),
                addressNormalizedStructureGroups = groupedCandidates(
                    candidates.filter { it.addressNormalizedStructureSha256 != null },
                    { requireNotNull(it.addressNormalizedStructureSha256) },
                ).filter { group ->
                    group.candidates.map { path ->
                        candidates.single { it.sourcePath == path }.canonicalArBinarySha256
                    }.distinct().size > 1
                },
                sizeBytesDistribution = valueCounts(candidates.map { it.sizeBytes.toString() }),
            ),
            examples = TWiLightCorpusExamples(
                pokemonSentinel = pokemon.toExample(),
                secondGoldenPatch = secondGolden?.toExample(),
                wildcard = wildcard?.toExample(),
                multiVariantTid = multiVariantTids.firstOrNull(),
            ),
        )
    }

    private fun validatePinnedPokemonSentinel(candidates: List<TWiLightWidescreenCandidate>) {
        val sentinel = candidates.singleOrNull { it.sourceFilename == POKEMON_SENTINEL_FILENAME }
            ?: error("Pinned corpus must contain exactly one $POKEMON_SENTINEL_FILENAME")
        require(sentinel.sourceSha256 == POKEMON_SENTINEL_SHA256) {
            "$POKEMON_SENTINEL_FILENAME SHA-256 mismatch: ${sentinel.sourceSha256}"
        }
        require(sentinel.actionReplayLines == POKEMON_SENTINEL_LINES) {
            "$POKEMON_SENTINEL_FILENAME Action Replay decode mismatch"
        }
    }

    private fun blockingReasonCounts(
        candidates: List<TWiLightWidescreenCandidate>,
    ): List<TWiLightValueCount> {
        val reasons = buildList {
            candidates.forEach { candidate ->
                add("runtime identity ${candidate.runtimeIdentityStatus.lowercase(Locale.ROOT)}")
                add("target screen unresolved")
                add("classification unclassified")
                add("conversion not evaluated")
                add("promotion quarantined")
                if (candidate.upstreamWildcard) add("upstream wildcard")
                if (candidate.filenameStatus != "RECOGNIZED") add("unrecognized filename")
                if (candidate.decodeStatus != "DECODED") {
                    add(candidate.decodeStatus.lowercase(Locale.ROOT).replace('_', ' '))
                }
                when (candidate.arValidationStatus) {
                    "PARTIALLY_SUPPORTED" -> add("AR partially supported")
                    "UNSUPPORTED" -> add("AR unsupported")
                    "INVALID" -> add("AR invalid")
                }
            }
        }
        return reasons.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { TWiLightValueCount(it.key, it.value) }
    }

    private fun groupedCandidates(
        candidates: List<TWiLightWidescreenCandidate>,
        keySelector: (TWiLightWidescreenCandidate) -> String,
    ): List<TWiLightCandidateGroup> {
        return candidates.groupBy(keySelector)
            .mapNotNull { (key, group) ->
                if (group.size <= 1) null else TWiLightCandidateGroup(
                    key = key,
                    candidates = group.map { it.sourcePath }.sorted(),
                )
            }
            .sortedWith(compareBy<TWiLightCandidateGroup>({ it.key }, { it.candidates.first() }))
    }

    private fun valueCounts(
        values: List<String>,
        preferredOrder: List<String> = emptyList(),
    ): List<TWiLightValueCount> {
        val counts = preferredOrder.associateWith { 0 }.toMutableMap()
        values.forEach { value -> counts[value] = counts.getOrDefault(value, 0) + 1 }
        val order = preferredOrder.withIndex().associate { it.value to it.index }
        return counts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>>({ order[it.key] ?: Int.MAX_VALUE }, { it.key }))
            .map { TWiLightValueCount(it.key, it.value) }
    }

    private fun TWiLightWidescreenCandidate.toExample() = TWiLightCandidateExample(
        sourcePath = sourcePath,
        sourceSha256 = sourceSha256,
        upstreamTid = upstreamTid,
        upstreamHeaderCrc16 = upstreamHeaderCrc16,
        upstreamWildcard = upstreamWildcard,
        arValidationStatus = arValidationStatus,
        actionReplayLines = actionReplayLines,
    )

    private fun addressNormalizedStructureSha256(
        validation: ActionReplayValidationResult,
    ): String {
        val lineCount = validation.instructions.sumOf { 1 + it.payload.size }
        val buffer = ByteBuffer.allocate(lineCount * 8).order(LITTLE_ENDIAN)
        validation.instructions.forEach { instruction ->
            val highNibble = (instruction.firstWord shr 28).toInt()
            val normalizedFirst = if (highNibble in 0x0..0xB || highNibble in 0xE..0xF) {
                instruction.firstWord and 0xF0000000u
            } else {
                instruction.firstWord
            }
            val normalizedSecond = if (highNibble == 0xF) 0u else instruction.secondWord
            buffer.putInt(normalizedFirst.toInt())
            buffer.putInt(normalizedSecond.toInt())
            instruction.payload.forEach { payload ->
                buffer.putInt(payload.firstWord.toInt())
                buffer.putInt(payload.secondWord.toInt())
            }
        }
        return sha256(buffer.array())
    }

    private fun canonicalJson(value: TWiLightWidescreenCandidatesDocument): ByteArray =
        (json.encodeToString(value) + '\n').toByteArray(UTF_8)

    private fun canonicalJson(value: TWiLightWidescreenSummary): ByteArray =
        (json.encodeToString(value) + '\n').toByteArray(UTF_8)

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun Long.toMillis(): Double = this / 1_000_000.0

    private data class InventoryEntry(
        val sourcePath: String,
        val filename: String,
        val extension: String,
        val bytes: ByteArray,
        val sourceSha256: String,
        val tid: String?,
        val crc16: String?,
        val originalCrc16: String?,
    )

    private data class DecodedActionReplay(
        val lines: List<String>,
        val canonicalSha256: String,
    )
}

object TWiLightWidescreenImporterMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: TWiLightWidescreenImporterMain <TWiLightMenu checkout> <output directory>"
        }
        val result = TWiLightWidescreenImporter.importCheckout(Path.of(args[0]), Path.of(args[1]))
        println(
            "TWiLight widescreen import: files=${result.counts.totalFiles}, " +
                "decoded=${result.counts.decodedArPatches}, tids=${result.counts.distinctTids}",
        )
        println(
            "Candidates: ${result.candidatesPath} " +
                "(${result.candidatesSizeBytes} bytes, sha256=${result.candidatesSha256})",
        )
        println(
            "Summary: ${result.summaryPath} " +
                "(${result.summarySizeBytes} bytes, sha256=${result.summarySha256})",
        )
        println(
            String.format(
                Locale.ROOT,
                "Timing: inventory=%.3f ms, decode=%.3f ms, AR validation=%.3f ms, total=%.3f ms",
                result.metrics.inventoryMillis,
                result.metrics.decodeMillis,
                result.metrics.actionReplayValidationMillis,
                result.metrics.totalMillis,
            ),
        )
    }
}
