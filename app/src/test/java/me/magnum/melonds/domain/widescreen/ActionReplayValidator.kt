package me.magnum.melonds.domain.widescreen

internal const val ACTION_REPLAY_CAPABILITY_VERSION = "MELONDS_AR_ENGINE_CAPABILITIES_V1"
internal const val ACTION_REPLAY_ENGINE_CORE_COMMIT =
    "6d763a82da4934070862c0412a23bd2d1f12f420"
// SHA-256 of AREngine.cpp encoded as UTF-8 with LF line endings.
internal const val ACTION_REPLAY_ENGINE_SOURCE_SHA256 =
    "b9f235f31baa2268d50dd49d0b9c0a9de05b5d2c716836db4df8408d66f4c4e5"
internal const val ACTION_REPLAY_ENGINE_CONDITION_STACK_BITS = 32

internal enum class ActionReplayValidationStatus {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    UNSUPPORTED,
    INVALID,
}

internal data class ActionReplayWordPair(
    val lineNumber: Int,
    val firstWord: UInt,
    val secondWord: UInt,
)

internal data class ActionReplayInstruction(
    val lineNumber: Int,
    val firstWord: UInt,
    val secondWord: UInt,
    val opcode: String,
    val payload: List<ActionReplayWordPair> = emptyList(),
)

internal data class ActionReplayValidationResult(
    val capabilityVersion: String,
    val status: ActionReplayValidationStatus,
    val diagnostics: List<String>,
    val usedOpcodes: List<String>,
    val instructionCount: Int,
    val payloadBytes: Long,
    val instructions: List<ActionReplayInstruction>,
)

/**
 * Strict build-time mirror of AREngine.cpp at [ACTION_REPLAY_ENGINE_CORE_COMMIT].
 *
 * Capability matrix (opcode is the first word's high byte):
 * - 00-BF: SUPPORTED by high-nibble groups 0-B.
 * - C0: SUPPORTED for one loop; nested loops are PARTIALLY_SUPPORTED.
 * - C1-C4: UNSUPPORTED (C4 has an explicit abort path).
 * - C5: PARTIALLY_SUPPORTED (the engine questions its counter/condition semantics).
 * - C6: SUPPORTED. C7-CF: UNSUPPORTED.
 * - D0-D3: SUPPORTED. D4/00-08: SUPPORTED. D4/09-FF: UNSUPPORTED.
 * - D5-DC: SUPPORTED. DD-DF: UNSUPPORTED.
 * - E0-EF: PARTIALLY_SUPPORTED because destination alignment is left unchecked.
 * - F0-FF: PARTIALLY_SUPPORTED because source/destination alignment is left unchecked.
 *
 * This validates syntax, control structure, and payload boundaries. It deliberately does not
 * emulate memory accesses or claim that runtime addresses are safe.
 */
internal object ActionReplayValidator {
    private val canonicalLine = Regex("[0-9A-F]{8} [0-9A-F]{8}")

    fun validate(lines: List<String>): ActionReplayValidationResult {
        if (lines.isEmpty()) {
            return invalidResult("program: empty Action Replay program")
        }

        val malformed = lines.mapIndexedNotNull { index, line ->
            if (canonicalLine.matches(line)) null
            else "line ${index + 1}: malformed Action Replay line; expected AAAAAAAA BBBBBBBB"
        }
        if (malformed.isNotEmpty()) {
            return ActionReplayValidationResult(
                capabilityVersion = ACTION_REPLAY_CAPABILITY_VERSION,
                status = ActionReplayValidationStatus.INVALID,
                diagnostics = malformed,
                usedOpcodes = emptyList(),
                instructionCount = 0,
                payloadBytes = 0,
                instructions = emptyList(),
            )
        }

        val words = lines.mapIndexed { index, line ->
            ActionReplayWordPair(
                lineNumber = index + 1,
                firstWord = line.substring(0, 8).toUInt(16),
                secondWord = line.substring(9, 17).toUInt(16),
            )
        }
        return decode(words)
    }

