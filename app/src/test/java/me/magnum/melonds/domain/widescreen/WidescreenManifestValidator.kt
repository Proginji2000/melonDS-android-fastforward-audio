package me.magnum.melonds.domain.widescreen

import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal const val WIDESCREEN_SCHEMA_VERSION = 1

@Serializable
internal data class WidescreenSourcesManifest(
    val schemaVersion: Int,
    val sources: List<WidescreenSourceRecord>,
)

@Serializable
internal data class WidescreenSourceRecord(
    val id: String,
    val kind: SourceKind,
    val provenance: SourceProvenance,
    val title: String,
    val locator: SourceLocator,
    val artifact: ActionReplayPatch? = null,
    val contributors: List<String>,
    val history: List<String>,
    val repositoryLicense: String? = null,
    val patchLicense: String,
)

@Serializable
internal enum class SourceKind {
    FORUM_POST,
    CODE_REFERENCE,
    GIT_ARTIFACT,
}

@Serializable
internal enum class SourceProvenance {
    PARTIAL,
    CORROBORATING,
    EXACT,
}

@Serializable
internal data class SourceLocator(
    val url: String,
    val version: String? = null,
    val commit: String? = null,
    val path: String? = null,
)

@Serializable
internal data class WidescreenProfilesManifest(
    val schemaVersion: Int,
    val romIdentity: RomIdentityDefinition,
    val profiles: List<CanonicalWidescreenProfile>,
)

@Serializable
internal data class RomIdentityDefinition(
    val algorithm: RomIdentityAlgorithm,
    val gameCode: GameCodeDefinition,
    val headerChecksum32: HeaderChecksum32Definition,
)

@Serializable
internal enum class RomIdentityAlgorithm {
    GAMECODE_PLUS_USRCHEAT_HEADER_CHECKSUM_V1,
}

@Serializable
internal data class GameCodeDefinition(
    val offsetBytes: Int,
    val lengthBytes: Int,
    val encoding: String,
    val caseSensitive: Boolean,
)

@Serializable
internal data class HeaderChecksum32Definition(
    val inputOffsetBytes: Int,
    val inputLengthBytes: Int,
    val implementation: String,
    val initialValueHex: String,
    val reflectedPolynomialHex: String,
    val finalXor: Boolean,
    val outputFormat: String,
)

@Serializable
internal data class CanonicalWidescreenProfile(
    val id: String,
    val rom: CanonicalRomIdentity,
    val upstreamIdentity: UpstreamRomIdentity? = null,
    val targetRatio: CanonicalRatio,
    val targetScreen: CanonicalTargetScreen,
    val patch: ActionReplayPatch,
    val classification: PatchClassification,
    val sourceRefs: List<String>,
    val conversion: ConversionRecipe? = null,
    val validation: ProfileValidation,
)

@Serializable
internal data class CanonicalRomIdentity(
    val gameCode: String,
    val headerChecksum32: String,
)

@Serializable
internal data class UpstreamRomIdentity(
    val namespace: String,
    val gameCode: String,
    val headerChecksum16: String,
)

@Serializable
internal enum class CanonicalRatio(val width: Int, val height: Int) {
    @SerialName("16:9")
    RATIO_16_9(16, 9),

    @SerialName("16:10")
    RATIO_16_10(16, 10),
}

@Serializable
internal enum class CanonicalTargetScreen {
    TOP,
    BOTTOM,
    UNRESOLVED,
}

@Serializable
internal data class ActionReplayPatch(
    val format: PatchFormat,
    val binaryEncoding: PatchBinaryEncoding,
    val byteLength: Int,
    val sha256: String,
    val actionReplayLines: List<String>,
)

@Serializable
internal enum class PatchFormat {
    ACTION_REPLAY_DS,
}

@Serializable
internal enum class PatchBinaryEncoding {
    UINT32_WORDS_LITTLE_ENDIAN,
}

@Serializable
internal data class PatchClassification(
    val sourceRatio: SourceRatio,
    val form: PatchForm,
    val ratioSemantics: RatioSemantics,
)

@Serializable
internal enum class SourceRatio {
    NATIVE_16_9,
    NATIVE_16_10,
}

@Serializable
internal enum class PatchForm {
    MIXED_CODE_AND_DATA,
    CONDITIONAL_DATA_WRITES,
}

@Serializable
internal enum class RatioSemantics {
    MULTI_CONSTANT_RATIO,
    DIRECT_RATIO_Q12,
}

