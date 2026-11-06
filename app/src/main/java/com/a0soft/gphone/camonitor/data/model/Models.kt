package com.a0soft.gphone.camonitor.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * QR Code Pairing schema exchanged between Camera PA and Viewer PB.
 */
data class PairingPayload(
    val ver: Int = 1,
    val deviceId: String,
    val name: String,
    val secret: String, // 256-bit Base64 OTP secret
    val broker: String? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("ver", ver)
            put("deviceId", deviceId)
            put("name", name)
            put("secret", secret)
            broker?.let { put("broker", it) }
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): PairingPayload? {
            return try {
                val json = JSONObject(jsonStr)
                PairingPayload(
                    ver = json.optInt("ver", 1),
                    deviceId = json.getString("deviceId"),
                    name = json.getString("name"),
                    secret = json.getString("secret"),
                    broker = json.optString("broker").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Persisted record of an authorized viewer or camera.
 */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val publicKeyBase64: String,
    val pairedTimestamp: Long,
    val role: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("name", name)
            put("publicKeyBase64", publicKeyBase64)
            put("pairedTimestamp", pairedTimestamp)
            role?.let { put("role", it) }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PairedDevice {
            return PairedDevice(
                deviceId = json.getString("deviceId"),
                name = json.getString("name"),
                publicKeyBase64 = json.getString("publicKeyBase64"),
                pairedTimestamp = json.getLong("pairedTimestamp"),
                role = json.optString("role").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * Encrypted and authenticated signaling message envelope sent over public MQTT.
 */
data class SignalingEnvelope(
    val senderId: String,
    val recipientId: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val iv: String,
    val tag: String,
    val signature: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("senderId", senderId)
            put("recipientId", recipientId)
            put("timestamp", timestamp)
            put("nonce", nonce)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("tag", tag)
            put("signature", signature)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SignalingEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                SignalingEnvelope(
                    senderId = json.getString("senderId"),
                    recipientId = json.getString("recipientId"),
                    timestamp = json.getLong("timestamp"),
                    nonce = json.getString("nonce"),
                    ciphertext = json.getString("ciphertext"),
                    iv = json.getString("iv"),
                    tag = json.getString("tag"),
                    signature = json.getString("signature")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Inner decrypted payload exchanged via signaling.
 */
sealed class SignalingMessage {
    data class PairRequest(
        val pbDeviceId: String,
        val pbName: String,
        val pbPublicKeyBase64: String
    ) : SignalingMessage()

    data class PairConfirm(
        val paDeviceId: String,
        val paName: String,
        val paPublicKeyBase64: String
    ) : SignalingMessage()

    data class Offer(
        val sdp: String
    ) : SignalingMessage()

    data class Answer(
        val sdp: String,
        val cameraName: String? = null,
        val fps: Int? = null
    ) : SignalingMessage()

    data class IceCandidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val sdp: String
    ) : SignalingMessage()

    data class Reject(
        val reason: String
    ) : SignalingMessage()

    data class CameraWaking(
        val cameraName: String? = null
    ) : SignalingMessage()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is PairRequest -> {
                json.put("type", "PAIR_REQUEST")
                json.put("pbDeviceId", pbDeviceId)
                json.put("pbName", pbName)
                json.put("pbPublicKeyBase64", pbPublicKeyBase64)
            }
            is PairConfirm -> {
                json.put("type", "PAIR_CONFIRM")
                json.put("paDeviceId", paDeviceId)
                json.put("paName", paName)
                json.put("paPublicKeyBase64", paPublicKeyBase64)
            }
            is Offer -> {
                json.put("type", "OFFER")
                json.put("sdp", sdp)
            }
            is Answer -> {
                json.put("type", "ANSWER")
                json.put("sdp", sdp)
                cameraName?.let { json.put("cameraName", it) }
                fps?.let { json.put("fps", it) }
            }
            is IceCandidate -> {
                json.put("type", "ICE_CANDIDATE")
                json.put("sdpMid", sdpMid)
                json.put("sdpMLineIndex", sdpMLineIndex)
                json.put("sdp", sdp)
            }
            is Reject -> {
                json.put("type", "REJECT")
                json.put("reason", reason)
            }
            is CameraWaking -> {
                json.put("type", "CAMERA_WAKING")
                cameraName?.let { json.put("cameraName", it) }
            }
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SignalingMessage? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.getString("type")) {
                    "PAIR_REQUEST" -> PairRequest(
                        pbDeviceId = json.getString("pbDeviceId"),
                        pbName = json.getString("pbName"),
                        pbPublicKeyBase64 = json.getString("pbPublicKeyBase64")
                    )
                    "PAIR_CONFIRM" -> PairConfirm(
                        paDeviceId = json.getString("paDeviceId"),
                        paName = json.getString("paName"),
                        paPublicKeyBase64 = json.getString("paPublicKeyBase64")
                    )
                    "OFFER" -> Offer(sdp = json.getString("sdp"))
                    "ANSWER" -> Answer(
                        sdp = json.getString("sdp"),
                        cameraName = json.optString("cameraName").takeIf { it.isNotEmpty() },
                        fps = if (json.has("fps")) json.getInt("fps") else null
                    )
                    "ICE_CANDIDATE" -> IceCandidate(
                        sdpMid = json.getString("sdpMid"),
                        sdpMLineIndex = json.getInt("sdpMLineIndex"),
                        sdp = json.getString("sdp")
                    )
                    "REJECT" -> Reject(reason = json.getString("reason"))
                    "CAMERA_WAKING" -> CameraWaking(
                        cameraName = json.optString("cameraName").takeIf { it.isNotEmpty() }
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Real-time connection stages for Viewer Mode connection progress telemetry.
 */
enum class ViewerConnectionStep {
    CONNECTING_NETWORK,
    REACHING_CAMERA,
    ESTABLISHING_LINK,
    STARTING_VIDEO
}

/**
 * Immutable snapshot of the Viewer's 4-step connection pipeline and bounded countdown state.
 */
data class ViewerConnectionProgress(
    val currentStep: ViewerConnectionStep = ViewerConnectionStep.CONNECTING_NETWORK,
    val step1DurationMs: Long? = null,
    val step2DurationMs: Long? = null,
    val step3DurationMs: Long? = null,
    val step4DurationMs: Long? = null,
    val attempt: Int = 1,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val remainingTimeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    val totalTimeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    val isCameraWakingUp: Boolean = false,
    val typicalConnectMs: Long? = null
) {
    fun isStepCompleted(step: ViewerConnectionStep): Boolean = currentStep.ordinal > step.ordinal

    fun isStepActive(step: ViewerConnectionStep): Boolean = currentStep == step

    fun stepDurationMs(step: ViewerConnectionStep): Long? = when (step) {
        ViewerConnectionStep.CONNECTING_NETWORK -> step1DurationMs
        ViewerConnectionStep.REACHING_CAMERA -> step2DurationMs
        ViewerConnectionStep.ESTABLISHING_LINK -> step3DurationMs
        ViewerConnectionStep.STARTING_VIDEO -> step4DurationMs
    }

    fun elapsedWaitSec(): Int = (totalTimeoutSec - remainingTimeoutSec).coerceAtLeast(0)

    fun shouldShowCountdown(): Boolean =
        currentStep == ViewerConnectionStep.REACHING_CAMERA &&
            (elapsedWaitSec() >= COUNTDOWN_REVEAL_AFTER_SEC || attempt > 1)

    fun shouldShowSlowHint(): Boolean =
        currentStep == ViewerConnectionStep.REACHING_CAMERA &&
            !isCameraWakingUp &&
            elapsedWaitSec() >= SLOW_HINT_AFTER_SEC

    fun timeoutProgressFraction(): Float {
        if (totalTimeoutSec <= 0) return 0f
        return remainingTimeoutSec.coerceIn(0, totalTimeoutSec).toFloat() / totalTimeoutSec.toFloat()
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 15
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val OFFER_RETRY_INTERVAL_MS = 3_000L
        const val COUNTDOWN_REVEAL_AFTER_SEC = 2
        const val SLOW_HINT_AFTER_SEC = 5
        const val FIRST_FRAME_FALLBACK_TIMEOUT_MS = 1_500L

        fun formatDurationSec(durationMs: Long): String {
            val clampedMs = durationMs.coerceAtLeast(100L)
            return String.format(java.util.Locale.US, "%.1f", clampedMs / 1000.0)
        }
    }
}

/**
 * Control commands sent over WebRTC DataChannel.
 */
enum class NightModeSetting {
    AUTO,
    NIGHT,
    NORMAL
}

/**
 * Describes one physical camera exposed by the device.
 *
 * The camera side enumerates every usable lens via
 * [androidx.camera.lifecycle.ProcessCameraProvider.availableCameraInfos] and publishes
 * the resulting list to the viewer. The viewer then picks one of [id] at will.
 *
 * @param id stable identifier used on the wire (the Camera2 camera id).
 * @param label human readable label, e.g. "0.5x" / "1x" / "2x" / "Front".
 * @param lensFacing one of "back" / "front" / "external".
 * @param focalLength35eq equivalent 35mm focal length in millimetres, used to derive the zoom label.
 * @param zoomFactor relative zoom against the shortest back focal length.
 * @param isDefault true for the lens the camera uses by default (the main back camera).
 * @param mid WebRTC transceiver mid that carries this lens. Filled in by the camera
 *   once the answer is negotiated, so the viewer can map a remote track back to a
 *   physical lens without relying on ordering.
 */
data class CameraLensInfo(
    val id: String,
    val label: String,
    val lensFacing: String,
    val focalLength35eq: Float,
    val zoomFactor: Float,
    val isDefault: Boolean,
    val available: Boolean = true,
    val mid: String? = null
)

sealed class ControlCommand {
    data class Torch(val enabled: Boolean) : ControlCommand()
    data class TorchReport(val enabled: Boolean) : ControlCommand()
    object PttStart : ControlCommand()
    object PttStop : ControlCommand()
    data class Rename(val name: String) : ControlCommand()
    data class Battery(val percentage: Int, val isCharging: Boolean) : ControlCommand()
    object QueryBattery : ControlCommand()
    data class SetNightMode(val mode: String) : ControlCommand()
    data class NightModeReport(val mode: String, val isNightActive: Boolean) : ControlCommand()
    data class FpsReport(val fps: Int) : ControlCommand()

    /** Viewer asks the camera to switch the rendered lens. */
    data class SwitchLens(val lensId: String) : ControlCommand()

    /** Camera reports the lens list it loaded plus which one is currently selected. */
    data class LensListReport(val lenses: List<CameraLensInfo>, val activeLensId: String) : ControlCommand()

    /**
     * Camera answers a [SwitchLens] request.
     *
     * Every request MUST be answered — including the ones the camera refuses
     * ("that lens is already live", "another switch is running"). The viewer keeps
     * the tapped lens in a pending state until it sees this reply or its own
     * timeout fires, so a silent refusal leaves the button stuck forever.
     *
     * @param status OK / ALREADY_ACTIVE / BUSY / FAILED
     * @param activeLensId lens actually streaming after the request
     */
    data class LensSwitchResult(
        val lensId: String,
        val status: String,
        val activeLensId: String? = null,
        val message: String? = null
    ) : ControlCommand()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is Torch -> {
                json.put("cmd", "TORCH")
                json.put("enabled", enabled)
            }
            is TorchReport -> {
                json.put("cmd", "TORCH_REPORT")
                json.put("enabled", enabled)
            }
            is PttStart -> json.put("cmd", "PTT_START")
            is PttStop -> json.put("cmd", "PTT_STOP")
            is Rename -> {
                json.put("cmd", "RENAME")
                json.put("name", name)
            }
            is Battery -> {
                json.put("cmd", "BATTERY")
                json.put("pct", percentage)
                json.put("charging", isCharging)
            }
            is QueryBattery -> json.put("cmd", "QUERY_BATTERY")
            is SetNightMode -> {
                json.put("cmd", "SET_NIGHT_MODE")
                json.put("mode", mode)
            }
            is NightModeReport -> {
                json.put("cmd", "NIGHT_MODE_REPORT")
                json.put("mode", mode)
                json.put("active", isNightActive)
            }
            is FpsReport -> {
                json.put("cmd", "FPS_REPORT")
                json.put("fps", fps)
            }
            is SwitchLens -> {
                json.put("cmd", "SWITCH_LENS")
                json.put("lensId", lensId)
            }
            is LensListReport -> {
                json.put("cmd", "LENS_LIST_REPORT")
                json.put("activeLensId", activeLensId)
                json.put("lenses", JSONArray().apply {
                    lenses.forEach { lens ->
                        put(JSONObject().apply {
                            put("id", lens.id)
                            put("label", lens.label)
                            put("facing", lens.lensFacing)
                            put("focal35", lens.focalLength35eq.toDouble())
                            put("zoom", lens.zoomFactor.toDouble())
                            put("def", lens.isDefault)
                            put("avail", lens.available)
                            lens.mid?.let { put("mid", it) }
                        })
                    }
                })
            }
            is LensSwitchResult -> {
                json.put("cmd", "LENS_SWITCH_RESULT")
                json.put("lensId", lensId)
                json.put("status", status)
                activeLensId?.let { json.put("activeLensId", it) }
                message?.let { json.put("message", it) }
            }
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ControlCommand? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.getString("cmd")) {
                    "TORCH" -> Torch(enabled = json.getBoolean("enabled"))
                    "TORCH_REPORT" -> TorchReport(enabled = json.getBoolean("enabled"))
                    "PTT_START" -> PttStart
                    "PTT_STOP" -> PttStop
                    "RENAME" -> Rename(name = json.getString("name"))
                    "BATTERY" -> Battery(
                        percentage = json.getInt("pct"),
                        isCharging = json.optBoolean("charging", false)
                    )
                    "QUERY_BATTERY" -> QueryBattery
                    "SET_NIGHT_MODE" -> SetNightMode(mode = json.getString("mode"))
                    "NIGHT_MODE_REPORT" -> NightModeReport(
                        mode = json.getString("mode"),
                        isNightActive = json.optBoolean("active", false)
                    )
                    "FPS_REPORT" -> FpsReport(fps = json.getInt("fps"))
                    "SWITCH_LENS" -> SwitchLens(lensId = json.getString("lensId"))
                    "LENS_LIST_REPORT" -> LensListReport(
                        activeLensId = json.optString("activeLensId"),
                        lenses = parseLensArray(json.optJSONArray("lenses"))
                    )
                    "LENS_SWITCH_RESULT" -> LensSwitchResult(
                        lensId = json.optString("lensId"),
                        status = json.optString("status", "FAILED"),
                        activeLensId = json.optString("activeLensId").takeIf { it.isNotEmpty() },
                        message = json.optString("message").takeIf { it.isNotEmpty() }
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun parseLensArray(arr: JSONArray?): List<CameraLensInfo> {
            if (arr == null) return emptyList()
            val out = mutableListOf<CameraLensInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    CameraLensInfo(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        lensFacing = o.optString("facing", "back"),
                        focalLength35eq = o.optDouble("focal35", 0.0).toFloat(),
                        zoomFactor = o.optDouble("zoom", 1.0).toFloat(),
                        isDefault = o.optBoolean("def", false),
                        available = o.optBoolean("avail", true),
                        mid = o.optString("mid").takeIf { it.isNotEmpty() }
                    )
                )
            }
            return out
        }
    }
}

/**
 * Log entry for diagnostics.
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /**
     * Milliseconds since process start.
     *
     * Wall-clock time alone is not trustworthy here: roaming between campus
     * subnets triggers an NTP correction, and a jump backwards makes the log
     * timeline read as if events happened out of order. The monotonic offset is
     * immune to that, so a stall or a gap can still be measured accurately.
     */
    val elapsedMs: Long = 0L,
    /**
     * Name of the thread that emitted the line.
     *
     * This is the single most useful field when diagnosing a freeze — without it
     * "the main thread was busy" can only be inferred indirectly from a timeout
     * message somewhere else in the log.
     */
    val thread: String = ""
) {
    fun format(): String {
        val time = FORMATTER.get()!!.format(java.util.Date(timestamp))
        val rel = String.format(java.util.Locale.US, "%.1fs", elapsedMs / 1000.0)
        val threadPart = if (thread.isEmpty()) "" else " {$thread}"
        return "[$time +$rel] [${level.name}] [$tag]$threadPart $message"
    }

    companion object {
        /**
         * SimpleDateFormat is not thread-safe and constructing one per line was
         * measurably wasteful (every one of the ~400 call sites paid for it).
         * A ThreadLocal keeps one instance per writer thread instead.
         */
        private val FORMATTER = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US
            )
        }
    }
}
