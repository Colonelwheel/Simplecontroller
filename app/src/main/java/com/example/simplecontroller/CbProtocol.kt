package com.example.simplecontroller

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * CBv0 frame builder for ConsoleBridge.
 * Binary format (little endian):
 * [0]=0xCB, [1]=0x01, [2:4]=seq(u16), [4]=type, payload..., [last]=crc8
 */
object CbProtocol {
    private const val MAGIC: Byte = 0xCB.toByte()
    private const val VERSION: Byte = 0x01
    private var seq: Int = 0

    // Types
    private const val TYPE_KEY: Byte         = 0x01
    private const val TYPE_MOUSE_DELTA: Byte = 0x02
    private const val TYPE_MOUSE_BTN: Byte   = 0x03
    private const val TYPE_GP_BUTTON: Byte   = 0x04
    private const val TYPE_GP_STICK: Byte    = 0x05
    private const val TYPE_GP_TRIGGER: Byte  = 0x06

    // Mouse button ids
    private const val MB_LEFT = 1
    private const val MB_RIGHT = 2
    private const val MB_MIDDLE = 3

    // Gamepad button map (must match gateway)
    private val gpButtonId: Map<String, Int> = mapOf(
        "X360A" to 0,
        "X360B" to 1,
        "X360X" to 2,
        "X360Y" to 3,
        "X360LB" to 4,
        "X360RB" to 5,
        "X360BACK" to 6,
        "X360START" to 7,
        "X360LSC" to 8,
        "X360RSC" to 9,
    )

    // --- Public API ---

    /** Try to encode a legacy command into a CBv0 frame. Returns null if unsupported. */
    fun encode(command: String): ByteArray? {
        val c = command.trim()

        // Mouse delta: DELTA:x,y
        parseDelta(c)?.let { (dx, dy) -> return mouseDelta(dx, dy) }

        // Mouse buttons
        parseMouse(c)?.let { return it }

        // Sticks: LS:x,y or RS:x,y
        parseStick(c)?.let { (which, x, y) -> return gpStick(which, x, y) }

        // Triggers: LT:v or RT:v
        parseTrigger(c)?.let { (which, v) -> return gpTrigger(which, v) }

        // Keyboard: KEY_DOWN:W / KEY_UP:W / "W" tap
        parseKey(c)?.let { (op, key) -> return keyEvent(op, key) }

        // Gamepad buttons: X360A / X360A_HOLD / X360A_RELEASE
        parseGpButton(c)?.let { (base, op) -> return gpButton(base, op) }

        return null
    }

    // --- Builders ---

    private fun keyEvent(op: Int, key: String): ByteArray {
        val keyBytes = key.encodeToByteArray()
        val body = ByteBuffer.allocate(2 + 1 + keyBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_KEY)
            .put(op.toByte())                  // 0=down,1=up,2=press
            .put(keyBytes.size.toByte())
            .put(keyBytes)
            .array()
        return wrap(body)
    }

    private fun mouseDelta(dx: Int, dy: Int): ByteArray {
        val body = ByteBuffer.allocate(1 + 2 + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_MOUSE_DELTA)
            .putShort(dx.toShort())
            .putShort(dy.toShort())
            .array()
        return wrap(body)
    }

    private fun mouseBtn(op: Int, btnId: Int): ByteArray {
        val body = ByteBuffer.allocate(1 + 1 + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_MOUSE_BTN)
            .put(op.toByte()) // 0=down,1=up,2=click
            .put(btnId.toByte())
            .array()
        return wrap(body)
    }

    private fun gpButton(base: String, op: Int): ByteArray {
        val id = gpButtonId[base] ?: return ByteArray(0)
        val body = ByteBuffer.allocate(1 + 1 + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_GP_BUTTON)
            .put(op.toByte()) // 0=down/hold,1=up/release,2=press
            .put(id.toByte())
            .array()
        return wrap(body)
    }

    private fun gpStick(which: Int, x: Int, y: Int): ByteArray {
        val body = ByteBuffer.allocate(1 + 1 + 2 + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_GP_STICK)
            .put(which.toByte()) // 0=LS,1=RS
            .putShort(x.toShort())
            .putShort(y.toShort())
            .array()
        return wrap(body)
    }

