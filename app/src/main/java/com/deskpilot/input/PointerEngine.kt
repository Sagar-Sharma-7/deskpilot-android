package com.deskpilot.input

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

data class PointerTuning(
    val sensitivity: Float = 1.15f,
    val acceleration: Boolean = true,
    val accelerationStrength: Float = .32f,
    val precisionMultiplier: Float = .42f
)

/** Converts touch deltas into bounded relative HID values without smoothing-induced lag. */
class PointerEngine(private var tuning: PointerTuning = PointerTuning()) {
    private var xRemainder = 0f
    private var yRemainder = 0f
    var precisionMode = false

    fun updateTuning(value: PointerTuning) { tuning = value }
    fun reset() { xRemainder = 0f; yRemainder = 0f }

    fun move(dx: Float, dy: Float): Pair<Int, Int> {
        if (dx == 0f && dy == 0f) return 0 to 0
        val scale = tuning.sensitivity * if (precisionMode) tuning.precisionMultiplier else 1f
        val speed = kotlin.math.sqrt(dx * dx + dy * dy)
        val acceleration = if (tuning.acceleration) 1f + tuning.accelerationStrength * (speed / 18f).coerceIn(0f, 1.5f) else 1f
        xRemainder += dx * scale * acceleration
        yRemainder += dy * scale * acceleration
        val x = xRemainder.toInt().coerceIn(-127, 127)
        val y = yRemainder.toInt().coerceIn(-127, 127)
        xRemainder -= x
        yRemainder -= y
        return x to y
    }
}

class ScrollEngine(private var speed: Float = .12f) {
    private var verticalRemainder = 0f
    private var horizontalRemainder = 0f
    fun updateSpeed(value: Float, highResolution: Boolean = false) {
        // A standard mouse wheel unit is a full Windows detent. The advanced
        // descriptor advertises 16 high-resolution units per detent instead.
        speed = value * if (highResolution) HIGH_RESOLUTION_MULTIPLIER else 1f
    }
    fun reset() { verticalRemainder = 0f; horizontalRemainder = 0f }
    fun scroll(dx: Float, dy: Float, natural: Boolean): Pair<Int, Int> {
        val direction = if (natural) 1 else -1
        verticalRemainder += dy * speed * direction
        horizontalRemainder += dx * speed * direction
        val vertical = verticalRemainder.toInt().coerceIn(-127, 127)
        val horizontal = horizontalRemainder.toInt().coerceIn(-127, 127)
        verticalRemainder -= vertical
        horizontalRemainder -= horizontal
        return vertical to horizontal
    }

    private companion object {
        const val HIGH_RESOLUTION_MULTIPLIER = 16f
    }
}
