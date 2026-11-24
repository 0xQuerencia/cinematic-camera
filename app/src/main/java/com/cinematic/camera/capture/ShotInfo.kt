package com.cinematic.camera.capture

import org.json.JSONObject

data class ShotInfo(val project: String = "", val reel: String = "", val scene: String = "",
                    val take: Int = 1, val notes: String = "") {
    fun sanitized() = copy(project = project.take(80), reel = reel.take(40), scene = scene.take(40),
        take = take.coerceIn(1, 9999), notes = notes.take(500))
    fun json() = JSONObject().put("project", project).put("reel", reel).put("scene", scene)
        .put("take", take).put("notes", notes)
    companion object {
        fun from(value: JSONObject) = ShotInfo(value.optString("project"), value.optString("reel"),
            value.optString("scene"), value.optInt("take", 1), value.optString("notes")).sanitized()
    }
}
