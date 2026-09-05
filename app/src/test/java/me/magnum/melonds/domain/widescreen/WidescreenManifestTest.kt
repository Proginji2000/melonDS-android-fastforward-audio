package me.magnum.melonds.domain.widescreen

import me.magnum.melonds.domain.model.RomInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WidescreenManifestTest {
    private val sourcesJson by lazy { resourceText("widescreen_sources.json") }
    private val profilesJson by lazy { resourceText("widescreen_profiles.json") }
    private val canonical by lazy {
        WidescreenManifestValidator.parseAndValidate(sourcesJson, profilesJson)
    }

    @Test
    fun canonicalManifestsAreValidAndDeterministicallyOrdered() {
        assertEquals(WIDESCREEN_SCHEMA_VERSION, canonical.sources.schemaVersion)
        assertEquals(WIDESCREEN_SCHEMA_VERSION, canonical.profiles.schemaVersion)
        assertEquals(
            canonical.sources.sources.sortedBy { it.id },
            canonical.sources.sources,
        )
        assertEquals(
            listOf("ASMP", "IRAF"),
            canonical.profiles.profiles.map { it.rom.gameCode },
        )
    }

    @Test
    fun romIdentityAlgorithmAndReferenceVectorsAreExact() {
        val identity = canonical.profiles.romIdentity
        assertEquals(
            RomIdentityAlgorithm.GAMECODE_PLUS_USRCHEAT_HEADER_CHECKSUM_V1,
            identity.algorithm,
        )
        assertEquals(0x200, identity.headerChecksum32.inputLengthBytes)
        assertEquals("RomProcessor/Crc32", identity.headerChecksum32.implementation)
        assertEquals("FFFFFFFF", identity.headerChecksum32.initialValueHex)
        assertEquals("EDB88320", identity.headerChecksum32.reflectedPolynomialHex)
        assertEquals(false, identity.headerChecksum32.finalXor)
        assertEquals(
            mapOf(
                "ASMP" to "D3D9F14A",
                "IRAF" to "031EF208",
            ),
            canonical.profiles.profiles.associate { it.rom.gameCode to it.rom.headerChecksum32 },
        )
    }

    @Test
    fun canonicalProfilesExactlyMatchCurrentRuntimeProfiles() {
        val runtimeProfiles = currentRuntimeProfiles()
        assertEquals(2, canonical.profiles.profiles.size)
        assertEquals(
            canonical.profiles.profiles.map { it.id },
            runtimeProfiles.map { it.id },
        )

        canonical.profiles.profiles.forEach { expected ->
            val runtime = requireNotNull(
                AutoWidescreen.resolve(
                    RomInfo(
                        gameCode = expected.rom.gameCode,
                        headerChecksum = expected.rom.headerChecksum32.toUInt(16),
                        gameTitle = "manifest parity test",
                        gameName = "manifest parity test",
                    ),
                ),
            )

            assertEquals(expected.id, runtime.id)
            assertEquals(expected.rom.gameCode, runtime.romKey.gameCode)
            assertEquals(expected.rom.headerChecksum32.toUInt(16), runtime.romKey.headerChecksum)
            assertEquals(expected.targetRatio.toRuntimeRatio(), runtime.targetRatio)
            assertEquals(expected.targetScreen.toRuntimeTargetScreen(), runtime.targetScreen)
            assertEquals(expected.patch.actionReplayLines, runtime.actionReplayCode.toLines())
        }
    }

    @Test
    fun bothExistingProfilesKeepRuntimeValidatedApproval() {
        assertEquals(
            setOf(TrustValidation.RUNTIME_VALIDATED),
            canonical.profiles.profiles.map { it.validation.trust }.toSet(),
        )
        assertEquals(
            setOf(ActivationValidation.APPROVED),
            canonical.profiles.profiles.map { it.validation.activation }.toSet(),
        )
    }

    @Test
    fun exactNativeSourceCanBeApprovedWithoutRuntimeValidation() {
        val pokemon = canonical.profiles.profiles.last()
        val sourcePatch = requireNotNull(
            canonical.sources.sources.single { it.id == pokemon.sourceRefs.single() }.artifact,
        )
        val sourceValidatedProfile = pokemon.copy(
            id = "source-validated-native-test-profile",
            rom = pokemon.rom.copy(headerChecksum32 = "031EF209"),
            targetRatio = CanonicalRatio.RATIO_16_10,
            patch = sourcePatch,
            conversion = null,
            validation = pokemon.validation.copy(
                conversion = ConversionValidation.NOT_NEEDED,
                trust = TrustValidation.SOURCE_VALIDATED,
            ),
        )

        WidescreenManifestValidator.validate(
            canonical.sources,
            canonical.profiles.copy(
                profiles = canonical.profiles.profiles + sourceValidatedProfile,
            ),
        )
    }

    @Test
    fun pokemonConversionIsExactContextualAndHashPinned() {
        val pokemon = canonical.profiles.profiles.single { it.id == "pokemon-white-fra-v1-0" }
        val conversion = requireNotNull(pokemon.conversion)
        val source = canonical.sources.sources.single { it.id == conversion.sourceRef }

        assertEquals("IRAF", pokemon.upstreamIdentity?.gameCode)
        assertEquals("BC1D", pokemon.upstreamIdentity?.headerChecksum16)
        assertEquals("direct-q12-aspect-v1", conversion.id)
        assertEquals("122822A8 0000199A", conversion.context.before)
        assertEquals("122822A8 00001C72", conversion.context.after)
        assertEquals(1, conversion.context.expectedModificationCount)
        assertEquals(
            "74f06acc1af52765f1e87a4dd40473e7dc091c785a71eca5621205694e7de8ca",
            source.artifact?.sha256,
        )
        assertEquals(
            "8de30d3a79c20f8dcb7c0390e246dbd6c355adf3ea9b6fa9d0edfdfddf4abad1",
            pokemon.patch.sha256,
        )
    }

    @Test
    fun invalidJsonUnknownSchemaAlgorithmAndRatioAreRejected() {
        assertRejected("invalid JSON") {
            WidescreenManifestValidator.parseAndValidate("{", profilesJson)
        }
        assertRejected("unknown schemaVersion") {
            validate(sources = canonical.sources.copy(schemaVersion = 2))
        }
        assertRejected("unknown identity algorithm") {
            WidescreenManifestValidator.parseAndValidate(
                sourcesJson,
                profilesJson.replace(
                    "GAMECODE_PLUS_USRCHEAT_HEADER_CHECKSUM_V1",
                    "CRC32_STANDARD",
                ),
            )
        }
        assertRejected("unknown ratio") {
            WidescreenManifestValidator.parseAndValidate(
                sourcesJson,
                profilesJson.replaceFirst("\"targetRatio\": \"16:9\"", "\"targetRatio\": \"4:3\""),
            )
        }
    }

    @Test
    fun duplicateIdsActiveRomKeysAndNonCanonicalOrderAreRejected() {
        val profiles = canonical.profiles.profiles
        val sources = canonical.sources.sources

        assertRejected("duplicate source id") {
            validate(sources = canonical.sources.copy(sources = sources + sources.first()))
        }
        assertRejected("duplicate profile id") {
            validate(
                profiles = replaceProfile(1) { it.copy(id = profiles.first().id) },
            )
        }
        assertRejected("duplicate active ROM key") {
            validate(
                profiles = canonical.profiles.copy(
                    profiles = listOf(
                        profiles.first(),
                        profiles.first().copy(id = "super-mario-64-ds-eur-v1-0-duplicate"),
                        profiles.last(),
                    ),
                ),
            )
        }
        assertRejected("unsorted sources") {
            validate(sources = canonical.sources.copy(sources = sources.reversed()))
        }
        assertRejected("unsorted profiles") {
            validate(profiles = canonical.profiles.copy(profiles = profiles.reversed()))
        }
        assertRejected("checksum order is unsigned") {
            val lowerChecksum = profiles.first().copy(
                id = "lower-checksum-test-profile",
                rom = profiles.first().rom.copy(headerChecksum32 = "031EF208"),
                validation = profiles.first().validation.copy(activation = ActivationValidation.DISABLED),
            )
            validate(
                profiles = canonical.profiles.copy(
                    profiles = listOf(profiles.first(), lowerChecksum, profiles.last()),
                ),
            )
        }
        assertRejected("id is the final ordering key") {
            val earlierId = profiles.first().copy(
                id = "earlier-id-test-profile",
                validation = profiles.first().validation.copy(activation = ActivationValidation.DISABLED),
            )
            validate(
                profiles = canonical.profiles.copy(
                    profiles = listOf(profiles.first(), earlierId, profiles.last()),
                ),
            )
        }
    }

    @Test
    fun malformedProfileFieldsAreRejected() {
        val mario = canonical.profiles.profiles.first()
        val cases = listOf(
            "invalid gameCode" to mario.copy(rom = mario.rom.copy(gameCode = "ASM")),
            "invalid checksum" to mario.copy(rom = mario.rom.copy(headerChecksum32 = "D3D9F14")),
            "empty patch" to mario.copy(
                patch = mario.patch.copy(byteLength = 0, actionReplayLines = emptyList()),
            ),
            "malformed AR line" to mario.copy(
                patch = mario.patch.copy(
                    actionReplayLines = mario.patch.actionReplayLines.toMutableList().apply {
                        this[0] = "520209C4  E59D0094"
                    },
                ),
            ),
            "unknown sourceRef" to mario.copy(sourceRefs = listOf("missing-source")),
            "malformed SHA-256" to mario.copy(patch = mario.patch.copy(sha256 = "xyz")),
        )

        cases.forEach { (label, profile) ->
            assertRejected(label) { validate(profiles = replaceProfile(0) { profile }) }
        }

        val twilightSourceIndex = canonical.sources.sources.indexOfFirst {
            it.id == "twilightmenu-v27-24-1-iraf-bc1d"
        }
        assertRejected("malformed source SHA-256") {
            validate(
                sources = replaceSource(twilightSourceIndex) { source ->
                    source.copy(artifact = requireNotNull(source.artifact).copy(sha256 = "0"))
                },
            )
        }
    }

    @Test
    fun approvedProfilesMustPassEveryTrustGate() {
        val mario = canonical.profiles.profiles.first()
        val pokemon = canonical.profiles.profiles.last()
        val cases = listOf(
            "unresolved identity" to mario.copy(
                validation = mario.validation.copy(identity = IdentityValidation.UNRESOLVED),
            ),
            "unsupported AR" to mario.copy(
                validation = mario.validation.copy(ar = ActionReplayValidation.PARTIAL),
            ),
            "unresolved ratio" to mario.copy(
                validation = mario.validation.copy(ratio = RatioValidation.NEEDS_REVIEW),
            ),
            "unresolved target screen" to mario.copy(targetScreen = CanonicalTargetScreen.UNRESOLVED),
            "known incompatibility" to mario.copy(
                validation = mario.validation.copy(
                    compatibility = CompatibilityValidation.KNOWN_INCOMPATIBILITY,
                ),
            ),
            "unvalidated trust" to mario.copy(
                validation = mario.validation.copy(trust = TrustValidation.UNVALIDATED),
            ),
            "conversion needing review" to pokemon.copy(
                validation = pokemon.validation.copy(conversion = ConversionValidation.NEEDS_REVIEW),
            ),
            "converted source-only trust" to pokemon.copy(
                validation = pokemon.validation.copy(trust = TrustValidation.SOURCE_VALIDATED),
            ),
        )

        cases.forEach { (label, profile) ->
            val index = canonical.profiles.profiles.indexOfFirst { it.id == profile.id }
            assertRejected(label) { validate(profiles = replaceProfile(index) { profile }) }
        }
    }

    @Test
    fun manifestArDeclarationMustMatchCalculatedCapabilityAndApproval() {
        val mario = canonical.profiles.profiles.first()
        val partialPatch = mario.patch.copy(
            byteLength = 16,
            sha256 = "f3e5fe699acf4b1ade572c7cb7c7acc4765f10c1e5183097c3f1bc2d5546757c",
            actionReplayLines = listOf(
                "C5000000 00000000",
                "D0000000 00000000",
            ),
        )
        val partialProfile = mario.copy(
            patch = partialPatch,
            validation = mario.validation.copy(
                ar = ActionReplayValidation.PARTIAL,
                activation = ActivationValidation.DISABLED,
            ),
        )
        validate(profiles = replaceProfile(0) { partialProfile })

        assertRejected("SUPPORTED declaration for calculated partial AR") {
            validate(
                profiles = replaceProfile(0) {
                    partialProfile.copy(
                        validation = partialProfile.validation.copy(
                            ar = ActionReplayValidation.SUPPORTED,
                        ),
                    )
                },
            )
        }
        assertRejected("APPROVED profile with calculated partial AR") {
            validate(
                profiles = replaceProfile(0) {
                    partialProfile.copy(
                        validation = partialProfile.validation.copy(
                            activation = ActivationValidation.APPROVED,
                        ),
                    )
                },
            )
        }

        val unsupportedProfile = mario.copy(
            patch = mario.patch.copy(
                byteLength = 8,
                sha256 = "993c93b841e8b9faabfbb230b6e7aaf6a18516ec7219433d075f2dd93b7c934e",
                actionReplayLines = listOf("C4000000 00000000"),
            ),
            validation = mario.validation.copy(
                ar = ActionReplayValidation.UNSUPPORTED,
                activation = ActivationValidation.DISABLED,
            ),
        )
        validate(profiles = replaceProfile(0) { unsupportedProfile })
    }

    @Test
    fun conversionRecipeRejectsAnyContextOrHashDrift() {
        val pokemon = canonical.profiles.profiles.last()
        val conversion = requireNotNull(pokemon.conversion)
        val cases = listOf(
            "wrong source context" to conversion.copy(
                context = conversion.context.copy(before = "122822A8 00001999"),
            ),
            "more than one expected modification" to conversion.copy(
                context = conversion.context.copy(expectedModificationCount = 2),
            ),
            "wrong result hash" to conversion.copy(resultPatchSha256 = "0".repeat(64)),
            "wrong derived Q12" to conversion.copy(expectedTargetValueHex = "1C71"),
        )

        cases.forEach { (label, changedConversion) ->
            assertRejected(label) {
                validate(
                    profiles = replaceProfile(1) {
                        it.copy(conversion = changedConversion)
                    },
                )
            }
        }
    }

    private fun validate(
        sources: WidescreenSourcesManifest = canonical.sources,
        profiles: WidescreenProfilesManifest = canonical.profiles,
    ) {
        WidescreenManifestValidator.validate(sources, profiles)
    }

    private fun replaceProfile(
        index: Int,
        transform: (CanonicalWidescreenProfile) -> CanonicalWidescreenProfile,
    ): WidescreenProfilesManifest {
        return canonical.profiles.copy(
            profiles = canonical.profiles.profiles.toMutableList().apply {
                this[index] = transform(this[index])
            },
        )
    }

    private fun replaceSource(
        index: Int,
        transform: (WidescreenSourceRecord) -> WidescreenSourceRecord,
    ): WidescreenSourcesManifest {
        return canonical.sources.copy(
            sources = canonical.sources.sources.toMutableList().apply {
                this[index] = transform(this[index])
            },
        )
    }

    private fun resourceText(name: String): String {
        return requireNotNull(javaClass.classLoader?.getResource(name)) {
            "Missing test resource $name"
        }.readText()
    }

    private fun CanonicalRatio.toRuntimeRatio(): WidescreenRatio {
        return when (this) {
            CanonicalRatio.RATIO_16_9 -> WidescreenRatio.RATIO_16_9
            CanonicalRatio.RATIO_16_10 -> WidescreenRatio.RATIO_16_10
        }
    }

    private fun CanonicalTargetScreen.toRuntimeTargetScreen(): WidescreenTargetScreen {
        return when (this) {
            CanonicalTargetScreen.TOP -> WidescreenTargetScreen.TOP
            CanonicalTargetScreen.BOTTOM -> WidescreenTargetScreen.BOTTOM
            CanonicalTargetScreen.UNRESOLVED -> error("UNRESOLVED target cannot match runtime")
        }
    }

    private fun String.toLines(): List<String> {
        return split(' ').chunked(2).map { it.joinToString(" ") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentRuntimeProfiles(): List<WidescreenProfile> {
        return AutoWidescreen::class.java.getDeclaredField("profiles").let { field ->
            field.isAccessible = true
            field.get(AutoWidescreen) as List<WidescreenProfile>
        }
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected manifest rejection: $label")
        } catch (exception: WidescreenManifestValidationException) {
            assertTrue("Empty validation error for $label", exception.message?.isNotBlank() == true)
        }
    }
}
