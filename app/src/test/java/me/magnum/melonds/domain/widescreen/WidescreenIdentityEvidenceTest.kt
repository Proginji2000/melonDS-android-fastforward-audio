package me.magnum.melonds.domain.widescreen

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidescreenIdentityEvidenceTest {
    private val manifestJson by lazy {
        checkNotNull(javaClass.classLoader?.getResource("widescreen_identity_evidence.json")) {
            "Missing widescreen_identity_evidence.json test resource"
        }.readText(UTF_8)
    }
    private val manifest by lazy { WidescreenIdentityEvidenceValidator.parseAndValidate(manifestJson) }

    @Test
    fun productionManifestContainsOnlyTheTwentyTwoRatifiedEvidenceRecords() {
        assertEquals(1, manifest.schemaVersion)
        assertEquals(
            UpstreamHeaderCrc16Algorithm.TWILIGHT_WIDESCREEN_STORED_HEADER_CRC16_V1,
            manifest.algorithms.upstreamHeaderCrc16,
        )
        assertEquals(
            RomIdentityAlgorithm.GAMECODE_PLUS_USRCHEAT_HEADER_CHECKSUM_V1,
            manifest.algorithms.headerChecksum32,
        )
        assertEquals(22, manifest.records.size)
        assertEquals(21, manifest.records.count { it.type == IdentityEvidenceType.LOCAL_ROM_HEADER })
        assertEquals(1, manifest.records.count { it.type == IdentityEvidenceType.RUNTIME_OBSERVED })
        assertEquals(expectedLocalTriplets, manifest.records.filter {
            it.type == IdentityEvidenceType.LOCAL_ROM_HEADER
        }.map { "${it.gameCode}/${it.upstreamHeaderCrc16}/${it.headerChecksum32}" })
    }

    @Test
    fun pokemonHasTwoConcordantEvidenceRecordsAndSuperMarioHasOnlyLocalEvidence() {
        val pokemon = manifest.records.filter { it.gameCode == "IRAF" && it.upstreamHeaderCrc16 == "BC1D" }
        val superMario = manifest.records.filter { it.gameCode == "ASMP" && it.upstreamHeaderCrc16 == "477C" }

        assertEquals(listOf(IdentityEvidenceType.LOCAL_ROM_HEADER, IdentityEvidenceType.RUNTIME_OBSERVED), pokemon.map { it.type })
        assertEquals(setOf("031EF208"), pokemon.map { it.headerChecksum32 }.toSet())
        assertEquals(
            "runtime-validation:pokemon-white-fra-v1-0",
            pokemon.single { it.type == IdentityEvidenceType.RUNTIME_OBSERVED }.sourceRef,
        )
        assertEquals(listOf(IdentityEvidenceType.LOCAL_ROM_HEADER), superMario.map { it.type })
        assertEquals("D3D9F14A", superMario.single().headerChecksum32)
    }

    @Test
    fun validatorRejectsUnknownAlgorithmsTypesDuplicatesWildcardsAndNonCanonicalOrder() {
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.parseAndValidate(
                manifestJson.replace(
                    "TWILIGHT_WIDESCREEN_STORED_HEADER_CRC16_V1",
                    "UNKNOWN_HEADER_CRC16",
                ),
            )
        }
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.parseAndValidate(
                manifestJson.replaceFirst("LOCAL_ROM_HEADER", "UNKNOWN_EVIDENCE"),
            )
        }
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.validate(
                manifest.copy(records = manifest.records + manifest.records.last()),
            )
        }
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.validate(
                manifest.copy(
                    records = listOf(
                        manifest.records.first().copy(
                            id = "local-rom-header-adaf-ffff-f23beadc",
                            upstreamHeaderCrc16 = "FFFF",
                            sourceRef = "local-rom-header:ADAF:FFFF:F23BEADC",
                        ),
                    ),
                ),
            )
        }
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.validate(manifest.copy(records = manifest.records.reversed()))
        }
        assertThrows(WidescreenIdentityEvidenceValidationException::class.java) {
            WidescreenIdentityEvidenceValidator.validate(
                manifest.copy(records = listOf(manifest.records.first().copy(sourceRef = "C:\\Roms\\game.nds"))),
            )
        }
    }

    @Test
    fun structurallyValidDivergentEvidenceIsAcceptedForResolverConflictHandling() {
        val first = manifest.records.first()
        val second = first.copy(
            id = "local-rom-header-adaf-9c75-ffffffff",
            headerChecksum32 = "FFFFFFFF",
            sourceRef = "local-rom-header:ADAF:9C75:FFFFFFFF",
        )

        assertEquals(
            listOf(first, second),
            WidescreenIdentityEvidenceValidator.validate(manifest.copy(records = listOf(first, second))).records,
        )
    }

    @Test
    fun manifestIsLfTerminatedAndContainsNoLocalRomMetadata() {
        assertTrue(manifestJson.endsWith('\n'))
        assertFalse(manifestJson.contains('\r'))
        val lowercase = manifestJson.lowercase(Locale.ROOT)
        listOf(
            "ayn thor",
            "/storage/",
            "/sdcard/",
            ".nds",
            ".srl",
            "filename",
            "filepath",
            "romsize",
            "rawheader",
            "headersha256",
            "a8tp",
            "cpue",
        ).forEach { forbidden -> assertFalse("Unexpected local metadata: $forbidden", lowercase.contains(forbidden)) }
    }

    private val expectedLocalTriplets = listOf(
        "ADAF/9C75/F23BEADC",
        "AJRP/422F/A37FF7D0",
        "APAF/E127/C89A40B6",
        "ASMP/477C/D3D9F14A",
        "AY9P/FCA9/DBFFAB14",
        "CJRP/8FD5/2E608754",
        "CPUF/77CC/D8380D9F",
        "IPGF/4D6A/E1041645",
        "IPKF/4291/F16A1F7B",
        "IRAF/BC1D/031EF208",
        "IRAO/F6BF/0F0875FE",
        "IRBF/09E4/BA565122",
        "IRBO/8485/106820A5",
        "IRDF/234D/6C54D079",
        "IRDO/D75C/012AF769",
        "IREF/5A30/66465E3D",
        "IREO/7680/8E4C1CD6",
        "YDQP/F9CC/AE7BD3F6",
        "YIVP/A6ED/8F926566",
        "YV5P/8E5E/9D159278",
        "YVIP/11DD/D7EF3686",
    )
}