@Serializable
internal data class ConversionRecipe(
    val id: String,
    val sourceRef: String,
    val sourcePatchSha256: String,
    val sourceRatio: CanonicalRatio,
    val targetRatio: CanonicalRatio,
    val q12Scale: Int,
    val rounding: ConversionRounding,
    val expectedSourceValueHex: String,
    val expectedTargetValueHex: String,
    val calculation: String,
    val context: ConversionContext,
    val resultPatchSha256: String,
)

@Serializable
internal enum class ConversionRounding {
    NEAREST,
}

@Serializable
internal data class ConversionContext(
    val lineNumber: Int,
    val before: String,
    val after: String,
    val expectedModificationCount: Int,
)

@Serializable
internal data class ProfileValidation(
    val identity: IdentityValidation,
    val ar: ActionReplayValidation,
    val ratio: RatioValidation,
    val targetScreen: TargetScreenValidation,
    val conversion: ConversionValidation,
    val compatibility: CompatibilityValidation,
    val trust: TrustValidation,
    val activation: ActivationValidation,
)

@Serializable
internal enum class IdentityValidation {
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED,
}

@Serializable
internal enum class ActionReplayValidation {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN,
}

@Serializable
internal enum class RatioValidation {
    UNDERSTOOD,
    NEEDS_REVIEW,
    UNKNOWN,
}

@Serializable
internal enum class TargetScreenValidation {
    RESOLVED,
    INFERRED,
    UNRESOLVED,
}

@Serializable
internal enum class ConversionValidation {
    NOT_NEEDED,
    SAFE_RECIPE_APPLIED,
    NEEDS_REVIEW,
}

@Serializable
internal enum class CompatibilityValidation {
    NO_KNOWN_INCOMPATIBILITY,
    KNOWN_INCOMPATIBILITY,
    UNKNOWN,
}

@Serializable
internal enum class TrustValidation {
    UNVALIDATED,
    SOURCE_VALIDATED,
    RUNTIME_VALIDATED,
}

@Serializable
internal enum class ActivationValidation {
    DISABLED,
    APPROVED,
}

internal data class ValidatedWidescreenManifests(
    val sources: WidescreenSourcesManifest,
    val profiles: WidescreenProfilesManifest,
)

