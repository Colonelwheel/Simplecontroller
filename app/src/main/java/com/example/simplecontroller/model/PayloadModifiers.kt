package com.example.simplecontroller.model

enum class PayloadCommandTiming { IMMEDIATE, SEND_ON_RELEASE }

data class PayloadCommand(
    val command: String,
    val timing: PayloadCommandTiming? = null,
    val holdReleaseDelayMs: Long = 0L,
    val hasModifiers: Boolean = false,
    val hasHoldReleaseDelay: Boolean = false,
    val repeatIntervalMs: Long? = null
)

/** Android-local, per-command suffixes. Receivers only ever see [PayloadCommand.command]. */
object PayloadModifiers {
    const val RELEASE_PRESS_DURATION_MS = 90L
    const val MAX_HOLD_RELEASE_DELAY_MS = 600_000L
    const val MIN_REPEAT_INTERVAL_MS = 2L
    const val MAX_REPEAT_INTERVAL_MS = 600_000L

    data class Result(val commands: List<PayloadCommand>, val error: String? = null)

    fun hasModifiers(payload: String): Boolean = payload.any { it == '(' || it == ')' }

    /** Includes malformed attempted R suffixes so old readers cannot silently accept them. */
    fun hasRepeatModifiers(payload: String): Boolean = REPEAT_SUFFIX_START.containsMatchIn(payload)

    /** Trigger P remains an authored ON duration; otherwise use the existing finite-tap duty. */
    fun repeatPressDurationMs(command: PayloadCommand): Long? {
        val interval = command.repeatIntervalMs ?: return null
        val authoredPulse = TRIGGER_PULSE.matchEntire(command.command)?.groupValues?.get(1)
            ?.toFloatOrNull()?.let { (it * 1000f).toLong().coerceAtLeast(1L) }
        return authoredPulse ?: (interval / 2L).coerceIn(1L, 50L)
    }

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
            var repeatInterval: Long? = null
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
                        when {
                            modifier.startsWith("HRD") -> {
                                if (delay != null) return Result(emptyList(), "Use (HRDmilliseconds) once per command: $raw")
                                val value = wholeMilliseconds(modifier.substring(3))
                                if (value == null || value !in 0..MAX_HOLD_RELEASE_DELAY_MS) {
                                    return Result(emptyList(), "HRD must be whole milliseconds from 0 to $MAX_HOLD_RELEASE_DELAY_MS: $raw")
                                }
                                delay = value
                            }
                            modifier.startsWith("R") -> {
                                if (repeatInterval != null) return Result(emptyList(), "Use (Rmilliseconds) once per command: $raw")
                                val value = wholeMilliseconds(modifier.substring(1))
                                if (value == null || value !in MIN_REPEAT_INTERVAL_MS..MAX_REPEAT_INTERVAL_MS) {
                                    return Result(emptyList(), "R must be whole milliseconds from $MIN_REPEAT_INTERVAL_MS to $MAX_REPEAT_INTERVAL_MS: $raw")
                                }
                                repeatInterval = value
                            }
                            else -> return Result(emptyList(), "Unknown payload modifier: ${match.value}")
                        }
                    }
                }
                remaining = remaining.substring(match.value.length)
            }
            if (repeatInterval != null && timing == PayloadCommandTiming.SEND_ON_RELEASE) {
                return Result(emptyList(), "(Rmilliseconds) repeats while held and cannot be combined with (SOR): $raw")
            }
            result += PayloadCommand(command, timing, delay ?: 0L, hasModifiers = true,
                hasHoldReleaseDelay = delay != null, repeatIntervalMs = repeatInterval)
        }
        return Result(result)
    }

    private fun wholeMilliseconds(number: String): Long? =
        number.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

    private val REPEAT_SUFFIX_START = Regex("\\(R", RegexOption.IGNORE_CASE)
    private val TRIGGER_PULSE = Regex("^(?:LT|RT):[01](?:\\.[0-9]+)?P([0-9]+(?:\\.[0-9]+)?)$", RegexOption.IGNORE_CASE)
}
