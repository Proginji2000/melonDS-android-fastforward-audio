package me.magnum.melonds.domain.widescreen

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.HexFormat
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val LOCAL_ROM_HEADER_SIZE = 0x200
const val NDS_GAME_CODE_OFFSET = 0x0C
const val NDS_HEADER_CRC16_OFFSET = 0x15E
const val TWILIGHT_WIDESCREEN_CRC16_V1 = "NDS_HEADER_STORED_U16_LE_AT_0x15E"

@Serializable
data class LocalRomIdentity(
    val gameCode: String,
    val upstreamHeaderCrc16: String,
    val headerChecksum32: String,
)

@Serializable
data class LocalRomIdentityEvidenceDocument(
    val schemaVersion: Int = 1,
    val evidenceType: IdentityEvidenceType = IdentityEvidenceType.LOCAL_ROM_HEADER,
    val identityDefinition: String = TWILIGHT_WIDESCREEN_CRC16_V1,
    val checksumAlgorithm: String = "CRC32_JAMCRC_FIRST_512_BYTES",
    val identities: List<LocalRomIdentity>,
)

data class LocalRomScanStats(
    val filesDiscovered: Int,
    val filesAccepted: Int,
    val filesTooSmall: Int,
    val filesInvalidHeader: Int,
    val duplicateLocalEvidenceCount: Int,
    val wildcardIdentityCount: Int,
)

data class LocalRomScanMetrics(
    val scanMillis: Double,
    val headerParseMillis: Double,
)

data class LocalRomScanResult(
    val identities: List<LocalRomIdentity>,
    val evidenceRecords: List<IdentityEvidenceRecord>,
    val evidenceBytes: ByteArray,
    val stats: LocalRomScanStats,
    val metrics: LocalRomScanMetrics,
    val diagnostics: List<String>,
)

object LocalRomIdentityScanner {
    const val EVIDENCE_FILENAME = "local-rom-identity-evidence.json"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun scan(root: Path): LocalRomScanResult {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Local ROM directory does not exist: $root" }
        require(!Files.isSymbolicLink(root)) { "Local ROM directory must not be a symbolic link: $root" }

        val scanStart = System.nanoTime()
        val diagnostics = mutableListOf<String>()
        val candidates = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isSymbolicLink && attrs.isRegularFile && isSupported(file)) {
                    candidates.add(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (isSupported(file)) {
                    candidates.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        val scanNanos = System.nanoTime() - scanStart

        val sortedCandidates = candidates.distinct().sortedBy { relativeKey(root, it) }
        val parsed = mutableListOf<LocalRomIdentity>()
        var tooSmall = 0
        var invalidHeader = 0
        var parseNanos = 0L
        for (path in sortedCandidates) {
            val parseStart = System.nanoTime()
            try {
                if (Files.size(path) < LOCAL_ROM_HEADER_SIZE) {
                    tooSmall++
                    continue
                }
                val header = readHeader(path)
                val gameCode = parseGameCode(header)
                if (gameCode == null) {
                    invalidHeader++
                    diagnostics += "${relativeKey(root, path)}: invalid game code"
                    continue
                }
                parsed += LocalRomIdentity(
                    gameCode = gameCode,
                    upstreamHeaderCrc16 = readStoredHeaderCrc16(header),
                    headerChecksum32 = jamCrc32(header),
                )
            } catch (error: IOException) {
                invalidHeader++
                diagnostics += "${relativeKey(root, path)}: ${error.javaClass.simpleName}"
            } catch (error: SecurityException) {
                invalidHeader++
                diagnostics += "${relativeKey(root, path)}: ${error.javaClass.simpleName}"
            } finally {
                parseNanos += System.nanoTime() - parseStart
            }
        }

        val identities = parsed.distinct().sortedWith(
            compareBy({ it.gameCode }, { it.upstreamHeaderCrc16 }, { it.headerChecksum32 }),
        )
        val wildcardCount = identities.count { it.upstreamHeaderCrc16 == "FFFF" }
        val evidenceRecords = identities.asSequence()
            .filter { it.upstreamHeaderCrc16 != "FFFF" }
            .map { identity ->
                IdentityEvidenceRecord(
                    type = IdentityEvidenceType.LOCAL_ROM_HEADER,
                    sourceRef = "local-rom-header:${identity.gameCode}:${identity.upstreamHeaderCrc16}:${identity.headerChecksum32}",
                    gameCode = identity.gameCode,
                    upstreamHeaderCrc16 = identity.upstreamHeaderCrc16,
                    headerChecksum32 = identity.headerChecksum32,
                )
            }
            .toList()
        val evidenceBytes = (
            json.encodeToString(LocalRomIdentityEvidenceDocument(identities = identities)) + '\n'
        ).toByteArray(UTF_8)

        return LocalRomScanResult(
            identities = identities,
            evidenceRecords = evidenceRecords,
            evidenceBytes = evidenceBytes,
            stats = LocalRomScanStats(
                filesDiscovered = sortedCandidates.size,
                filesAccepted = parsed.size,
                filesTooSmall = tooSmall,
                filesInvalidHeader = invalidHeader,
                duplicateLocalEvidenceCount = parsed.size - identities.size,
                wildcardIdentityCount = wildcardCount,
            ),
            metrics = LocalRomScanMetrics(
                scanMillis = scanNanos / 1_000_000.0,
                headerParseMillis = parseNanos / 1_000_000.0,
            ),
            diagnostics = diagnostics,
        )
    }

    private fun readHeader(path: Path): ByteArray {
        val buffer = ByteBuffer.allocate(LOCAL_ROM_HEADER_SIZE)
        FileChannel.open(path, READ).use { channel ->
            var offset = 0L
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, offset)
                if (read < 0) throw IOException("Unexpected end of local ROM header")
                if (read == 0) throw IOException("Unable to make progress reading local ROM header")
                offset += read
            }
        }
        return buffer.array()
    }

    private fun parseGameCode(header: ByteArray): String? {
        val bytes = header.copyOfRange(NDS_GAME_CODE_OFFSET, NDS_GAME_CODE_OFFSET + 4)
        if (bytes.any { it.toInt() !in 1..0x7F }) return null
        val gameCode = String(bytes, US_ASCII)
        return gameCode.takeIf(GAME_CODE_PATTERN::matches)
    }

    private fun readStoredHeaderCrc16(header: ByteArray): String {
        // TWiLightMenu compares this stored field directly; it does not recalculate it for lookup.
        val value = ByteBuffer.wrap(header, NDS_HEADER_CRC16_OFFSET, 2).order(LITTLE_ENDIAN).short
        return HexFormat.of().withUpperCase().toHexDigits(value)
    }

    private fun jamCrc32(header: ByteArray): String {
        val crc32 = CRC32()
        crc32.update(header)
        val jamCrc32 = (crc32.value xor 0xFFFF_FFFFL).toInt()
        return HexFormat.of().withUpperCase().toHexDigits(jamCrc32)
    }

    private fun isSupported(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("nds", "srl")

    private fun relativeKey(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')
}