internal class WidescreenManifestValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal object WidescreenManifestValidator {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
    }
    private val idPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val gameCodePattern = Regex("^[A-Z0-9]{4}$")
    private val checksum32Pattern = Regex("^[0-9A-F]{8}$")
    private val checksum16Pattern = Regex("^[0-9A-F]{4}$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val actionReplayLinePattern = Regex("^[0-9A-F]{8} [0-9A-F]{8}$")

    fun parseAndValidate(
        sourcesJson: String,
        profilesJson: String,
    ): ValidatedWidescreenManifests {
        val sources = decode("widescreen_sources.json") {
            json.decodeFromString<WidescreenSourcesManifest>(sourcesJson)
        }
        val profiles = decode("widescreen_profiles.json") {
            json.decodeFromString<WidescreenProfilesManifest>(profilesJson)
        }
        return validate(sources, profiles)
    }

    fun validate(
        sources: WidescreenSourcesManifest,
        profiles: WidescreenProfilesManifest,
    ): ValidatedWidescreenManifests {
        requireManifest(sources.schemaVersion == WIDESCREEN_SCHEMA_VERSION) {
            "Unsupported sources schemaVersion ${sources.schemaVersion}"
        }
        requireManifest(profiles.schemaVersion == WIDESCREEN_SCHEMA_VERSION) {
            "Unsupported profiles schemaVersion ${profiles.schemaVersion}"
        }
        validateRomIdentityDefinition(profiles.romIdentity)
        validateSources(sources.sources)
        validateProfiles(profiles.profiles, sources.sources.associateBy { it.id })
        return ValidatedWidescreenManifests(sources, profiles)
    }

    private fun validateRomIdentityDefinition(identity: RomIdentityDefinition) {
        requireManifest(identity.algorithm == RomIdentityAlgorithm.GAMECODE_PLUS_USRCHEAT_HEADER_CHECKSUM_V1) {
            "Unsupported ROM identity algorithm ${identity.algorithm}"
        }
        requireManifest(
            identity.gameCode == GameCodeDefinition(
                offsetBytes = 12,
                lengthBytes = 4,
                encoding = "ASCII",
                caseSensitive = true,
            ),
        ) { "Unexpected gameCode definition" }
        requireManifest(
            identity.headerChecksum32 == HeaderChecksum32Definition(
                inputOffsetBytes = 0,
                inputLengthBytes = 0x200,
                implementation = "RomProcessor/Crc32",
                initialValueHex = "FFFFFFFF",
                reflectedPolynomialHex = "EDB88320",
                finalXor = false,
                outputFormat = "UPPERCASE_8_HEX",
            ),
        ) { "Unexpected headerChecksum32 definition" }
    }

    private fun validateSources(sources: List<WidescreenSourceRecord>) {
        requireManifest(sources.map { it.id }.distinct().size == sources.size) {
            "Duplicate source id"
        }
        requireManifest(sources == sources.sortedBy { it.id }) { "Sources are not sorted by id" }

        sources.forEach { source ->
            requireManifest(idPattern.matches(source.id)) { "Invalid source id ${source.id}" }
            requireManifest(source.title.isNotBlank()) { "Blank source title for ${source.id}" }
            requireManifest(source.locator.url.isNotBlank()) { "Blank source URL for ${source.id}" }
            requireManifest(source.patchLicense.isNotBlank()) { "Blank patch license for ${source.id}" }
            source.locator.commit?.let {
                requireManifest(Regex("^[0-9a-f]{40}$").matches(it)) {
                    "Invalid source commit for ${source.id}"
                }
            }
            if (source.kind == SourceKind.GIT_ARTIFACT) {
                requireManifest(
                    source.locator.version?.isNotBlank() == true &&
                        source.locator.commit != null &&
                        source.locator.path?.isNotBlank() == true &&
                        source.artifact != null,
                ) { "Incomplete git artifact source ${source.id}" }
            }
            source.artifact?.let { validatePatch(it, "source ${source.id}") }
        }
    }

    private fun validateProfiles(
        profiles: List<CanonicalWidescreenProfile>,
        sourcesById: Map<String, WidescreenSourceRecord>,
    ) {
        requireManifest(profiles.map { it.id }.distinct().size == profiles.size) {
            "Duplicate profile id"
        }

        profiles.forEach { profile -> validateProfile(profile, sourcesById) }

        val activeDuplicate = profiles
            .filter { it.validation.activation == ActivationValidation.APPROVED }
            .groupBy { it.rom.gameCode to it.rom.headerChecksum32 }
            .entries
            .firstOrNull { it.value.size > 1 }
        requireManifest(activeDuplicate == null) {
            "Duplicate active ROM key ${activeDuplicate?.key}"
        }

        val canonicalOrder = compareBy<CanonicalWidescreenProfile>(
            { it.rom.gameCode },
            { it.rom.headerChecksum32.toULong(16) },
            { it.id },
        )
        requireManifest(profiles == profiles.sortedWith(canonicalOrder)) {
            "Profiles are not sorted by gameCode, unsigned checksum, id"
        }
    }

    private fun validateProfile(
        profile: CanonicalWidescreenProfile,
        sourcesById: Map<String, WidescreenSourceRecord>,
    ) {
        requireManifest(idPattern.matches(profile.id)) { "Invalid profile id ${profile.id}" }
        requireManifest(gameCodePattern.matches(profile.rom.gameCode)) {
            "Invalid gameCode for ${profile.id}"
        }
        requireManifest(checksum32Pattern.matches(profile.rom.headerChecksum32)) {
            "Invalid headerChecksum32 for ${profile.id}"
        }
        profile.upstreamIdentity?.let {
            requireManifest(it.namespace.isNotBlank()) { "Blank upstream namespace for ${profile.id}" }
            requireManifest(gameCodePattern.matches(it.gameCode)) {
                "Invalid upstream gameCode for ${profile.id}"
            }
            requireManifest(checksum16Pattern.matches(it.headerChecksum16)) {
                "Invalid upstream headerChecksum16 for ${profile.id}"
            }
        }
        validatePatch(profile.patch, "profile ${profile.id}")
        requireManifest(profile.sourceRefs.isNotEmpty()) { "No sourceRef for ${profile.id}" }
        requireManifest(profile.sourceRefs.distinct().size == profile.sourceRefs.size) {
            "Duplicate sourceRef for ${profile.id}"
        }
        profile.sourceRefs.forEach {
            requireManifest(it in sourcesById) { "Unknown sourceRef $it for ${profile.id}" }
        }

        when (profile.validation.conversion) {
            ConversionValidation.NOT_NEEDED -> requireManifest(profile.conversion == null) {
                "Unexpected conversion recipe for ${profile.id}"
            }

            ConversionValidation.SAFE_RECIPE_APPLIED,
            ConversionValidation.NEEDS_REVIEW,
            -> requireManifest(profile.conversion != null) {
                "Missing conversion recipe for ${profile.id}"
            }
        }
        profile.conversion?.let { validateConversion(profile, it, sourcesById) }
        validateActivation(profile, sourcesById)
    }

    private fun validateActivation(
        profile: CanonicalWidescreenProfile,
        sourcesById: Map<String, WidescreenSourceRecord>,
    ) {
        if (profile.validation.activation != ActivationValidation.APPROVED) return

        requireManifest(profile.validation.identity == IdentityValidation.RESOLVED) {
            "APPROVED profile ${profile.id} has unresolved identity"
        }
        requireManifest(profile.validation.ar == ActionReplayValidation.SUPPORTED) {
            "APPROVED profile ${profile.id} has unsupported AR"
        }
        requireManifest(profile.validation.ratio == RatioValidation.UNDERSTOOD) {
            "APPROVED profile ${profile.id} has unresolved ratio"
        }
        requireManifest(
            profile.targetScreen != CanonicalTargetScreen.UNRESOLVED &&
                profile.validation.targetScreen == TargetScreenValidation.RESOLVED,
        ) { "APPROVED profile ${profile.id} has unresolved targetScreen" }
        requireManifest(profile.validation.conversion != ConversionValidation.NEEDS_REVIEW) {
            "APPROVED profile ${profile.id} has conversion needing review"
        }
        requireManifest(
            profile.validation.compatibility == CompatibilityValidation.NO_KNOWN_INCOMPATIBILITY,
        ) { "APPROVED profile ${profile.id} has unresolved compatibility" }
        requireManifest(
            profile.validation.trust == TrustValidation.SOURCE_VALIDATED ||
                profile.validation.trust == TrustValidation.RUNTIME_VALIDATED,
        ) { "APPROVED profile ${profile.id} has incompatible trust" }

        if (profile.validation.trust == TrustValidation.SOURCE_VALIDATED) {
            requireManifest(profile.validation.conversion == ConversionValidation.NOT_NEEDED) {
                "SOURCE_VALIDATED profile ${profile.id} cannot use a local conversion"
            }
            requireManifest(
                profile.sourceRefs.any {
                    sourcesById.getValue(it).let { source ->
                        source.provenance == SourceProvenance.EXACT &&
                            source.artifact?.sha256 == profile.patch.sha256
                    }
                },
            ) { "SOURCE_VALIDATED profile ${profile.id} lacks an exact hashed source" }
        }

        if (profile.validation.conversion == ConversionValidation.SAFE_RECIPE_APPLIED) {
            requireManifest(profile.validation.trust == TrustValidation.RUNTIME_VALIDATED) {
                "Converted profile ${profile.id} must be RUNTIME_VALIDATED"
            }
        }
    }

    private fun validatePatch(patch: ActionReplayPatch, owner: String) {
        requireManifest(patch.actionReplayLines.isNotEmpty()) { "Empty patch for $owner" }
        patch.actionReplayLines.forEachIndexed { index, line ->
            requireManifest(actionReplayLinePattern.matches(line)) {
                "Malformed AR line ${index + 1} for $owner"
            }
        }
        requireManifest(patch.byteLength == patch.actionReplayLines.size * 8) {
            "Incorrect patch byteLength for $owner"
        }
        requireSha256(patch.sha256, "$owner patch")
        requireManifest(patch.sha256 == sha256OfActionReplayLines(patch.actionReplayLines)) {
            "Patch SHA-256 mismatch for $owner"
        }
    }

    private fun validateConversion(
        profile: CanonicalWidescreenProfile,
        conversion: ConversionRecipe,
        sourcesById: Map<String, WidescreenSourceRecord>,
    ) {
        requireManifest(conversion.id == "direct-q12-aspect-v1") {
            "Unsupported conversion recipe ${conversion.id}"
        }
        requireManifest(conversion.sourceRef in profile.sourceRefs) {
            "Conversion sourceRef is not attached to ${profile.id}"
        }
        val sourcePatch = sourcesById[conversion.sourceRef]?.artifact
        requireManifest(sourcePatch != null) { "Conversion source patch is missing for ${profile.id}" }
        sourcePatch ?: return

        requireSha256(conversion.sourcePatchSha256, "conversion source")
        requireSha256(conversion.resultPatchSha256, "conversion result")
        requireManifest(conversion.sourcePatchSha256 == sourcePatch.sha256) {
            "Conversion source hash mismatch for ${profile.id}"
        }
        requireManifest(conversion.resultPatchSha256 == profile.patch.sha256) {
            "Conversion result hash mismatch for ${profile.id}"
        }
        requireManifest(conversion.targetRatio == profile.targetRatio) {
            "Conversion target ratio mismatch for ${profile.id}"
        }
        requireManifest(
            (profile.classification.sourceRatio == SourceRatio.NATIVE_16_9 &&
                conversion.sourceRatio == CanonicalRatio.RATIO_16_9) ||
                (profile.classification.sourceRatio == SourceRatio.NATIVE_16_10 &&
                    conversion.sourceRatio == CanonicalRatio.RATIO_16_10),
        ) { "Conversion source ratio mismatch for ${profile.id}" }
        requireManifest(conversion.q12Scale == 4096) { "Unexpected Q12 scale for ${profile.id}" }
        requireManifest(conversion.rounding == ConversionRounding.NEAREST) {
            "Unexpected Q12 rounding for ${profile.id}"
        }
        requireManifest(checksum16Pattern.matches(conversion.expectedSourceValueHex)) {
            "Invalid source Q12 value for ${profile.id}"
        }
        requireManifest(checksum16Pattern.matches(conversion.expectedTargetValueHex)) {
            "Invalid target Q12 value for ${profile.id}"
        }
        requireManifest(
            conversion.expectedSourceValueHex == q12Hex(conversion.sourceRatio, conversion.q12Scale),
        ) { "Source Q12 value mismatch for ${profile.id}" }
        requireManifest(
            conversion.expectedTargetValueHex == q12Hex(conversion.targetRatio, conversion.q12Scale),
        ) { "Target Q12 value mismatch for ${profile.id}" }

        val context = conversion.context
        requireManifest(context.expectedModificationCount == 1) {
            "Conversion must modify exactly one line for ${profile.id}"
        }
        requireManifest(actionReplayLinePattern.matches(context.before)) {
            "Malformed conversion before-line for ${profile.id}"
        }
        requireManifest(actionReplayLinePattern.matches(context.after)) {
            "Malformed conversion after-line for ${profile.id}"
        }
        val lineIndex = context.lineNumber - 1
        requireManifest(lineIndex in sourcePatch.actionReplayLines.indices) {
            "Conversion lineNumber is out of range for ${profile.id}"
        }
        requireManifest(sourcePatch.actionReplayLines[lineIndex] == context.before) {
            "Conversion source context mismatch for ${profile.id}"
        }

        val beforeWords = context.before.split(' ')
        val afterWords = context.after.split(' ')
        requireManifest(
            beforeWords[0] == afterWords[0] &&
                beforeWords[1].dropLast(4) == afterWords[1].dropLast(4) &&
                beforeWords[1].takeLast(4) == conversion.expectedSourceValueHex &&
                afterWords[1].takeLast(4) == conversion.expectedTargetValueHex,
        ) { "Conversion is not the declared contextual Q12 change for ${profile.id}" }

        val convertedLines = sourcePatch.actionReplayLines.toMutableList().apply {
            this[lineIndex] = context.after
        }
        val modificationCount = sourcePatch.actionReplayLines.zip(convertedLines).count { (before, after) ->
            before != after
        }
        requireManifest(modificationCount == context.expectedModificationCount) {
            "Conversion modification count mismatch for ${profile.id}"
        }
        requireManifest(convertedLines == profile.patch.actionReplayLines) {
            "Converted patch differs from final patch for ${profile.id}"
        }
    }

    private fun q12Hex(ratio: CanonicalRatio, scale: Int): String {
        val rounded = (ratio.width.toLong() * scale + ratio.height / 2) / ratio.height
        return rounded.toString(16).uppercase().padStart(4, '0')
    }

    private fun sha256OfActionReplayLines(lines: List<String>): String {
        val bytes = ByteArray(lines.size * 8)
        var offset = 0
        lines.forEach { line ->
            line.split(' ').forEach { word ->
                val value = word.toLong(16)
                repeat(4) { byteIndex ->
                    bytes[offset++] = (value shr (byteIndex * 8)).toByte()
                }
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun requireSha256(value: String, owner: String) {
        requireManifest(sha256Pattern.matches(value)) { "Malformed SHA-256 for $owner" }
    }

    private inline fun <T> decode(name: String, block: () -> T): T {
        return try {
            block()
        } catch (exception: SerializationException) {
            throw WidescreenManifestValidationException("Invalid JSON or schema in $name", exception)
        }
    }

    private inline fun requireManifest(condition: Boolean, message: () -> String) {
        if (!condition) throw WidescreenManifestValidationException(message())
    }
}
