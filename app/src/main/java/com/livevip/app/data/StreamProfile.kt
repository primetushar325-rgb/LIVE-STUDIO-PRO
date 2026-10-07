package com.livevip.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Orientation(val label: String) {
    LANDSCAPE_16_9("16:9"),
    VERTICAL_9_16("9:16")
}

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"

    companion object {
        val LANDSCAPE = listOf(Resolution(1920, 1080), Resolution(1280, 720), Resolution(854, 480))
        val VERTICAL = listOf(Resolution(1080, 1920), Resolution(720, 1280), Resolution(480, 854))
        fun optionsFor(o: Orientation) = if (o == Orientation.LANDSCAPE_16_9) LANDSCAPE else VERTICAL
        fun parse(s: String?): Resolution? {
            val parts = s?.split("x") ?: return null
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return Resolution(w, h)
        }
    }
}

enum class LoopMode { OFF, COUNT, UNLIMITED }

data class VideoItem(
    val uri: String,
    val displayName: String = "",
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    fun aspectLabel(): String {
        if (width <= 0 || height <= 0) return "-"
        val ar = width.toFloat() / height.toFloat()
        return when {
            ar > 1.6f -> "16:9"
            ar < 0.7f -> "9:16"
            else -> String.format("%.2f:1", ar)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("displayName", displayName)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
    }

    companion object {
        fun fromJson(o: JSONObject) = VideoItem(
            uri = o.optString("uri"),
            displayName = o.optString("displayName"),
            durationMs = o.optLong("durationMs"),
            width = o.optInt("width"),
            height = o.optInt("height")
        )
    }
}

data class TransformSettings(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val fill: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("scale", scale.toDouble())
        put("tx", translationX.toDouble())
        put("ty", translationY.toDouble())
        put("fill", fill)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) TransformSettings() else TransformSettings(
            o.optDouble("scale", 1.0).toFloat(),
            o.optDouble("tx", 0.0).toFloat(),
            o.optDouble("ty", 0.0).toFloat(),
            o.optBoolean("fill", false)
        )
    }
}

/**
 * A saved stream profile. NOTE: the stream key is NOT part of the plain JSON blob,
 * it is stored separately in keystore backed encrypted preferences.
 */
data class StreamProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Stream",
    val playlist: List<VideoItem> = emptyList(),
    val orientation: Orientation = Orientation.LANDSCAPE_16_9,
    val resolution: Resolution = Resolution(1280, 720),
    val fps: Int = 30,
    val bitrateKbps: Int = 2500,
    val keyframeIntervalSec: Int = 2,
    val videoAudioEnabled: Boolean = true,
    val microphoneEnabled: Boolean = false,
    val serverUrl: String = "rtmps://a.rtmps.youtube.com:443/live2",
    val loopMode: LoopMode = LoopMode.UNLIMITED,
    val loopCount: Int = 3,
    val maxDurationMinutes: Int = 600,
    val useCamera: Boolean = false,
    val transform: TransformSettings = TransformSettings(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L
) {
    fun summaryLine(): String =
        "${orientation.label} • ${playlist.size} video${if (playlist.size == 1) "" else "s"} • " +
            "$resolution • ${fps}FPS • ${loopLabel()} • ${durationLabel()}"

    fun loopLabel(): String = when (loopMode) {
        LoopMode.OFF -> "No loop"
        LoopMode.COUNT -> "Loop x$loopCount"
        LoopMode.UNLIMITED -> "Loop ∞"
    }

    fun durationLabel(): String =
        if (maxDurationMinutes % 60 == 0) "${maxDurationMinutes / 60}H max" else "${maxDurationMinutes}M max"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("playlist", JSONArray().also { arr -> playlist.forEach { arr.put(it.toJson()) } })
        put("orientation", orientation.name)
        put("resolution", resolution.toString())
        put("fps", fps)
        put("bitrateKbps", bitrateKbps)
        put("keyframeIntervalSec", keyframeIntervalSec)
        put("videoAudioEnabled", videoAudioEnabled)
        put("microphoneEnabled", microphoneEnabled)
        put("serverUrl", serverUrl)
        put("loopMode", loopMode.name)
        put("loopCount", loopCount)
        put("maxDurationMinutes", maxDurationMinutes)
        put("useCamera", useCamera)
        put("transform", transform.toJson())
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastUsedAt", lastUsedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): StreamProfile {
            val list = mutableListOf<VideoItem>()
            val arr = o.optJSONArray("playlist")
            if (arr != null) for (i in 0 until arr.length()) list.add(VideoItem.fromJson(arr.getJSONObject(i)))
            val orientation = runCatching { Orientation.valueOf(o.optString("orientation")) }
                .getOrDefault(Orientation.LANDSCAPE_16_9)
            return StreamProfile(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Stream"),
                playlist = list,
                orientation = orientation,
                resolution = Resolution.parse(o.optString("resolution"))
                    ?: Resolution.optionsFor(orientation)[1],
                fps = o.optInt("fps", 30),
                bitrateKbps = o.optInt("bitrateKbps", 2500),
                keyframeIntervalSec = o.optInt("keyframeIntervalSec", 2),
                videoAudioEnabled = o.optBoolean("videoAudioEnabled", true),
                microphoneEnabled = o.optBoolean("microphoneEnabled", false),
                serverUrl = o.optString("serverUrl"),
                loopMode = runCatching { LoopMode.valueOf(o.optString("loopMode")) }
                    .getOrDefault(LoopMode.UNLIMITED),
                loopCount = o.optInt("loopCount", 3),
                maxDurationMinutes = o.optInt("maxDurationMinutes", 600),
                useCamera = o.optBoolean("useCamera", false),
                transform = TransformSettings.fromJson(o.optJSONObject("transform")),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                lastUsedAt = o.optLong("lastUsedAt", 0L)
            )
        }
    }
}
