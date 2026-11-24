package com.cinematic.camera.media

object FramePacing {
    data class Result(val median: Long, val max: Long, val longIntervals: Int)
    fun analyze(pts: LongArray): Result? {
        if (pts.size < 2) return null
        val sorted = pts.sortedArray()
        val intervals = LongArray(sorted.size - 1) { sorted[it+1] - sorted[it] }
        val positive = intervals.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return null
        val median = positive[positive.size / 2]
        return Result(median, positive.last(),positive.count { it > median*1.5 })
    }
}
