package me.magnum.melonds.domain.widescreen

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionReplayValidatorTest {
    @Test
    fun capabilityMetadataMatchesPinnedEngineSource() {
        assertEquals("MELONDS_AR_ENGINE_CAPABILITIES_V1", ACTION_REPLAY_CAPABILITY_VERSION)
        assertEquals(
            "6d763a82da4934070862c0412a23bd2d1f12f420",
            ACTION_REPLAY_ENGINE_CORE_COMMIT,
        )
        assertEquals(32, ACTION_REPLAY_ENGINE_CONDITION_STACK_BITS)

        val workingDirectory = Path.of("").toAbsolutePath()
        val source = listOf(
            workingDirectory.resolve("melonDS-android-lib/src/AREngine.cpp"),
            workingDirectory.resolve("../melonDS-android-lib/src/AREngine.cpp").normalize(),
        ).firstOrNull(Files::isRegularFile)
        checkNotNull(source) { "Cannot locate melonDS-android-lib/src/AREngine.cpp" }
        val normalizedSource = String(Files.readAllBytes(source), UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val actualHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(normalizedSource.toByteArray(UTF_8)),
        )
        assertEquals(ACTION_REPLAY_ENGINE_SOURCE_SHA256, actualHash)
    }

    @Test
    fun parserPreservesUnsignedWordsLineAndOpcode() {
        val result = validate("0FFFFFFF FFFFFFFF")

        assertEquals(ActionReplayValidationStatus.SUPPORTED, result.status)
        assertEquals(ACTION_REPLAY_CAPABILITY_VERSION, result.capabilityVersion)
        assertEquals(1, result.instructionCount)
        assertEquals(listOf("0"), result.usedOpcodes)
        assertEquals(1, result.instructions.single().lineNumber)
        assertEquals(0x0FFFFFFFu, result.instructions.single().firstWord)
        assertEquals(UInt.MAX_VALUE, result.instructions.single().secondWord)
    }

    @Test
    fun writesZeroOneAndTwoAreSupported() {
        listOf("0", "1", "2").forEach { type ->
            assertStatus(ActionReplayValidationStatus.SUPPORTED, "${type}0000000 FFFFFFFF")
        }
    }

    @Test
    fun conditionsThreeThroughAAreSupported() {
        ('3'..'9').plus('A').forEach { type ->
            assertStatus(
                ActionReplayValidationStatus.SUPPORTED,
                "${type}0000000 00000000",
                "D0000000 00000000",
            )
        }
    }

    @Test
    fun offsetPointerGroupBIsSupported() {
        assertStatus(ActionReplayValidationStatus.SUPPORTED, "B0000000 00000000")
    }

    @Test
    fun c0SingleLoopAndC6AreSupported() {
        assertStatus(
            ActionReplayValidationStatus.SUPPORTED,
            "C0000000 00000000",
            "00000000 00000000",
            "D1000000 00000000",
        )
        assertStatus(
            ActionReplayValidationStatus.SUPPORTED,
            "C0000000 00000000",
            "D2000000 00000000",
        )
        assertStatus(ActionReplayValidationStatus.SUPPORTED, "C6000000 02000000")
    }

    @Test
    fun c5IsPartiallySupportedWithPreciseReason() {
        val result = validate("C5000000 00000000", "D0000000 00000000")

        assertEquals(ActionReplayValidationStatus.PARTIALLY_SUPPORTED, result.status)
        assertContains(result, "line 1: opcode C5 is only partially supported")
    }

    @Test
    fun everyUnimplementedCOpcodeIsUnsupported() {
        val opcodes = (0xC1..0xC4) + (0xC7..0xCF)
        opcodes.forEach { opcode ->
            val label = opcode.toString(16).uppercase()
            val result = validate("${label}000000 00000000")
            assertEquals("opcode $label", ActionReplayValidationStatus.UNSUPPORTED, result.status)
            assertContains(result, "unsupported opcode $label")
        }
    }

    @Test
    fun supportedDControlAndRegisterOpcodesMatchEngine() {
        assertStatus(
            ActionReplayValidationStatus.SUPPORTED,
            "50000000 00000000",
            "D0000000 00000000",
        )
        assertStatus(
            ActionReplayValidationStatus.SUPPORTED,
            "C0000000 00000000",
            "D1000000 00000000",
        )
        assertStatus(ActionReplayValidationStatus.SUPPORTED, "D2000000 00000000")
        assertStatus(ActionReplayValidationStatus.SUPPORTED, "D3000000 02000000")
        (0xD5..0xDC).forEach { opcode ->
            val label = opcode.toString(16).uppercase()
            assertStatus(ActionReplayValidationStatus.SUPPORTED, "${label}000000 00000000")
        }
    }

    @Test
    fun d4SubOperationsZeroThroughEightAreSupported() {
        (0x00..0x08).forEach { subOperation ->
            val suffix = subOperation.toString(16).uppercase().padStart(2, '0')
            assertStatus(
                ActionReplayValidationStatus.SUPPORTED,
                "D40000$suffix 00000001",
            )
        }
    }

    @Test
    fun unknownD4AndDdThroughDfAreUnsupported() {
        val badD4 = validate("D400000A 00000001")
        assertEquals(ActionReplayValidationStatus.UNSUPPORTED, badD4.status)
        assertContains(badD4, "line 1: unsupported D4 sub-operation 0A")

        (0xDD..0xDF).forEach { opcode ->
            val label = opcode.toString(16).uppercase()
            assertStatus(ActionReplayValidationStatus.UNSUPPORTED, "${label}000000 00000000")
        }
    }

    @Test
    fun ePayloadConsumptionMatchesEngineForRequestedLengths() {
        listOf(
            listOf(0, 0, 0, 0),
            listOf(1, 2, 1, 7),
            listOf(7, 2, 1, 1),
            listOf(8, 2, 1, 0),
            listOf(9, 4, 2, 7),
            listOf(15, 4, 2, 1),
            listOf(16, 4, 2, 0),
        ).forEach { (byteCount, expectedWords, expectedLines, expectedPadding) ->
            val program = buildList {
                add("E2000000 ${byteCount.toString(16).uppercase().padStart(8, '0')}")
                repeat(expectedLines) { add("C4000000 D400000A") }
                add("00000000 00000000")
            }
            val result = ActionReplayValidator.validate(program)
            val payload = result.instructions.first().payload

            assertEquals("b=$byteCount status", ActionReplayValidationStatus.PARTIALLY_SUPPORTED, result.status)
            assertEquals("b=$byteCount copied bytes", byteCount.toLong(), result.payloadBytes)
            assertEquals("b=$byteCount consumed words", expectedWords, payload.size * 2)
            assertEquals("b=$byteCount consumed lines", expectedLines, payload.size)
            assertEquals("b=$byteCount padding", expectedPadding.toLong(), payload.size * 8L - result.payloadBytes)
            assertEquals("b=$byteCount instruction count", 2, result.instructionCount)
            assertEquals("b=$byteCount used opcodes", listOf("E", "0"), result.usedOpcodes)
        }
    }

    @Test
    fun ePayloadCannotReadPastProgram() {
        val absent = validate("E2000000 00000008")
        assertEquals(ActionReplayValidationStatus.INVALID, absent.status)
        assertContains(absent, "line 1: E payload requires 8 bytes but only 0 remain")

        val partialFinalBlock = validate(
            "E2000000 00000009",
            "12345678 9ABCDEF0",
        )
        assertEquals(ActionReplayValidationStatus.INVALID, partialFinalBlock.status)
        assertContains(partialFinalBlock, "line 1: E payload requires 9 bytes but only 8 remain")

        val partial = validate(
            "E2000000 00000018",
            "12345678 9ABCDEF0",
        )
        assertEquals(ActionReplayValidationStatus.INVALID, partial.status)
        assertContains(partial, "line 1: E payload requires 24 bytes but only 8 remain")

        val incoherent = validate("E2000000 FFFFFFFF")
        assertEquals(ActionReplayValidationStatus.INVALID, incoherent.status)
        assertContains(incoherent, "E payload requires 4294967295 bytes")
    }

    @Test
    fun fCopyMatchesEngineStructureButRemainsPartialForAlignmentTodo() {
        val result = validate(
            "D3000000 02000000",
            "F2000000 00000004",
        )

        assertEquals(ActionReplayValidationStatus.PARTIALLY_SUPPORTED, result.status)
        assertEquals(listOf("D3", "F"), result.usedOpcodes)
        assertContains(result, "AREngine leaves source/destination alignment unchecked")
        assertStatus(ActionReplayValidationStatus.INVALID, "F2000000 0000000")
    }

    @Test
    fun canonicalFormatRejectsEmptyLowercaseWidthsWhitespaceAndBlankLines() {
        assertStatus(ActionReplayValidationStatus.INVALID)
        listOf(
            "1234567a 00000000",
            "1234567 00000000",
            "123456789 00000000",
            "12345678  00000000",
            "12345678\t00000000",
            "",
        ).forEach { line ->
            val result = validate(line)
            assertEquals("line '$line'", ActionReplayValidationStatus.INVALID, result.status)
            assertContains(result, "malformed Action Replay line")
        }
    }

    @Test
    fun conditionsDetectUnderflowOpenBlocksNestingAndEngineDepth() {
        val underflow = validate("D0000000 00000000")
        assertEquals(ActionReplayValidationStatus.INVALID, underflow.status)
        assertContains(underflow, "line 1: conditional stack underflow")

        val open = validate("50000000 00000000")
        assertEquals(ActionReplayValidationStatus.INVALID, open.status)
        assertContains(open, "line 1: condition opened here is not closed")

        val nested = validate(
            "30000000 00000000",
            "40000000 00000000",
            "D0000000 00000000",
            "D0000000 00000000",
        )
        assertEquals(ActionReplayValidationStatus.PARTIALLY_SUPPORTED, nested.status)
        assertContains(nested, "nested condition depth 2 is only partially supported")

        listOf(30, 31, 32).forEach { depth ->
            val boundary = ActionReplayValidator.validate(nestedConditions(depth))
            assertEquals(
                "depth $depth",
                ActionReplayValidationStatus.PARTIALLY_SUPPORTED,
                boundary.status,
            )
            assertTrue("depth $depth must fit the native u32", boundary.diagnostics.none { "exceeds" in it })
        }
        val tooDeep = ActionReplayValidator.validate(nestedConditions(33))
        assertEquals(ActionReplayValidationStatus.INVALID, tooDeep.status)
        assertContains(tooDeep, "conditional depth 33 exceeds AREngine's 32-bit condition stack")
    }

    @Test
    fun d2FlushClosesConditionsWithoutUnderflow() {
        assertStatus(
            ActionReplayValidationStatus.SUPPORTED,
            "90000000 00000000",
            "D2000000 00000000",
        )
        assertStatus(ActionReplayValidationStatus.SUPPORTED, "D2000000 00000000")
    }

    @Test
    fun loopsDetectMissingEndsOrStartsAndNestedSingleState() {
        val noLoop = validate("D1000000 00000000")
        assertEquals(ActionReplayValidationStatus.INVALID, noLoop.status)
        assertContains(noLoop, "line 1: D1 encountered without active loop")

        val open = validate("C0000000 00000000")
        assertEquals(ActionReplayValidationStatus.INVALID, open.status)
        assertContains(open, "line 1: loop opened here is not closed")

        val nested = validate(
            "C0000000 00000000",
            "C0000000 00000000",
            "D1000000 00000000",
            "D1000000 00000000",
        )
        assertEquals(ActionReplayValidationStatus.PARTIALLY_SUPPORTED, nested.status)
        assertContains(nested, "line 2: nested loop is only partially supported")

        val unbalancedBody = validate(
            "C0000000 00000000",
            "30000000 00000000",
            "D1000000 00000000",
        )
        assertEquals(ActionReplayValidationStatus.INVALID, unbalancedBody.status)
        assertContains(unbalancedBody, "loop opened at line 1 changes conditional depth")
    }

    @Test
    fun resultAggregatesWorstStatusAndStatistics() {
        val result = validate(
            "C5000000 00000000",
            "D0000000 00000000",
            "C4000000 00000000",
        )

        assertEquals(ActionReplayValidationStatus.UNSUPPORTED, result.status)
        assertEquals(listOf("C5", "D0", "C4"), result.usedOpcodes)
        assertEquals(3, result.instructionCount)
        assertEquals(0L, result.payloadBytes)
        assertEquals(2, result.diagnostics.size)
    }

    @Test
    fun pokemonWhiteRuntimeValidatedPatchIsSupported() {
        val result = validate(
            "922822A8 00001555",
            "122822A8 00001C72",
            "D2000000 00000000",
        )

        assertEquals(ActionReplayValidationStatus.SUPPORTED, result.status)
        assertEquals(listOf("9", "1", "D2"), result.usedOpcodes)
        assertEquals(3, result.instructionCount)
        assertEquals(0L, result.payloadBytes)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun superMario64DsRuntimeValidatedPatchIsSupported() {
        val result = validate(
            "520209C4 E59D0094",
            "020209C8 E3A09C15",
            "0200D03C 00001C71",
            "D2000000 00000000",
            "520B21E8 E59F00E8",
            "020B227C E3A02B07",
            "D2000000 00000000",
            "5211530C E59F205C",
            "02115370 00001C71",
            "D2000000 00000000",
        )

        assertEquals(ActionReplayValidationStatus.SUPPORTED, result.status)
        assertEquals(listOf("5", "0", "D2"), result.usedOpcodes)
        assertEquals(10, result.instructionCount)
        assertEquals(0L, result.payloadBytes)
        assertTrue(result.diagnostics.isEmpty())
    }

    private fun validate(vararg lines: String): ActionReplayValidationResult {
        return ActionReplayValidator.validate(lines.toList())
    }

    private fun nestedConditions(depth: Int): List<String> {
        return List(depth) { "30000000 00000000" } + List(depth) { "D0000000 00000000" }
    }

    private fun assertStatus(expected: ActionReplayValidationStatus, vararg lines: String) {
        assertEquals(expected, validate(*lines).status)
    }

    private fun assertContains(result: ActionReplayValidationResult, expected: String) {
        assertTrue(
            "Expected diagnostic containing '$expected' but got ${result.diagnostics}",
            result.diagnostics.any { expected in it },
        )
    }
}
