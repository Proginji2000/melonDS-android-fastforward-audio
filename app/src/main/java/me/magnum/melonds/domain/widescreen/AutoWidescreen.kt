package me.magnum.melonds.domain.widescreen

import me.magnum.melonds.domain.model.Cheat
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.RomInfo

enum class WidescreenRatio(val width: Int, val height: Int) {
    RATIO_16_9(16, 9),
    RATIO_16_10(16, 10),
}

enum class WidescreenTargetScreen {
    TOP,
    BOTTOM,
}

data class WidescreenRomKey(
    val gameCode: String,
    val headerChecksum: UInt,
)

data class WidescreenProfile(
    val id: String,
    val romKey: WidescreenRomKey,
    val targetRatio: WidescreenRatio,
    val targetScreen: WidescreenTargetScreen,
    val actionReplayCode: String,
)

object AutoWidescreen {
    private val profiles = listOf(
        // Super Mario 64 DS, Europe v1.0; AR Game ID ASMP-D3D9F14A; 16:9, physical TOP.
        // Code provenance: DeSmuME forum "Widescreen Codes" thread and its Europe AR list;
        // the ROM ID ASMP-D3D9F14A is also documented by shocoman's project.
        WidescreenProfile(
            id = "super-mario-64-ds-eur-v1-0",
            romKey = WidescreenRomKey("ASMP", 0xD3D9F14Au),
            targetRatio = WidescreenRatio.RATIO_16_9,
            targetScreen = WidescreenTargetScreen.TOP,
            actionReplayCode =
                "520209C4 E59D0094 " +
                    "020209C8 E3A09C15 " +
                    "0200D03C 00001C71 " +
                    "D2000000 00000000 " +
                    "520B21E8 E59F00E8 " +
                    "020B227C E3A02B07 " +
                    "D2000000 00000000 " +
                    "5211530C E59F205C " +
                    "02115370 00001C71 " +
                    "D2000000 00000000",
        ),
    )
    private val actionReplayCodePattern =
        Regex("^(?:[0-9A-Fa-f]{8} [0-9A-Fa-f]{8})(?: [0-9A-Fa-f]{8} [0-9A-Fa-f]{8})*$")

    fun resolve(romInfo: RomInfo): WidescreenProfile? = resolve(romInfo, profiles)

    internal fun resolve(
        romInfo: RomInfo,
        registry: List<WidescreenProfile>,
    ): WidescreenProfile? {
        val romKey = WidescreenRomKey(romInfo.gameCode, romInfo.headerChecksum)
        return registry.singleOrNull { it.romKey == romKey }
    }

    fun activate(
        profile: WidescreenProfile?,
        isEnabled: Boolean,
        isHardcoreModeEnabled: Boolean,
    ): WidescreenProfile? {
        return profile?.takeIf {
            isEnabled && !isHardcoreModeEnabled && isActionReplayCodeValid(it.actionReplayCode)
        }
    }

    fun isActionReplayCodeValid(code: String): Boolean {
        return actionReplayCodePattern.matches(code)
    }

    fun toSessionCheat(profile: WidescreenProfile): Cheat {
        return Cheat(
            id = null,
            cheatDatabaseId = 0,
            name = "Automatic widescreen (${profile.id})",
            description = null,
            code = profile.actionReplayCode,
            enabled = true,
        )
    }

    fun screenRect(
        rect: Rect?,
        physicalScreen: WidescreenTargetScreen,
        profile: WidescreenProfile?,
    ): Rect? {
        return if (rect != null && profile?.targetScreen == physicalScreen) {
            fitRect(rect, profile.targetRatio)
        } else {
            rect
        }
    }

    fun fitRect(rect: Rect, ratio: WidescreenRatio): Rect {
        if (rect.width <= 0 || rect.height <= 0) {
            return rect
        }

        val isWiderThanTarget =
            rect.width.toLong() * ratio.height > rect.height.toLong() * ratio.width
        return if (isWiderThanTarget) {
            val width = rect.height * ratio.width / ratio.height
            Rect(rect.x + (rect.width - width) / 2, rect.y, width, rect.height)
        } else {
            val height = rect.width * ratio.height / ratio.width
            Rect(rect.x, rect.y + (rect.height - height) / 2, rect.width, height)
        }
    }
}