    private fun decode(words: List<ActionReplayWordPair>): ActionReplayValidationResult {
        var status = ActionReplayValidationStatus.SUPPORTED
        val diagnostics = mutableListOf<String>()
        val usedOpcodes = linkedSetOf<String>()
        val instructions = mutableListOf<ActionReplayInstruction>()
        val conditions = java.util.ArrayDeque<Int>()
        val loops = java.util.ArrayDeque<LoopFrame>()
        var payloadBytes = 0L
        var index = 0

        fun report(candidate: ActionReplayValidationStatus, line: Int, message: String) {
            diagnostics += "line $line: $message"
            if (candidate.priority > status.priority) status = candidate
        }

        fun openCondition(line: Int) {
            val depth = conditions.size + 1
            if (depth > ACTION_REPLAY_ENGINE_CONDITION_STACK_BITS) {
                report(
                    ActionReplayValidationStatus.INVALID,
                    line,
                    "conditional depth $depth exceeds AREngine's " +
                        "$ACTION_REPLAY_ENGINE_CONDITION_STACK_BITS-bit condition stack",
                )
            } else if (depth > 1) {
                report(
                    ActionReplayValidationStatus.PARTIALLY_SUPPORTED,
                    line,
                    "nested condition depth $depth is only partially supported: " +
                        "AREngine skips nested condition opcodes while a parent is false",
                )
            }
            conditions.addLast(line)
        }

        while (index < words.size) {
            val wordPair = words[index]
            val opcodeByte = (wordPair.firstWord shr 24).toInt()
            val opcode = opcodeLabel(opcodeByte)
            usedOpcodes += opcode

            var payload = emptyList<ActionReplayWordPair>()
            if (opcodeByte in 0xE0..0xEF) {
                val declaredBytes = wordPair.secondWord.toLong()
                payloadBytes += declaredBytes
                val requiredLines = (declaredBytes + 7) / 8
                val availableLines = words.size - index - 1
                if (requiredLines > availableLines.toLong()) {
                    payload = words.subList(index + 1, words.size)
                    instructions += wordPair.toInstruction(opcode, payload)
                    report(
                        ActionReplayValidationStatus.INVALID,
                        wordPair.lineNumber,
                        "E payload requires $declaredBytes bytes but only " +
                            "${availableLines.toLong() * 8} remain",
                    )
                    break
                }
                val payloadLineCount = requiredLines.toInt()
                payload = words.subList(index + 1, index + 1 + payloadLineCount)
                index += payloadLineCount
            }

            instructions += wordPair.toInstruction(opcode, payload)
            when (capability(opcodeByte, wordPair.firstWord)) {
                ActionReplayValidationStatus.SUPPORTED -> Unit
                ActionReplayValidationStatus.PARTIALLY_SUPPORTED -> {
                    report(
                        ActionReplayValidationStatus.PARTIALLY_SUPPORTED,
                        wordPair.lineNumber,
                        partialReason(opcodeByte),
                    )
                }

                ActionReplayValidationStatus.UNSUPPORTED -> {
                    val message = if (opcodeByte == 0xD4) {
                        val subOperation = (wordPair.firstWord and 0xFFu).toString(16)
                            .uppercase()
                            .padStart(2, '0')
                        "unsupported D4 sub-operation $subOperation"
                    } else {
                        "unsupported opcode $opcode"
                    }
                    report(ActionReplayValidationStatus.UNSUPPORTED, wordPair.lineNumber, message)
                }

                ActionReplayValidationStatus.INVALID -> error("Capability cannot be INVALID")
            }

            when {
                opcodeByte in 0x30..0xAF -> openCondition(wordPair.lineNumber)
                opcodeByte == 0xC5 -> openCondition(wordPair.lineNumber)
                opcodeByte == 0xC0 -> {
                    if (loops.isNotEmpty()) {
                        report(
                            ActionReplayValidationStatus.PARTIALLY_SUPPORTED,
                            wordPair.lineNumber,
                            "nested loop is only partially supported: AREngine has one loop state",
                        )
                    }
                    loops.addLast(LoopFrame(wordPair.lineNumber, conditions.size))
                }

                opcodeByte == 0xD0 -> {
                    if (conditions.isEmpty()) {
                        report(
                            ActionReplayValidationStatus.INVALID,
                            wordPair.lineNumber,
                            "conditional stack underflow",
                        )
                    } else {
                        conditions.removeLast()
                        val activeLoop = loops.peekLast()
                        if (activeLoop != null && conditions.size < activeLoop.conditionDepth) {
                            report(
                                ActionReplayValidationStatus.INVALID,
                                wordPair.lineNumber,
                                "D0 closes a condition opened outside the active loop",
                            )
                        }
                    }
                }

                opcodeByte == 0xD1 -> {
                    if (loops.isEmpty()) {
                        report(
                            ActionReplayValidationStatus.INVALID,
                            wordPair.lineNumber,
                            "D1 encountered without active loop",
                        )
                    } else {
                        val loop = loops.removeLast()
                        if (conditions.size != loop.conditionDepth) {
                            report(
                                ActionReplayValidationStatus.INVALID,
                                wordPair.lineNumber,
                                "loop opened at line ${loop.lineNumber} changes conditional depth",
                            )
                        }
                    }
                }

                opcodeByte == 0xD2 -> {
                    if (loops.isNotEmpty()) {
                        val loop = loops.removeLast()
                        if (conditions.size != loop.conditionDepth) {
                            report(
                                ActionReplayValidationStatus.INVALID,
                                wordPair.lineNumber,
                                "loop opened at line ${loop.lineNumber} changes conditional depth",
                            )
                        }
                    }
                    conditions.clear()
                }
            }

            index++
        }

        conditions.forEach { line ->
            report(ActionReplayValidationStatus.INVALID, line, "condition opened here is not closed")
        }
        loops.forEach { loop ->
            report(ActionReplayValidationStatus.INVALID, loop.lineNumber, "loop opened here is not closed")
        }

        return ActionReplayValidationResult(
            capabilityVersion = ACTION_REPLAY_CAPABILITY_VERSION,
            status = status,
            diagnostics = diagnostics,
            usedOpcodes = usedOpcodes.toList(),
            instructionCount = instructions.size,
            payloadBytes = payloadBytes,
            instructions = instructions,
        )
    }

