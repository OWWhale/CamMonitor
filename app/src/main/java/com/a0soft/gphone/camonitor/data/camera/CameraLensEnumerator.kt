package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import com.a0soft.gphone.camonitor.data.model.CameraLensInfo
import com.a0soft.gphone.camonitor.log.AppLogger

/**
 * Enumerates the physical cameras exposed by the device and turns them into
 * stable, human readable [CameraLensInfo] descriptors.
 *
 * The camera streams *one* lens at a time and lets the viewer pick which one at
 * runtime. This object only describes what the hardware offers — binding,
 * streaming and switching live in [LensStreamController].
 *
 * Zoom labels follow the usual photographic convention: the shortest back focal
 * length is the reference (1x), longer ones become 2x / 3x, and anything shorter
 * becomes 0.5x.
 */
object CameraLensEnumerator {

    private const val TAG = "CameraLensEnumerator"

    /** Below this equivalent focal length ratio the lens is an ultra-wide. */
    private const val ULTRA_WIDE_RATIO = 0.85f

    /** Above this ratio the lens is still treated as the 1x main camera. */
    private const val MAIN_LENS_RATIO = 1.15f

    /**
     * Returns every camera the device exposes, ordered so the default (main back)
     * camera comes first, then other back cameras by ascending zoom, then front
     * cameras.
     *
     * Never throws: on failure an empty list is returned and the caller falls back
     * to the legacy single-camera path.
     */
    fun enumerate(context: Context): List<CameraLensInfo> {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return emptyList()
            val ids = manager.cameraIdList
            if (ids.isNullOrEmpty()) {
                AppLogger.w(TAG, "CameraManager reports no camera devices")
                return emptyList()
            }

            val infos = mutableListOf<CameraLensInfo>()

            ids.forEach { id ->
                val chars = try {
                    manager.getCameraCharacteristics(id)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Cannot read characteristics for camera $id: ${e.message}")
                    return@forEach
                }
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val focal35 = equivalent35mmFocal(chars)
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                infos.add(
                    CameraLensInfo(
                        id = id,
                        label = "", // filled in below once the reference focal is known
                        lensFacing = facingName,
                        focalLength35eq = focal35,
                        zoomFactor = 1f,
                        isDefault = false
                    )
                )
            }

            // Camera2 ids conventionally start with the MAIN back camera (id "0").
            // Its focal length is the 1x reference. Using the SHORTEST back focal
            // length instead would make the ultra-wide the "1x" lens and mislabel
            // the real main camera as a telephoto — and picking the LONGEST one as
            // the default would open the telephoto as the primary stream.
            val mainBack = infos.firstOrNull { it.lensFacing == "back" }
                ?: return infos.mapIndexed { index, info ->
                    info.copy(label = if (info.lensFacing == "front") "Front" else "1x", isDefault = index == 0)
                }
            val reference = mainBack.focalLength35eq

            val withZoom = infos.map { info ->
                if (info.lensFacing == "back") {
                    val zoom = if (reference > 0f) info.focalLength35eq / reference else 1f
                    info.copy(zoomFactor = zoom)
                } else {
                    info.copy(zoomFactor = 1f)
                }
            }

            withZoom
                .map { it.copy(label = labelFor(it, reference), isDefault = it.id == mainBack.id) }
                // The default lens must occupy the FIRST video m-line, so it is
                // also the stream a single-transceiver peer would fall back to.
                .sortedWith(
                    compareByDescending<CameraLensInfo> { it.isDefault }
                        .thenBy { lensRank(it) }
                        .thenBy { it.zoomFactor }
                )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed enumerating cameras: ${e.message}")
            emptyList()
        }
    }

    /** Front cameras rank after back cameras; external cameras last. */
    private fun lensRank(lens: CameraLensInfo): Int = when (lens.lensFacing) {
        "back" -> 0
        "front" -> 1
        else -> 2
    }

    /**
     * Builds the display label.
     *
     * Back lenses get photographic zoom labels ("0.5x", "1x", "2x"), front lenses
     * get "Front", external lenses get "External".
     */
    private fun labelFor(lens: CameraLensInfo, reference: Float): String {
        return when (lens.lensFacing) {
            "front" -> "Front"
            "external" -> "External"
            else -> {
                val zoom = if (reference > 0f) lens.focalLength35eq / reference else 1f
                when {
                    zoom <= 0f -> "1x"
                    zoom < ULTRA_WIDE_RATIO -> formatUltraWide(zoom)
                    zoom < MAIN_LENS_RATIO -> "1x"
                    else -> formatTele(zoom)
                }
            }
        }
    }

    /** Ultra-wide keeps one decimal ("0.5x", "0.6x"); never snap it up to 1x. */
    private fun formatUltraWide(zoom: Float): String {
        val rounded = (kotlin.math.round(zoom * 10f) / 10f).coerceAtLeast(0.1f)
        return "${rounded}x"
    }

    private fun formatTele(zoom: Float): String {
        // Snap to conventional focal steps so 1.99x shows as "2x".
        val snapped = when {
            zoom < 1.6f -> 1f
            zoom < 2.4f -> 2f
            zoom < 3.4f -> 3f
            zoom < 4.6f -> 5f
            else -> 10f
        }
        return "${snapped.toInt()}x"
    }

    /**
     * Computes the 35mm-equivalent focal length.
     *
     * Uses the sensor's diagonal size (when available) so the result is directly
     * comparable across lenses of the same device.
     */
    private fun equivalent35mmFocal(chars: CameraCharacteristics): Float {
        val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return 0f
        val sensorSize: SizeF? = try {
            chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        } catch (e: Exception) {
            null
        }
        if (sensorSize == null || sensorSize.width <= 0f || sensorSize.height <= 0f) {
            return focalLength
        }
        // 35mm full frame diagonal is ~43.27mm.
        val sensorDiagonal = kotlin.math.sqrt(
            sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
        )
        if (sensorDiagonal <= 0f) return focalLength
        return focalLength * (43.27f / sensorDiagonal)
    }
}
