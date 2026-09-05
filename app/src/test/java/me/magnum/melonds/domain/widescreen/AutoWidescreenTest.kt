package me.magnum.melonds.domain.widescreen

import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.RomInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWidescreenTest {
    private val superMarioRom = RomInfo(
        gameCode = "ASMP",
        headerChecksum = 0xD3D9F14Au,
        gameTitle = "SUPER MARIO 64 DS",
        gameName = "Super Mario 64 DS",
    )
    private val expectedSuperMarioActionReplayCode =
        "520209C4 E59D0094 " +
            "020209C8 E3A09C15 " +
            "0200D03C 00001C71 " +
            "D2000000 00000000 " +
            "520B21E8 E59F00E8 " +
            "020B227C E3A02B07 " +
            "D2000000 00000000 " +
            "5211530C E59F205C " +
            "02115370 00001C71 " +
            "D2000000 00000000"
    private val pokemonWhiteRom = RomInfo(
        gameCode = "IRAF",
        headerChecksum = 0x031EF208u,
        gameTitle = "POKEMON W",
        gameName = "Pokemon White Version",
    )
    private val expectedPokemonWhiteActionReplayCode =
        "922822A8 00001555 " +
            "122822A8 00001C72 " +
            "D2000000 00000000"

    @Test
    fun superMario64DsEuropeV10IsResolved() {
        assertEquals("super-mario-64-ds-eur-v1-0", superMarioProfile().id)
    }

    @Test
    fun wholeRomChecksumDoesNotMatchSuperMarioProfile() {
        assertNull(AutoWidescreen.resolve(superMarioRom.copy(headerChecksum = 0x29715DECu)))
    }

    @Test
    fun gameCodeMatchingIsCaseSensitive() {
        assertNull(AutoWidescreen.resolve(superMarioRom.copy(gameCode = "asmp")))
    }

    @Test
    fun superMarioProfileUses16By9() {
        assertEquals(WidescreenRatio.RATIO_16_9, superMarioProfile().targetRatio)
    }

    @Test
    fun superMarioProfileTargetsPhysicalTopScreen() {
        assertEquals(WidescreenTargetScreen.TOP, superMarioProfile().targetScreen)
    }

    @Test
    fun superMarioActionReplayCodeIsAccepted() {
        assertTrue(AutoWidescreen.isActionReplayCodeValid(superMarioProfile().actionReplayCode))
    }

    @Test
    fun superMarioActionReplayCodeKeepsExactOrderAndContents() {
        assertEquals(expectedSuperMarioActionReplayCode, superMarioProfile().actionReplayCode)
    }

    @Test
    fun disabledSettingDoesNotActivateSuperMarioProfile() {
        assertNull(
            AutoWidescreen.activate(
                superMarioProfile(),
                isEnabled = false,
                isHardcoreModeEnabled = false,
            ),
        )
    }

    @Test
    fun hardcoreDoesNotActivateSuperMarioProfile() {
        assertNull(
            AutoWidescreen.activate(
                superMarioProfile(),
                isEnabled = true,
                isHardcoreModeEnabled = true,
            ),
        )
    }

    @Test
    fun pokemonWhiteFranceV10IsResolved() {
        assertEquals("pokemon-white-fra-v1-0", pokemonWhiteProfile().id)
    }

    @Test
    fun wrongChecksumDoesNotMatchPokemonWhiteProfile() {
        assertNull(AutoWidescreen.resolve(pokemonWhiteRom.copy(headerChecksum = 0x031EF209u)))
    }

    @Test
    fun wrongGameCodeDoesNotMatchPokemonWhiteProfile() {
        assertNull(AutoWidescreen.resolve(pokemonWhiteRom.copy(gameCode = "IRAE")))
    }

    @Test
    fun twilightBinarySuffixDoesNotMatchPokemonWhiteHeaderChecksum() {
        assertNull(AutoWidescreen.resolve(pokemonWhiteRom.copy(headerChecksum = 0x0000BC1Du)))
    }

    @Test
    fun pokemonWhiteProfileUses16By9OnPhysicalTopScreen() {
        assertEquals(WidescreenRatio.RATIO_16_9, pokemonWhiteProfile().targetRatio)
        assertEquals(WidescreenTargetScreen.TOP, pokemonWhiteProfile().targetScreen)
    }

    @Test
    fun pokemonWhiteActionReplayCodeIsAccepted() {
        assertTrue(AutoWidescreen.isActionReplayCodeValid(pokemonWhiteProfile().actionReplayCode))
    }

    @Test
    fun pokemonWhiteActionReplayCodeKeepsExactOrderAndContents() {
        assertEquals(expectedPokemonWhiteActionReplayCode, pokemonWhiteProfile().actionReplayCode)
    }

    @Test
    fun disabledSettingDoesNotActivatePokemonWhiteProfile() {
        assertNull(
            AutoWidescreen.activate(
                pokemonWhiteProfile(),
                isEnabled = false,
                isHardcoreModeEnabled = false,
            ),
        )
    }

    @Test
    fun hardcoreDoesNotActivatePokemonWhiteProfile() {
        assertNull(
            AutoWidescreen.activate(
                pokemonWhiteProfile(),
                isEnabled = true,
                isHardcoreModeEnabled = true,
            ),
        )
    }

    @Test
    fun unknownRomLeavesSessionAndRectUnchanged() {
        val originalRect = Rect(10, 20, 256, 192)
        val resolved = AutoWidescreen.resolve(superMarioRom.copy(gameCode = "NONE"))
        val active = AutoWidescreen.activate(
            resolved,
            isEnabled = true,
            isHardcoreModeEnabled = false,
        )

        assertNull(active)
        assertEquals(
            originalRect,
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.TOP, active),
        )
    }

    @Test
    fun actionReplayCodeRejectsNonCanonicalInput() {
        assertFalse(AutoWidescreen.isActionReplayCodeValid("12345678"))
        assertFalse(AutoWidescreen.isActionReplayCodeValid("1234567G 9ABCDEF0"))
        assertFalse(AutoWidescreen.isActionReplayCodeValid("12345678  9ABCDEF0"))
    }

    @Test
    fun ratio16By9FitsCenteredInsideExistingRect() {
        val originalRect = Rect(0, 0, 256, 192)
        val fittedRect = AutoWidescreen.fitRect(originalRect, WidescreenRatio.RATIO_16_9)

        assertEquals(Rect(0, 24, 256, 144), fittedRect)
        assertTrue(originalRect.contains(fittedRect))
        assertEquals(Rect(0, 0, 256, 192), originalRect)
    }

    @Test
    fun ratio16By10FitsInsideExistingRect() {
        assertEquals(
            Rect(0, 16, 256, 160),
            AutoWidescreen.fitRect(Rect(0, 0, 256, 192), WidescreenRatio.RATIO_16_10),
        )
    }

    @Test
    fun matchingRatioRemainsUnchanged() {
        val originalRect = Rect(8, 12, 320, 180)
        assertEquals(originalRect, AutoWidescreen.fitRect(originalRect, WidescreenRatio.RATIO_16_9))
    }

    @Test
    fun ratioOnlyAppliesToTargetPhysicalScreen() {
        val originalRect = Rect(0, 0, 256, 192)

        assertEquals(
            Rect(0, 24, 256, 144),
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.TOP, superMarioProfile()),
        )
        assertEquals(
            originalRect,
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.BOTTOM, superMarioProfile()),
        )
    }

    @Test
    fun physicalTopWidescreenFollowsSwappedLayoutSlot() {
        val topLayoutSlot = Rect(0, 0, 256, 192)
        val bottomLayoutSlot = Rect(300, 0, 400, 300)

        // After screen swap, the bottom layout slot displays the physical top screen.
        val physicalTopRect = AutoWidescreen.screenRect(
            bottomLayoutSlot,
            WidescreenTargetScreen.TOP,
            superMarioProfile(),
        )
        val physicalBottomRect = AutoWidescreen.screenRect(
            topLayoutSlot,
            WidescreenTargetScreen.BOTTOM,
            superMarioProfile(),
        )

        assertEquals(Rect(300, 37, 400, 225), physicalTopRect)
        assertEquals(topLayoutSlot, physicalBottomRect)
    }

    @Test
    fun activeProfileProducesOnlyAnEphemeralCheat() {
        val activeProfile = requireNotNull(
            AutoWidescreen.activate(
                superMarioProfile(),
                isEnabled = true,
                isHardcoreModeEnabled = false,
            ),
        )
        val cheat = AutoWidescreen.toSessionCheat(activeProfile)

        assertNull(cheat.id)
        assertEquals(0, cheat.cheatDatabaseId)
        assertEquals(expectedSuperMarioActionReplayCode, cheat.code)
        assertTrue(cheat.enabled)
    }

    private fun superMarioProfile(): WidescreenProfile {
        return requireNotNull(AutoWidescreen.resolve(superMarioRom))
    }

    private fun pokemonWhiteProfile(): WidescreenProfile {
        return requireNotNull(AutoWidescreen.resolve(pokemonWhiteRom))
    }
}
