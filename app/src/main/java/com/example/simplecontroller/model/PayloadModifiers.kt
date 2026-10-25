package com.example.simplecontroller.model

enum class PayloadCommandTiming { IMMEDIATE, SEND_ON_RELEASE }

data class PayloadCommand(
    val command: String,
    val timing: PayloadCommandTiming? = null,
    val holdReleaseDelayMs: Long = 0L,
    val hasModifiers: Boolean = false,
    val hasHoldReleaseDelay: Boolean = false
)

/** Android-local, per-command suffixes. Receivers only ever see [PayloadCommand.command]. */
object PayloadModifiers {
    const val RELEASE_PRESS_DURATION_MS = 90L
    const val MAX_HOLD_RELEASE_DELAY_MS = 600_000L

    data class Result(val commands: List<PayloadCommand>, val error: String? = null)

    fun hasModifiers(payload: String): Boolean = payload.any { it == '(' || it == ')' }

    fun parse(payload: String): Result {
        val result = mutableListOf<PayloadCommand>()
        for (raw in payload.split(Regex("[,\\s]+")).filter(String::isNotBlank)) {
            val suffixStart = raw.indexOf('(')
            if (suffixStart < 0) {
                if (')' in raw) return Result(emptyList(), "Malformed payload modifier: $raw")
                result += PayloadCommand(raw)
                continue
            }
            val command = raw.substring(0, suffixStart)
            if (command.isBlank()) return Result(emptyList(), "A payload modifier needs a command: $raw")
            var timing: PayloadCommandTiming? = null
            var delay: Long? = null
            var remaining = raw.substring(suffixStart)
            while (remaining.isNotEmpty()) {
                val match = Regex("^\\(([^()]*)\\)").find(remaining)
                    ?: return Result(emptyList(), "Malformed payload modifier: $raw")
                when (val modifier = match.groupValues[1].uppercase()) {
                    "I", "SOR" -> {
                        if (timing != null) return Result(emptyList(), "Use either (I) or (SOR) once per command: $raw")
                        timing = if (modifier == "I") PayloadCommandTiming.IMMEDIATE else PayloadCommandTiming.SEND_ON_RELEASE
                    }
                    else -> {
                        if (!modifier.startsWith("HRD")) return Result(emptyList(), "Unknown payload modifier: ${match.value}")
                        if (delay != null) return Result(emptyList(), "Use (HRDmilliseconds) once per command: $raw")
                        val number = modifier.substring(3)
                        val value = number.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
                        if (value == null || value !in 0..MAX_HOLD_RELEASE_DELAY_MS) {
                            return Result(emptyList(), "HRD must be whole milliseconds from 0 to $MAX_HOLD_RELEASE_DELAY_MS: $raw")
                        }
                        delay = value
                    }
                }
                remaining = remaining.substring(match.value.length)
            }
            result += PayloadCommand(command, timing, delay ?: 0L, hasModifiers = true,
                hasHoldReleaseDelay = delay != null)
        }
        return Result(result)
    }
}
