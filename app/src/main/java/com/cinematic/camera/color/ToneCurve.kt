package com.cinematic.camera.color

/** Five fixed input knots (0, .25, .5, .75, 1), with three editable output knots. */
data class ToneCurve(val low: Float = .25f, val mid: Float = .5f, val high: Float = .75f) {
    init {
        require(low in 0f..1f && mid in low..1f && high in mid..1f) { "Curve knots must be finite and ordered." }
    }

    fun map(input: Float): Float {
        val x = input.coerceIn(0f, 1f) * 4f
        return when {
            x < 1f -> x * low
            x < 2f -> low + (x - 1f) * (mid - low)
            x < 3f -> mid + (x - 2f) * (high - mid)
            else -> high + (x - 3f) * (1f - high)
        }
    }

    fun values(): FloatArray = floatArrayOf(low, mid, high)
}

data class RgbCurves(
    val master: ToneCurve = ToneCurve(),
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve(),
) {
    fun map(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        red.map(master.map(r)), green.map(master.map(g)), blue.map(master.map(b))
    )
}
