package com.example.simplecontroller.model

/** Pure payload checks shared by profile validation and the runtime executor. */
object ButtonAimPayloadPolicy {
    private enum class TokenKind { OUTPUT, EDGE_ACTION, FORCE_RELEASE, RELEASE_ALL }

    private data class TokenResult(val kind: TokenKind? = null, val error: String? = null)

    fun validationError(payload: String, allowBlank: Boolean = false): String? {
        val tokens = tokenize(payload)
        if (tokens.isEmpty()) return if (allowBlank) null else "Payload is blank"
        return tokens.firstNotNullOfOrNull { classify(it).error }
    }

    /** TouchAim states must contain only outputs whose ownership can be released safely. */
    fun stateValidationError(payload: String): String? {
        val tokens = tokenize(payload)
        if (tokens.isEmpty()) return null
        val results = tokens.map(::classify)
        results.firstNotNullOfOrNull(TokenResult::error)?.let { return it }
        return if (results.all { it.kind == TokenKind.OUTPUT }) null else
            "TouchAim Aim/Shoot payloads may use only releasable Xbox, trigger, mouse-button, keyboard, or stick-macro outputs."
    }

    private fun classify(raw: String): TokenResult {
        val command = raw.trim().uppercase()
        if (command.startsWith("PAGE_")) {
            return TokenResult(error = "Page actions must use the local Button action selector.")
        }
        if (command == "RELEASE_ALL") return TokenResult(TokenKind.RELEASE_ALL)
        if (command == "SCROLL_MODE_TOGGLE" || command == "CAMERA_FOLLOW" ||
            command == "CAMERA_FOLLOW:1" || command == "CAMERA_FOLLOW:0"
        ) return TokenResult(TokenKind.EDGE_ACTION)

        STICK_MACRO_REGEX.matchEntire(command)?.let { match ->
            if (!isKnownDirection(match.groupValues[2])) {
                return TokenResult(error = "Unknown stick direction: $raw")
            }
            return TokenResult(TokenKind.OUTPUT)
        }

        TRIGGER_REGEX.matchEntire(command)?.let { match ->
            val value = match.groupValues[2].toFloatOrNull()
                ?: return TokenResult(error = "Invalid trigger value: $raw")
            if (value !in 0f..1f) {
                return TokenResult(error = "Trigger value must be 0.0-1.0: $raw")
            }
            return TokenResult(
                if (value == 0f && match.groupValues[3].isEmpty()) {
                    TokenKind.FORCE_RELEASE
                } else {
                    TokenKind.OUTPUT
                }
            )
        }
        if (command.startsWith("LT:") || command.startsWith("RT:")) {
            return TokenResult(error = "Invalid trigger command: $raw")
        }

        XBOX_REGEX.matchEntire(command)?.let { match ->
            return TokenResult(
                if (match.groupValues[2] == "RELEASE") TokenKind.FORCE_RELEASE
                else TokenKind.OUTPUT
            )
        }
        if (command.startsWith("X360")) {
            return TokenResult(error = "Invalid Xbox command: $raw")
        }

        MOUSE_REGEX.matchEntire(command)?.let { match ->
            return TokenResult(
                if (match.groupValues[2] == "UP") TokenKind.FORCE_RELEASE
                else TokenKind.OUTPUT
            )
        }
        if (command.startsWith("MOUSE_") || command.startsWith("TOUCHPAD:") ||
            command.startsWith("STICK")
        ) return TokenResult(TokenKind.EDGE_ACTION)

        if (command.startsWith("KEY_DOWN:")) {
            return if (raw.substringAfter(':').trim().isEmpty()) {
                TokenResult(error = "Keyboard key is blank: $raw")
            } else TokenResult(TokenKind.OUTPUT)
        }
        if (command.startsWith("KEY_UP:")) {
            return if (raw.substringAfter(':').trim().isEmpty()) {
                TokenResult(error = "Keyboard key is blank: $raw")
            } else TokenResult(TokenKind.FORCE_RELEASE)
        }

        return TokenResult(TokenKind.OUTPUT)
    }

    private fun isKnownDirection(direction: String): Boolean = direction in setOf(
        "R", "RIGHT", "L", "LEFT", "U", "UP", "D", "DOWN", "UR", "UL", "BR", "BL"
    )

    private fun tokenize(payload: String): List<String> = payload
        .split(TOKEN_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)

    private val TOKEN_SEPARATOR = Regex("[,\\s]+")
    private val XBOX_REGEX = Regex("^X360([A-Z0-9]+?)(?:_(HOLD|RELEASE))?$")
    private val MOUSE_REGEX = Regex("^MOUSE_(LEFT|RIGHT|MIDDLE)(?:_(DOWN|UP))?$")
    private val TRIGGER_REGEX = Regex(
        "^(LT|RT):([01](?:\\.[0-9]+)?)(?:P([0-9]+(?:\\.[0-9]+)?))?$"
    )
    private val STICK_MACRO_REGEX = Regex("^(LS|RS):([A-Z]+)([0-9]{1,3})?$")
}