    private fun capability(opcode: Int, firstWord: UInt): ActionReplayValidationStatus {
        return when (opcode ushr 4) {
            in 0x0..0xB -> ActionReplayValidationStatus.SUPPORTED
            0xC -> when (opcode) {
                0xC0, 0xC6 -> ActionReplayValidationStatus.SUPPORTED
                0xC5 -> ActionReplayValidationStatus.PARTIALLY_SUPPORTED
                else -> ActionReplayValidationStatus.UNSUPPORTED
            }

            0xD -> when (opcode) {
                in 0xD0..0xD3, in 0xD5..0xDC -> ActionReplayValidationStatus.SUPPORTED
                0xD4 -> if ((firstWord and 0xFFu) <= 0x08u) {
                    ActionReplayValidationStatus.SUPPORTED
                } else {
                    ActionReplayValidationStatus.UNSUPPORTED
                }

                else -> ActionReplayValidationStatus.UNSUPPORTED
            }

            0xE, 0xF -> ActionReplayValidationStatus.PARTIALLY_SUPPORTED
            else -> error("Opcode is one byte")
        }
    }

    private fun partialReason(opcode: Int): String {
        return when (opcode) {
            0xC5 -> "opcode C5 is only partially supported: AREngine resets its local counter " +
                "for every RunCheat call and marks the condition semantics unresolved"
            in 0xE0..0xEF -> "opcode E is only partially supported: " +
                "AREngine leaves destination alignment unchecked"
            in 0xF0..0xFF -> "opcode F is only partially supported: " +
                "AREngine leaves source/destination alignment unchecked"
            else -> error("No partial capability reason for opcode $opcode")
        }
    }

    private fun opcodeLabel(opcode: Int): String {
        return when (opcode ushr 4) {
            in 0x0..0xB -> (opcode ushr 4).toString(16).uppercase()
            0xC, 0xD -> opcode.toString(16).uppercase().padStart(2, '0')
            0xE -> "E"
            0xF -> "F"
            else -> error("Opcode is one byte")
        }
    }

    private fun ActionReplayWordPair.toInstruction(
        opcode: String,
        payload: List<ActionReplayWordPair>,
    ) = ActionReplayInstruction(lineNumber, firstWord, secondWord, opcode, payload)

    private fun invalidResult(message: String) = ActionReplayValidationResult(
        capabilityVersion = ACTION_REPLAY_CAPABILITY_VERSION,
        status = ActionReplayValidationStatus.INVALID,
        diagnostics = listOf(message),
        usedOpcodes = emptyList(),
        instructionCount = 0,
        payloadBytes = 0,
        instructions = emptyList(),
    )

    private val ActionReplayValidationStatus.priority: Int
        get() = when (this) {
            ActionReplayValidationStatus.SUPPORTED -> 0
            ActionReplayValidationStatus.PARTIALLY_SUPPORTED -> 1
            ActionReplayValidationStatus.UNSUPPORTED -> 2
            ActionReplayValidationStatus.INVALID -> 3
        }

    private data class LoopFrame(
        val lineNumber: Int,
        val conditionDepth: Int,
    )
}