    private fun gpTrigger(which: Int, v01k: Int): ByteArray {
        val body = ByteBuffer.allocate(1 + 1 + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_GP_TRIGGER)
            .put(which.toByte()) // 0=LT,1=RT
            .put(v01k.toByte())  // 0..255
            .array()
        return wrap(body)
    }

    private fun wrap(typeAndPayload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(2 + 2 + typeAndPayload.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC)
        b.put(VERSION)
        val s = (seq and 0xFFFF)
        b.putShort(s.toShort())
        seq = (seq + 1) and 0xFFFF
        b.put(typeAndPayload)
        val withoutCrc = b.array().copyOf(b.position())
        val crc = crc8(withoutCrc)
        b.put(crc)
        return b.array()
    }

    // --- Parsers ---

    private fun parseDelta(c: String): Pair<Int, Int>? {
        if (!c.startsWith("DELTA:", ignoreCase = true)) return null
        val parts = c.substringAfter("DELTA:").split(",")
        if (parts.size != 2) return null
        val dx = parts[0].toFloatOrNull() ?: return null
        val dy = parts[1].toFloatOrNull() ?: return null
        // inverse of gateway scaling (gateway divides by 100.0)
        return (dx * 100f).roundToInt() to (dy * 100f).roundToInt()
    }

    private fun parseMouse(c: String): ByteArray? {
        return when (c.uppercase()) {
            "MOUSE_LEFT_DOWN"  -> mouseBtn(0, MB_LEFT)
            "MOUSE_LEFT_UP"    -> mouseBtn(1, MB_LEFT)
            "MOUSE_LEFT"       -> mouseBtn(2, MB_LEFT)
            "MOUSE_RIGHT_DOWN" -> mouseBtn(0, MB_RIGHT)
            "MOUSE_RIGHT_UP"   -> mouseBtn(1, MB_RIGHT)
            "MOUSE_RIGHT"      -> mouseBtn(2, MB_RIGHT)
            "MOUSE_MIDDLE_DOWN"-> mouseBtn(0, MB_MIDDLE)
            "MOUSE_MIDDLE_UP"  -> mouseBtn(1, MB_MIDDLE)
            "MOUSE_MIDDLE"     -> mouseBtn(2, MB_MIDDLE)
            else -> null
        }
    }

    private fun parseStick(c: String): Triple<Int, Int, Int>? {
        val u = c.uppercase()
        val which =
            if (u.startsWith("LS:")) 0
            else if (u.startsWith("RS:")) 1
            else return null
        val nums = u.substringAfter(':').split(',')
        if (nums.size != 2) return null
        val x = (nums[0].toFloatOrNull() ?: return null).coerceIn(-1f, 1f)
        val y = (nums[1].toFloatOrNull() ?: return null).coerceIn(-1f, 1f)
        // convert [-1,1] → i16 using 32767 scale (inverse of gateway)
        val xi = (x * 32767f).roundToInt().coerceIn(-32768, 32767)
        val yi = (y * 32767f).roundToInt().coerceIn(-32768, 32767)
        return Triple(which, xi, yi)
    }

    private fun parseTrigger(c: String): Pair<Int, Int>? {
        val u = c.uppercase()
        val which =
            if (u.startsWith("LT:")) 0
            else if (u.startsWith("RT:")) 1
            else return null
        val v = u.substringAfter(':').toFloatOrNull() ?: return null
        val v01k = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return which to v01k
    }

    private fun parseKey(c: String): Pair<Int, String>? {
        val u = c.uppercase()
        return when {
            u.startsWith("KEY_DOWN:") -> 0 to u.substringAfter("KEY_DOWN:")
            u.startsWith("KEY_UP:")   -> 1 to u.substringAfter("KEY_UP:")
            // bare key tap like "W" or "SPACE"
            u.length in 1..16 && !u.contains(':') -> 2 to u
            else -> null
        }
    }

    private fun parseGpButton(c: String): Pair<String, Int>? {
        val u = c.uppercase()
        val base = u.substringBefore('_')
        if (!gpButtonId.containsKey(base)) return null
        val op = when {
            u.endsWith("_HOLD")    -> 0
            u.endsWith("_RELEASE") -> 1
            else                   -> 2
        }
        return base to op
    }

    // --- CRC-8/ATM (poly=0x07) ---
    private fun crc8(bytes: ByteArray): Byte {
        var crc = 0x00
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) and 0xFF) xor 0x07
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return (crc and 0xFF).toByte()
    }
}
