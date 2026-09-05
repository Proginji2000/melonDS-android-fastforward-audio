package me.magnum.melonds.domain.widescreen

import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.RomInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWidescreenTest {
    // All metadata and AR words in this test are synthetic and never enter production.
    private val validCode = "12345678 9ABCDEF0"
    private val profile = WidescreenProfile(
        id = "synthetic-16-9",
        romKey = WidescreenRomKey("TEST", 0x12345678u),
        targetRatio = WidescreenRatio.RATIO_16_9,
        targetScreen = WidescreenTargetScreen.TOP,
        actionReplayCode = validCode,
    )

    @Test
    fun exactRomIsResolved() {
        assertEquals(profile, AutoWidescreen.resolve(romInfo(), listOf(profile)))
    }

    @Test
    fun wrongChecksumIsNotResolved() {
        assertNull(AutoWidescreen.resolve(romInfo(checksum = 0x87654321u), listOf(profile)))
    }

    @Test
    fun wrongGameCodeIsNotResolved() {
        assertNull(AutoWidescreen.resolve(romInfo(gameCode = "FAIL"), listOf(profile)))
    }

    @Test
    fun productionRegistryIsEmpty() {
        assertNull(AutoWidescreen.resolve(romInfo()))
    }

    @Test
    fun disabledSettingDoesNotActivateProfile() {
        assertNull(AutoWidescreen.activate(profile, isEnabled = false, isHardcoreModeEnabled = false))
    }

    @Test
    fun hardcoreDoesNotActivateProfile() {
        assertNull(AutoWidescreen.activate(profile, isEnabled = true, isHardcoreModeEnabled = true))
    }

    @Test
    fun actionReplayCodeRequiresCanonicalHexPairs() {
        assertTrue(AutoWidescreen.isActionReplayCodeValid(validCode))
        assertTrue(AutoWidescreen.isActionReplayCodeValid("12345678 9ABCDEF0 00000000 FFFFFFFF"))
        assertFalse(AutoWidescreen.isActionReplayCodeValid("12345678"))
        assertFalse(AutoWidescreen.isActionReplayCodeValid("1234567G 9ABCDEF0"))
        assertFalse(AutoWidescreen.isActionReplayCodeValid("12345678  9ABCDEF0"))
        assertNull(
            AutoWidescreen.activate(
                profile.copy(actionReplayCode = "12345678"),
                isEnabled = true,
                isHardcoreModeEnabled = false,
            ),
        )
    }

    @Test
    fun ratio16By9FitsInsideExistingRect() {
        assertEquals(
            Rect(0, 24, 256, 144),
            AutoWidescreen.fitRect(Rect(0, 0, 256, 192), WidescreenRatio.RATIO_16_9),
        )
    }

    @Test
    fun ratio16By10FitsInsideExistingRect() {
        assertEquals(
            Rect(0, 16, 256, 160),
            AutoWidescreen.fitRect(Rect(0, 0, 256, 192), WidescreenRatio.RATIO_16_10),
        )
    }

    @Test
    fun ratioOnlyAppliesToTargetPhysicalScreen() {
        val originalRect = Rect(0, 0, 256, 192)

        assertEquals(
            Rect(0, 24, 256, 144),
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.TOP, profile),
        )
        assertEquals(
            originalRect,
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.BOTTOM, profile),
        )
    }

    @Test
    fun unsupportedRomLeavesSessionAndRectUnchanged() {
        val originalRect = Rect(10, 20, 256, 192)
        val resolved = AutoWidescreen.resolve(romInfo(gameCode = "NONE"), listOf(profile))
        val active = AutoWidescreen.activate(resolved, isEnabled = true, isHardcoreModeEnabled = false)

        assertNull(active)
        assertEquals(
            originalRect,
            AutoWidescreen.screenRect(originalRect, WidescreenTargetScreen.TOP, active),
        )
    }

    @Test
    fun activeProfileProducesOnlyAnEphemeralCheat() {
        val active = AutoWidescreen.activate(profile, isEnabled = true, isHardcoreModeEnabled = false)!!
        val cheat = AutoWidescreen.toSessionCheat(active)

        assertNull(cheat.id)
        assertEquals(0, cheat.cheatDatabaseId)
        assertEquals(validCode, cheat.code)
        assertTrue(cheat.enabled)
    }

    private fun romInfo(
        gameCode: String = "TEST",
        checksum: UInt = 0x12345678u,
    ): RomInfo {
        return RomInfo(gameCode, checksum, "Synthetic", "Synthetic")
    }
}
