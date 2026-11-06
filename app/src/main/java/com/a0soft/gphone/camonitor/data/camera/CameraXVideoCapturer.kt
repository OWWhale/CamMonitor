package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.a0soft.gphone.camonitor.data.model.NightModeSetting
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraXVideoCapturer(
    private val context: Context,
    private val preferNightMode: Boolean = true,
    /**
     * Camera2 id of the physical lens this capturer drives.
     *
     * When null the capturer falls back to the default back camera. When set, the
     * capturer binds exactly that lens, which is what lets several capturers run
     * in parallel — one per physical lens.
     */
    private val targetCameraId: String? = null,

    /**
     * Whether the NIGHT CameraX extension may be applied to this lens.
     *
     * ExtensionsManager builds an extension-enabled selector from the base
     * selector; on many devices it only supports the main back camera and throws
     * (or silently drops the CameraFilter) for ultra-wide / tele / front lenses.
     * Enabling it on every lens would make all secondary lenses fail to bind as
     * soon as night mode is active, so LensStreamController enables it only on the
     * default lens.
     */
    private val allowNightExtension: Boolean = true
) : VideoCapturer {

    companion object {
        /**
         * Ambient light at which the NIGHT pipeline kicks in.
         *
         * Raised from 10 lx to 5 lx: the CameraX NIGHT extension combines several
         * exposures per frame, which is the single hottest thing the camera can do
         * on this hardware. A typical dim room sits at 10-30 lx and used to keep
         * the extension engaged all night; now it only engages in near-darkness.
         */
        const val LUX_THRESHOLD_NIGHT_ENTER = 5.0f
        const val LUX_THRESHOLD_NIGHT_EXIT = 15.0f
        const val LIGHT_DEBOUNCE_DELAY_MS = 1500L

        fun selectClosestFps(
            availablePairs: List<Pair<Int, Int>>,
            targetFps: Int = 20
        ): Pair<Int, Int> {
            if (availablePairs.isEmpty()) return Pair(targetFps, targetFps)
            val valid = availablePairs.filter { it.first > 0 && it.second >= it.first }
            if (valid.isEmpty()) return availablePairs[0]

            return valid.minWith(
                compareBy<Pair<Int, Int>> { kotlin.math.abs(it.second - targetFps) }
                    .thenBy { it.first }
            )
        }

        fun selectClosestSupportedFpsRange(
            availableRanges: Array<Range<Int>>?,
            targetFps: Int = 20
        ): Range<Int> {
            if (availableRanges.isNullOrEmpty()) {
                return Range(targetFps, targetFps)
            }
            val validRanges = availableRanges.filter {
                val l = it.lower
                val u = it.upper
                l != null && u != null && l > 0 && u >= l
            }
            if (validRanges.isEmpty()) return availableRanges[0]

            return validRanges.minWith(
                compareBy<Range<Int>> { kotlin.math.abs(it.upper - targetFps) }
                    .thenBy { it.lower }
            )
        }
    }

    /** Diagnostic: counts frames delivered by CameraX into the WebRTC source. */
    private val capturedFrameCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Number of frames this lens has handed to WebRTC so far.
     *
     * Exposed so [LensStreamController] can tell a lens that merely *bound* from
     * one that is actually delivering pictures. A bound-but-silent lens is the
     * "connected, audio only, black screen" case.
     */
    val framesCaptured: Int get() = capturedFrameCount.get()

    /**
     * When CameraX actually handed us a surface, in elapsed-realtime millis.
     *
     * `startCapture()` returning true only means the session was *bound*; the
     * surface arrives later, on the main thread, through the surface-provider
     * callback. If the main thread is blocked that callback never runs, the
     * session stays bound with no frames, and the only symptom is a black screen
     * ten seconds later. Tracking the timestamp lets the controller fail fast
     * and say "no surface" instead of "no frames".
     */
    @Volatile
    var surfaceGrantedAt: Long = 0L
        private set

    val isSurfaceGranted: Boolean get() = surfaceGrantedAt > 0L

    /**
     * Blocks the caller (a lens-worker thread, never the main thread) until the
     * surface arrives or [timeoutMs] elapses.
     */
    fun awaitSurface(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (surfaceGrantedAt > 0L) return true
            if (!isCapturing.get()) return false
            try {
                Thread.sleep(50L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return surfaceGrantedAt > 0L
    }

    /** Tagged with the lens id so multi-lens logs are attributable. */
    private val tag = "CameraXVideoCapturer" + (targetCameraId?.let { "[$it]" } ?: "")
    private val mainHandler = Handler(Looper.getMainLooper())

    var finalFps: Int = 20
        private set

    private val _streamFpsFlow = MutableStateFlow(20)
    val streamFpsFlow: StateFlow<Int> = _streamFpsFlow.asStateFlow()

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surface: Surface? = null

    var currentTargetRotation: Int = Surface.ROTATION_0
        private set

    @Volatile
    var currentRotationDegrees: Int = 0
        private set

    private val capturerLifecycleOwner = CapturerLifecycleOwner()
    private val isCapturing = AtomicBoolean(false)
    private val captureSessionToken = java.util.concurrent.atomic.AtomicInteger(0)

    private var lastWidth: Int = 1280
    private var lastHeight: Int = 720
    private var lastFramerate: Int = 20
    private var isTorchActive: Boolean = false
    var nightModeSetting: NightModeSetting = NightModeSetting.AUTO
        private set

    fun setNightModeSetting(setting: NightModeSetting) {
        if (nightModeSetting == setting) return
        nightModeSetting = setting
        AppLogger.i(tag, "NightModeSetting changed to: $setting")
        mainHandler.post {
            if (!isCapturing.get()) return@post
            when (setting) {
                NightModeSetting.AUTO -> {
                    startLightMonitoring()
                    val shouldBeNight = currentLux < LUX_THRESHOLD_NIGHT_ENTER
                    bindCameraSession(enableNight = shouldBeNight)
                }
                NightModeSetting.NIGHT -> {
                    stopLightMonitoring()
                    bindCameraSession(enableNight = true)
                }
                NightModeSetting.NORMAL -> {
                    stopLightMonitoring()
                    bindCameraSession(enableNight = false)
                }
            }
        }
    }

    private val _isNightModeActiveFlow = MutableStateFlow(false)
    val isNightModeActiveFlow: StateFlow<Boolean> = _isNightModeActiveFlow.asStateFlow()
    val isNightModeActive: Boolean get() = _isNightModeActiveFlow.value

    var isNightExtensionSupported: Boolean = false
        private set

    var onNightModeChanged: ((Boolean) -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null
    var onFpsDetermined: ((Int) -> Unit)? = null

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private var lightSensorListener: SensorEventListener? = null
    private var currentLux: Float = Float.MAX_VALUE
    private var pendingNightMode: Boolean? = null
    private var nightSwitchRunnable: Runnable? = null
    private var orientationEventListener: OrientationEventListener? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        AppLogger.i(tag, "CameraXVideoCapturer initialized with SurfaceTextureHelper")

        // Pre-warm CameraX provider asynchronously without blocking
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                this.cameraProvider = provider
                val extManagerFuture = ExtensionsManager.getInstanceAsync(context, provider)
                extManagerFuture.addListener({
                    try {
                        this.extensionsManager = extManagerFuture.get()
                    } catch (e: Exception) {
                        AppLogger.w(tag, "Pre-warm ExtensionsManager failed: ${e.message}")
                    }
                }, mainExecutor)
            } catch (e: Exception) {
                AppLogger.w(tag, "Pre-warm ProcessCameraProvider failed: ${e.message}")
            }
        }, mainExecutor)
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: run {
            AppLogger.e(tag, "startCapture failed: SurfaceTextureHelper is null")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        if (!isCapturing.compareAndSet(false, true)) {
            AppLogger.w(tag, "startCapture called but already capturing")
            return
        }

        val currentToken = captureSessionToken.incrementAndGet()
        lastWidth = width
        lastHeight = height
        lastFramerate = framerate

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cachedProvider = cameraProvider
        val cachedExtManager = extensionsManager

        if (cachedProvider != null && cachedExtManager != null) {
            mainHandler.post {
                proceedStartSession(cachedProvider, cachedExtManager, helper, currentToken)
            }
        } else {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    this.cameraProvider = provider
                    val extManagerFuture = ExtensionsManager.getInstanceAsync(context, provider)
                    extManagerFuture.addListener({
                        try {
                            val extManager = extManagerFuture.get()
                            this.extensionsManager = extManager
                            proceedStartSession(provider, extManager, helper, currentToken)
                        } catch (e: Exception) {
                            if (currentToken == captureSessionToken.get()) {
                                AppLogger.e(tag, "ExtensionsManager initialization failed", e)
                                isCapturing.set(false)
                                capturerObserver?.onCapturerStarted(false)
                                onCameraError?.invoke(e.message ?: "ExtensionsManager init failed")
                            }
                        }
                    }, mainExecutor)
                } catch (e: Exception) {
                    if (currentToken == captureSessionToken.get()) {
                        AppLogger.e(tag, "ProcessCameraProvider initialization failed", e)
                        isCapturing.set(false)
                        capturerObserver?.onCapturerStarted(false)
                        onCameraError?.invoke(e.message ?: "ProcessCameraProvider init failed")
                    }
                }
            }, mainExecutor)
        }
    }

    private fun proceedStartSession(
        provider: ProcessCameraProvider,
        extManager: ExtensionsManager,
        helper: SurfaceTextureHelper,
        sessionToken: Int
    ) {
        if (sessionToken != captureSessionToken.get() || !isCapturing.get()) {
            AppLogger.w(tag, "proceedStartSession cancelled: capturer was stopped or restarted (token mismatch: expected=$sessionToken, current=${captureSessionToken.get()})")
            return
        }
        try {
            val initialRotation = getDeviceRotation()
            currentTargetRotation = initialRotation

            // Start WebRTC listening to incoming OpenGL texture frames
            helper.stopListening()
            helper.startListening { videoFrame ->
                val n = capturedFrameCount.incrementAndGet()
                if (n == 1 || n % 100 == 0) {
                    AppLogger.i(tag, "CameraX frames delivered: $n " +
                        "(${videoFrame.buffer.width}x${videoFrame.buffer.height}, rot=${videoFrame.rotation})")
                }
                val texBuffer = videoFrame.buffer as? TextureBufferImpl
                if (texBuffer != null) {
                    val (texRot, frameRot) = when (currentTargetRotation) {
                        Surface.ROTATION_90 -> Pair(-90, 0)
                        Surface.ROTATION_270 -> Pair(90, 0)
                        Surface.ROTATION_180 -> Pair(90, 90)
                        else -> Pair(-90, 90) // Surface.ROTATION_0 (Portrait)
                    }
                    val modifiedBuffer = if (texRot != 0) {
                        createTextureBufferWithModifiedTransformMatrix(
                            texBuffer,
                            textureRotationDegrees = texRot,
                            newWidth = texBuffer.width,
                            newHeight = texBuffer.height
                        )
                    } else {
                        texBuffer.retain()
                        texBuffer
                    }
                    val frame = VideoFrame(
                        modifiedBuffer,
                        frameRot,
                        videoFrame.timestampNs
                    )
                    capturerObserver?.onFrameCaptured(frame)
                    frame.release()
                } else {
                    capturerObserver?.onFrameCaptured(videoFrame)
                }
            }

            val baseSelector = resolveTargetSelector(provider)
            // Secondary lenses must not go through ExtensionsManager: on most
            // devices NIGHT is only implemented for the main back camera and
            // would either throw or silently discard our Camera2 id filter.
            val nightSupported = allowNightExtension &&
                runCatching { extManager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT) }
                    .getOrDefault(false)
            this.isNightExtensionSupported = nightSupported

            capturerLifecycleOwner.start()

            when (nightModeSetting) {
                NightModeSetting.AUTO -> {
                    if (preferNightMode) {
                        startLightMonitoring()
                    }
                    val initialNight = preferNightMode && currentLux < LUX_THRESHOLD_NIGHT_ENTER
                    bindCameraSession(enableNight = initialNight)
                }
                NightModeSetting.NIGHT -> {
                    bindCameraSession(enableNight = true)
                }
                NightModeSetting.NORMAL -> {
                    bindCameraSession(enableNight = false)
                }
            }

            startOrientationMonitoring()
            capturerObserver?.onCapturerStarted(true)
            AppLogger.i(tag, "CameraX capture started: ${lastWidth}x${lastHeight}@${lastFramerate}fps, rotation=$initialRotation (nightActive=$isNightModeActive, currentLux=$currentLux)")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error starting CameraX capture", e)
            isCapturing.set(false)
            stopOrientationMonitoring()
            stopLightMonitoring()
            capturerObserver?.onCapturerStarted(false)
            onCameraError?.invoke(e.message ?: "CameraX startCapture failed")
        }
    }

    /**
     * Builds the [CameraSelector] for this capturer.
     *
     * When a specific physical lens was requested we build a selector that matches
     * exactly that Camera2 id, so several capturers can hold different lenses at the
     * same time. Otherwise we fall back to the default back camera.
     */
    private fun resolveTargetSelector(provider: ProcessCameraProvider): CameraSelector {
        val wantedId = targetCameraId ?: return CameraSelector.DEFAULT_BACK_CAMERA
        val infos = provider.availableCameraInfos
        val bindable = infos.mapNotNull { cameraIdOf(it) }
        if (wantedId !in bindable) {
            // NEVER fall back to DEFAULT_BACK_CAMERA here. In multi-lens mode that
            // silently binds several "different" lenses to the same main camera:
            // the viewer gets N identical streams and the HAL hits a concurrent
            // open conflict. Throwing lets proceedStartSession() report this lens
            // as unavailable through onCameraError so it is hidden from the viewer.
            throw IllegalArgumentException(
                "cameraId=$wantedId is not bindable by CameraX (available=$bindable)"
            )
        }
        return CameraSelector.Builder()
            .addCameraFilter { candidates -> candidates.filter { cameraIdOf(it) == wantedId } }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun cameraIdOf(info: androidx.camera.core.CameraInfo): String? = runCatching {
        androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
    }.getOrNull()

    private fun bindCameraSession(enableNight: Boolean) {
        // A queued bind (night-mode switch, delayed lux runnable) must never run
        // after the capturer was stopped — it would re-open the sensor behind the
        // lens controller's back and fight the next lens for the hardware.
        if (!isCapturing.get()) {
            AppLogger.w(tag, "bindCameraSession skipped: capturer is not capturing")
            return
        }
        // Every bind gets a fresh surface: if the previous one was granted and
        // this bind never produces a new one, the stale timestamp would make the
        // watchdog believe the pipeline is healthy.
        surfaceGrantedAt = 0L
        val provider = cameraProvider ?: return
        val extManager = extensionsManager ?: return
        val helper = surfaceTextureHelper ?: return

        val baseSelector = resolveTargetSelector(provider)
        val targetSelector: CameraSelector
        val actuallyNight: Boolean

        if (enableNight) {
            if (isNightExtensionSupported) {
                // getExtensionEnabledCameraSelector() can still throw for a
                // selector carrying a custom CameraFilter even when
                // isExtensionAvailable() returned true. Degrade to the plain
                // selector rather than failing the whole lens bind.
                targetSelector = runCatching {
                    extManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                }.getOrElse { e ->
                    AppLogger.w(tag, "NIGHT extension selector failed (cameraId=$targetCameraId): ${e.message}; using standard selector")
                    baseSelector
                }
                AppLogger.i(tag, "Binding CameraX with ExtensionMode.NIGHT (cameraId=$targetCameraId)")
            } else {
                targetSelector = baseSelector
                AppLogger.i(tag, "Binding CameraX with Standard CameraSelector (applying Camera2 low-light fallback) (cameraId=$targetCameraId)")
            }
            actuallyNight = true
        } else {
            targetSelector = baseSelector
            actuallyNight = false
            AppLogger.i(tag, "Binding CameraX with Standard CameraSelector (Normal mode) (cameraId=$targetCameraId)")
        }

        // CameraX expresses resolutions in SENSOR coordinates, which are always
        // landscape (1280x720, 1920x1080…). They have nothing to do with how the
        // phone is held: swapping for portrait produces Size(720,1280), which
        // matches no 16:9 candidate, so CLOSEST_LOWER_THEN_HIGHER silently falls
        // back to 640x360 — or even 192x108 — and the whole stream turns to mush
        // while every log line still says "720p".
        //
        // Only normalise width >= height so an upside-down caller cannot poison
        // the request; the on-screen orientation is applied via targetRotation.
        val targetWidth = maxOf(lastWidth, lastHeight)
        val targetHeight = minOf(lastWidth, lastHeight)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    // CLOSEST_HIGHER_THEN_LOWER made CameraX pick the sensor's
                    // maximum (3264x1840 on this device). With several lenses
                    // open at once that is ~11 MP of preview per frame and the
                    // ISP/encoder starves: the video track stays LIVE but never
                    // delivers a frame. The stream is capped at 720p by
                    // VideoSource.adaptOutputFormat anyway, so a larger preview
                    // only burns bandwidth and hardware capacity.
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder()
            .setTargetRotation(currentTargetRotation)
            .setResolutionSelector(resolutionSelector)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        // Use THIS capturer's lens characteristics. Reading the first back camera
        // would apply the main camera's AE ranges to a telephoto lens, which does
        // not expose the same frame rate options.
        val characteristicsId = targetCameraId ?: cameraManager?.cameraIdList?.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
        val chars = try {
            cameraManager?.getCameraCharacteristics(characteristicsId)
        } catch (e: Exception) {
            AppLogger.w(tag, "Cannot read characteristics for camera $characteristicsId: ${e.message}")
            null
        }
        val availableFpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val selectedFpsRange = selectClosestSupportedFpsRange(availableFpsRanges, lastFramerate)
        // The closest range is usually something like [15,30] whose UPPER bound is
        // 30 — using it as-is made the sensor run at 30 fps even though the caller
        // asked for 20, paying ~50% more ISP + encoder power for frames the
        // encoder then drops. Cap the upper bound at the requested rate instead.
        val cappedUpper = selectedFpsRange.upper
            .coerceAtMost(lastFramerate)
            .coerceAtLeast(selectedFpsRange.lower)
        val effectiveFpsRange = Range(selectedFpsRange.lower, cappedUpper)
        finalFps = cappedUpper
        _streamFpsFlow.value = finalFps
        onFpsDetermined?.invoke(finalFps)
        AppLogger.i(tag, "Selected hardware AE FPS range: $selectedFpsRange -> $effectiveFpsRange, " +
            "finalFps=$finalFps (target=$lastFramerate)")

        if (enableNight && !isNightExtensionSupported) {
            AppLogger.i(tag, "Applying Camera2 low-light fallback")
            configureLowLightFallback(previewBuilder, chars, effectiveFpsRange)
        } else {
            previewBuilder.setTargetFpsRange(effectiveFpsRange)
        }

        val newPreview = previewBuilder.build()
        // Remember the PREVIOUS preview before overwriting the field. The unbind
        // below must target the session that is actually bound to the camera —
        // unbinding the new (not yet bound) preview would leave the old session
        // holding the hardware, which breaks concurrent multi-lens capture.
        val previousPreview = this.preview
        this.preview = newPreview

        val mainExecutor = ContextCompat.getMainExecutor(context)
        newPreview.setSurfaceProvider(mainExecutor) { request ->
            if (!isCapturing.get()) {
                request.willNotProvideSurface()
                return@setSurfaceProvider
            }
            val res = request.resolution
            surfaceGrantedAt = SystemClock.elapsedRealtime()
            AppLogger.i(tag, "CameraX Surface granted: ${res.width}x${res.height} " +
                "(requested=${targetWidth}x${targetHeight}, night=$enableNight)")
            helper.setTextureSize(res.width, res.height)
            val surfaceTexture = helper.surfaceTexture
            surfaceTexture.setDefaultBufferSize(res.width, res.height)

            this.surface?.release()
            val newSurface = Surface(surfaceTexture)
            this.surface = newSurface

            request.setTransformationInfoListener(mainExecutor) { transformationInfo ->
                currentRotationDegrees = transformationInfo.rotationDegrees
                AppLogger.i(tag, "TransformationInfo: rotationDegrees=${transformationInfo.rotationDegrees}, cropRect=${transformationInfo.cropRect}, targetRot=$currentTargetRotation")
            }
            request.provideSurface(newSurface, mainExecutor) { result ->
                AppLogger.d(tag, "CameraX Surface closed/invalidated: ${result.resultCode}")
                if (this.surface === newSurface) {
                    this.surface = null
                }
                newSurface.release()
            }
        }

        // Unbind ONLY our own use case.
        //
        // IMPORTANT: never call provider.unbindAll() here — when several lenses run
        // in parallel (one capturer per physical camera) that would tear down every
        // other lens' session as well. Unbinding the specific Preview instance keeps
        // the sibling lenses alive.
        previousPreview?.let { oldPreview ->
            try {
                provider.unbind(oldPreview)
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed unbinding previous preview: ${e.message}")
            }
        }
        camera = provider.bindToLifecycle(capturerLifecycleOwner, targetSelector, newPreview)
        _isNightModeActiveFlow.value = actuallyNight
        onNightModeChanged?.invoke(actuallyNight)

        if (isTorchActive) {
            camera?.cameraControl?.enableTorch(true)
        }
        AppLogger.i(tag, "Camera session bound: nightActive=$actuallyNight " +
            "(supported=$isNightExtensionSupported, requested=${targetWidth}x${targetHeight})")
    }

    private fun startLightMonitoring() {
        val sm = sensorManager ?: run {
            AppLogger.w(tag, "SensorManager not available")
            return
        }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: run {
            AppLogger.w(tag, "Ambient Light Sensor (Sensor.TYPE_LIGHT) not available on this device")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val lux = event?.values?.getOrNull(0) ?: return
                currentLux = lux
                handleLuxChanged(lux)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }
        lightSensorListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        AppLogger.i(tag, "Ambient Light monitoring started with sensor: ${sensor.name}")
    }

    private fun stopLightMonitoring() {
        lightSensorListener?.let {
            sensorManager?.unregisterListener(it)
            lightSensorListener = null
        }
        nightSwitchRunnable?.let {
            mainHandler.removeCallbacks(it)
            nightSwitchRunnable = null
        }
        pendingNightMode = null
    }

    private fun handleLuxChanged(lux: Float) {
        if (!isCapturing.get()) return
        val isCurrentlyNight = _isNightModeActiveFlow.value
        val shouldBeNight = when {
            lux < LUX_THRESHOLD_NIGHT_ENTER -> true
            lux > LUX_THRESHOLD_NIGHT_EXIT -> false
            else -> isCurrentlyNight // Within hysteresis deadband [10, 25] lux
        }

        if (shouldBeNight == isCurrentlyNight) {
            nightSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
            nightSwitchRunnable = null
            pendingNightMode = null
            return
        }

        if (pendingNightMode == shouldBeNight) {
            return
        }

        nightSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNightMode = shouldBeNight

        val runnable = Runnable {
            nightSwitchRunnable = null
            pendingNightMode = null
            if (!isCapturing.get()) return@Runnable
            AppLogger.i(tag, "Ambient light threshold crossed (lux=$lux): switching Night mode -> $shouldBeNight")
            try {
                bindCameraSession(enableNight = shouldBeNight)
            } catch (e: Exception) {
                AppLogger.e(tag, "Error switching camera night mode to $shouldBeNight", e)
            }
        }
        nightSwitchRunnable = runnable
        mainHandler.postDelayed(runnable, LIGHT_DEBOUNCE_DELAY_MS)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun Preview.Builder.setTargetFpsRange(fpsRange: Range<Int>) {
        val extender = Camera2Interop.Extender(this)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            fpsRange
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun configureLowLightFallback(builder: Preview.Builder, chars: CameraCharacteristics?, fpsRange: Range<Int>) {
        if (chars == null) return
        val extender = Camera2Interop.Extender(builder)

        // 1. Scene Mode Night
        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (sceneModes?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
            AppLogger.i(tag, "Fallback: Applied CONTROL_SCENE_MODE_NIGHT")
        }

        // 2. Auto Exposure Priority for frame rate reduction in low light
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        if (aeModes?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // 3. Hardware-validated AE FPS Range
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        AppLogger.i(tag, "Fallback: Applied hardware-validated AE target FPS range: $fpsRange")

        // 4. Optical/Video Stabilization
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (oisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }

        // 5. High-quality / Fast Noise Reduction
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        when {
            nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true -> {
                extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
            }
            nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true -> {
                extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
            }
        }
    }

    private fun getDeviceRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation
            } catch (e: Exception) {
                null
            } ?: windowManager?.defaultDisplay?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation
        }

        if (rotation == null || rotation == Surface.ROTATION_0) {
            val config = context.resources.configuration
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return Surface.ROTATION_90
            }
        }
        return rotation ?: Surface.ROTATION_0
    }

    fun setTargetRotation(rotation: Int) {
        updateTargetRotation(rotation)
    }

    private fun updateTargetRotation(newRotation: Int) {
        if (!isCapturing.get()) return
        if (newRotation == currentTargetRotation) return
        currentTargetRotation = newRotation
        preview?.targetRotation = newRotation
        AppLogger.i(tag, "Orientation changed: currentTargetRotation=$newRotation (targetRotation updated on preview)")
    }

    private fun createTextureBufferWithModifiedTransformMatrix(
        buffer: TextureBufferImpl,
        textureRotationDegrees: Int,
        newWidth: Int,
        newHeight: Int
    ): VideoFrame.TextureBuffer {
        val transformMatrix = android.graphics.Matrix().apply {
            preTranslate(0.5f, 0.5f)
            preRotate(textureRotationDegrees.toFloat())
            preTranslate(-0.5f, -0.5f)
        }
        return buffer.applyTransformMatrix(transformMatrix, newWidth, newHeight)
    }

    private fun startOrientationMonitoring() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                updateTargetRotation(newRotation)
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationEventListener = listener
        }
    }

    private fun stopOrientationMonitoring() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    override fun stopCapture() {
        captureSessionToken.incrementAndGet()
        if (!isCapturing.getAndSet(false)) {
            return
        }

        stopLightMonitoring()
        stopOrientationMonitoring()

        val stopRunnable = Runnable {
            try {
                capturerLifecycleOwner.stop()
                // Unbind only our own preview so sibling lenses keep running.
                preview?.let { p ->
                    try {
                        cameraProvider?.unbind(p)
                    } catch (e: Exception) {
                        AppLogger.w(tag, "Failed unbinding preview on stop: ${e.message}")
                    }
                }
                camera = null
                preview = null

                surfaceTextureHelper?.stopListening()
                surface?.release()
                surface = null

                _isNightModeActiveFlow.value = false
                onNightModeChanged?.invoke(false)

                capturerObserver?.onCapturerStopped()
                AppLogger.i(tag, "CameraX capture stopped cleanly")
            } catch (e: Exception) {
                AppLogger.e(tag, "Error stopping CameraX capture", e)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopRunnable.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    stopRunnable.run()
                } finally {
                    latch.countDown()
                }
            }
            try {
                if (!latch.await(3, TimeUnit.SECONDS)) {
                    // The camera thread is stuck (usually a slow HAL teardown).
                    // Report it instead of blocking the caller any longer.
                    AppLogger.w(tag, "stopCapture timed out waiting for the main thread")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (isCapturing.get()) {
            stopCapture()
            startCapture(width, height, framerate)
        }
    }

    /** Guards against the double-dispose that used to freeze the main thread. */
    private val disposed = AtomicBoolean(false)

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            AppLogger.w(tag, "dispose() called twice — ignoring the second call")
            return
        }
        captureSessionToken.incrementAndGet()
        stopCapture()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            capturerLifecycleOwner.destroy()
        } else {
            mainHandler.post { capturerLifecycleOwner.destroy() }
        }
        surfaceTextureHelper = null
        capturerObserver = null
        AppLogger.i(tag, "CameraXVideoCapturer disposed")
    }

    override fun isScreencast(): Boolean = false

    fun setTorch(enabled: Boolean): Boolean {
        isTorchActive = enabled
        val control = camera?.cameraControl
        if (control != null) {
            control.enableTorch(enabled)
            AppLogger.i(tag, "Torch toggled via CameraControl: enabled=$enabled")
            return true
        }
        if (isCapturing.get()) {
            AppLogger.i(tag, "CameraControl not yet bound; stored isTorchActive=$enabled to apply on bind")
            return true
        }
        AppLogger.w(tag, "Cannot toggle torch: CameraControl is null and capturer not capturing")
        return false
    }

    private class CapturerLifecycleOwner : LifecycleOwner {
        private var lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry

        fun start() {
            if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
                lifecycleRegistry = LifecycleRegistry(this)
            }
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
