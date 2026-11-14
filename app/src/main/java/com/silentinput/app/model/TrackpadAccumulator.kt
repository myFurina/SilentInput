package com.silentinput.app.model

/**
 * Sub-pixel movement and two-finger scroll delta accumulator for the touch trackpad.
 */
class TrackpadAccumulator {
    var accX: Float = 0f
        private set
    var accY: Float = 0f
        private set
    var accWheel: Float = 0f
        private set

    fun addMoveDelta(dx: Float, dy: Float, sensitivity: Float): Pair<Int, Int> {
        if (!dx.isFinite() || !dy.isFinite() || !sensitivity.isFinite()) {
            return 0 to 0
        }
        accX += dx * sensitivity
        accY += dy * sensitivity
        val intX = accX.toInt()
        val intY = accY.toInt()
        if (intX != 0 || intY != 0) {
            accX -= intX
            accY -= intY
        }
        return intX to intY
    }

    fun addScrollDelta(
        avgDy: Float,
        sensitivity: Float,
        stepDivisor: Float = 28f,
        naturalScrolling: Boolean = true
    ): Int {
        if (!avgDy.isFinite() || !sensitivity.isFinite() || !stepDivisor.isFinite() || stepDivisor == 0f) {
            return 0
        }
        val directionSign = if (naturalScrolling) 1f else -1f
        accWheel += (directionSign * avgDy * sensitivity) / stepDivisor
        val intWheel = accWheel.toInt()
        if (intWheel != 0) {
            accWheel -= intWheel
        }
        return intWheel
    }

    fun reset() {
        accX = 0f
        accY = 0f
        accWheel = 0f
    }
}
