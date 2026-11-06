package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.a0soft.gphone.camonitor.data.model.CameraLensInfo
import com.a0soft.gphone.camonitor.data.model.NightModeSetting
import com.a0soft.gphone.camonitor.log.AppLogger
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams exactly ONE physical lens at a time and switches it on demand.
 *
 * Why single-path:
 *
 *  - CameraX only guarantees two concurrent use cases per device, and vendors
 *    (MTK/Qualcomm BSPs) frequently refuse a third physical camera. Opening one
 *    capturer per lens produced "track LIVE, picture black" for every lens once
 *    a third one was added.
 *  - With one sensor at a time the encoder/ISP budget belongs entirely to the
 *    selected lens, so the picture is also sharper.
 *
 * Switching keeps the WebRTC pipeline untouched: the [VideoSource]/[VideoTrack]
 * live in WebRtcManager and are reused, only the camera sensor and its
 * SurfaceTextureHelper are torn down and rebuilt. No renegotiation, no reconnect,
 * the viewer keeps rendering the same remote track.
 *
 * Threading contract:
 *
 *  - Every camera operation runs on a single worker thread (`LensStream-ops`),
 *    so open/switch/stop can never interleave.
 *  - [stop] is the only exception: it cuts the frame flow synchronously on the
 *    calling thread (normally the main thread) and queues the disposal, because
 *    the owner disposes the VideoSource right after and must not race a capturer
 *    that is still pushing frames.
 *  - Callbacks ([SwitchOutcome], [onActiveLensChanged], ...) fire on the worker
 *    thread; owners that touch UI/DataChannel state must post to their own looper.
 */
class LensStreamController(
    private val context: Context,
    private val preferNightMode: Boolean = true
) {

    companion object {
        private const val TAG = "LensStream"

        /** A sensor that binds but never emits is indistinguishable from a dead one. */
        private const val FIRST_FRAME_TIMEOUT_MS = 4_000L

        /**
         * How long the surface may take to arrive after a successful bind.
         *
         * Normally it lands within tens of milliseconds (26 ms in a good run).
         * When the main thread is blocked the surface-provider callback never
         * runs, and the old code paid the full [STALL_TIMEOUT_MS] before noticing
         * — 9.9 s of a black screen. Failing at 2.5 s turns that into a fast,
         * correctly-labelled failure that the owner can rebuild from.
         */
        private const val SURFACE_TIMEOUT_MS = 2_500L

        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        /** Frames must arrive at least this often or the pipeline is considered stalled. */
        private const val STALL_TIMEOUT_MS = 9_000L

        private const val FRAME_PROBE_MS = 300L

        /** Synthetic lens id used when CameraManager exposes nothing enumerable. */
        const val DEFAULT_LENS_ID = "default"
    }

    enum class SwitchStatus { OK, ALREADY_ACTIVE, BUSY, FAILED }

    data class SwitchOutcome(
        val lensId: String,
        val status: SwitchStatus,
        val message: String? = null
    )

    private enum class State { IDLE, RUNNING, SWITCHING, STOPPED }

    private class Slot(val info: CameraLensInfo) {
        @Volatile
        var available: Boolean = true
    }

    private val ops = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LensStream-ops").apply { isDaemon = true }
    }

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LensStream-health").apply { isDaemon = true }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Populated on the worker thread by [start] / [doSwitch]; read via [lensList]. */
    private val slots = ArrayList<Slot>()

    @Volatile
    private var snapshot: List<CameraLensInfo> = emptyList()

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var activeId: String? = null

    val activeLensId: String? get() = activeId

    /** Lens facing of the streaming sensor ("back" / "front" / "external"). */
    val activeLensFacing: String?
        get() = activeId?.let { id -> snapshot.firstOrNull { it.id == id }?.lensFacing }

    val isRunning: Boolean get() = state == State.RUNNING && capturer != null

    // Written by the ops thread, read by the health watchdog: must be volatile.
    @Volatile
    private var capturer: CameraXVideoCapturer? = null

    @Volatile
    private var helper: SurfaceTextureHelper? = null

    private var observer: CapturerObserver? = null
    private var eglContext: EglBase.Context? = null
    private var width = 1280
    private var height = 720
    private var fps = 20

    @Volatile
    private var nightSetting: NightModeSetting = NightModeSetting.AUTO

    @Volatile
    private var torchEnabled = false

    private val switching = AtomicBoolean(false)
    private val sessionToken = AtomicInteger(0)

    @Volatile
    private var stopping = false

    private var lastFrameCount = -1
    private var lastFrameAt = 0L

    var onFpsDetermined: ((Int) -> Unit)? = null
    var onNightModeChanged: ((Boolean) -> Unit)? = null

    /** Fired when a lens turns out to be unusable (bind failure). */
    var onLensListChanged: (() -> Unit)? = null

    /** Fired when the STREAMING lens died and nothing is being captured any more. */
    var onCameraLost: (() -> Unit)? = null

    /** Fired whenever the streaming lens changes (initial open, switch, recovery). */
    var onActiveLensChanged: ((String?) -> Unit)? = null

    init {
        watchdog.scheduleWithFixedDelay(
            { runCatching { healthCheck() } },
            HEALTH_CHECK_INTERVAL_MS,
            HEALTH_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    /** Lenses known to this controller, with their current availability. */
    fun lensList(): List<CameraLensInfo> = snapshot

    fun setNightMode(setting: NightModeSetting) {
        nightSetting = setting
        ops.execute { runCatching { capturer?.setNightModeSetting(setting) } }
    }

    fun setTorch(enabled: Boolean) {
        torchEnabled = enabled
        ops.execute { runCatching { capturer?.setTorch(enabled) } }
    }

    /**
     * Enumerates the lenses and opens the preferred one.
     *
     * [onResult] is invoked on the main thread with true when a sensor is
     * streaming (bound, first frame not yet guaranteed), false when nothing could
     * be opened.
     */
    fun start(
        observer: CapturerObserver,
        eglContext: EglBase.Context,
        width: Int,
        height: Int,
        fps: Int,
        onResult: (Boolean) -> Unit
    ) {
        this.observer = observer
        this.eglContext = eglContext
        this.width = width
        this.height = height
        this.fps = fps
        stopping = false
        sessionToken.incrementAndGet()

        ops.execute {
            if (slots.isEmpty()) loadSlots()
            publishSnapshot()
            if (slots.isEmpty()) {
                AppLogger.e(TAG, "No camera available — lens stream cannot start")
                state = State.IDLE
                mainHandler.post { onResult(false) }
                return@execute
            }

            var opened = false
            for (slot in preferredOrder()) {
                if (stopping) break
                if (!openLens(slot)) continue

                // Binding alone proves nothing: CameraX delivers the surface
                // asynchronously on the main thread, and a blocked main thread
                // means a bound session that never emits a frame. This path used
                // to hand off straight to `state = RUNNING`, so the failure only
                // surfaced via the 9 s frame watchdog with no indication why.
                val target = capturer
                if (target != null && !target.awaitSurface(SURFACE_TIMEOUT_MS)) {
                    AppLogger.e(
                        TAG,
                        "Lens ${slot.info.id} bound but no surface after ${SURFACE_TIMEOUT_MS}ms " +
                            "— the main thread is likely blocked; aborting this start"
                    )
                    releaseCapturer()
                    activeId = null
                    // Every other lens would fail the same way, so stop here.
                    break
                }

                opened = true
                break
            }
            state = if (opened) State.RUNNING else State.IDLE
            if (opened) resetFrameClock()
            AppLogger.i(TAG, "Lens stream start: opened=$opened active=$activeId")
            mainHandler.post { onResult(opened) }
        }
    }

    /**
     * Switches the streaming sensor. Never blocks the caller.
     *
     * [onOutcome] fires on the worker thread — even for the BUSY rejection — so
     * every request gets exactly one answer.
     */
    fun switchTo(lensId: String, onOutcome: (SwitchOutcome) -> Unit) {
        if (!switching.compareAndSet(false, true)) {
            AppLogger.w(TAG, "Switch to $lensId rejected: another switch is already queued/running")
            onOutcome(SwitchOutcome(lensId, SwitchStatus.BUSY))
            return
        }
        ops.execute {
            val outcome = try {
                doSwitch(lensId)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Lens switch to $lensId crashed: ${t.message}")
                state = State.IDLE
                SwitchOutcome(lensId, SwitchStatus.FAILED, t.message ?: "switch crashed")
            } finally {
                switching.set(false)
            }
            onOutcome(outcome)
        }
    }

    /**
     * Stops the sensor immediately and disposes it asynchronously.
     *
     * The synchronous part is [CameraXVideoCapturer.stopCapture]: it unbinds the
     * preview and detaches the SurfaceTexture listener, so the owner of the
     * VideoSource can dispose it right after this call returns without a frame
     * landing in a dead source.
     */
    fun stop() {
        stopping = true
        sessionToken.incrementAndGet()
        state = State.STOPPED
        switching.set(false)

        val oldCapturer = capturer
        val oldHelper = helper
        capturer = null
        helper = null
        activeId = null

        if (oldCapturer != null) {
            runCatching { oldCapturer.stopCapture() }
        }
        ops.execute {
            if (oldCapturer != null) runCatching { oldCapturer.dispose() }
            if (oldHelper != null) runCatching { oldHelper.dispose() }
            AppLogger.i(TAG, "Lens stream stopped")
        }
    }

    /** Releases the worker threads. The controller is unusable afterwards. */
    fun shutdown() {
        runCatching { watchdog.shutdownNow() }
        runCatching { ops.shutdown() }
    }

    // ------------------------------------------------------------------
    // worker-thread internals
    // ------------------------------------------------------------------

    private fun loadSlots() {
        val discovered = CameraLensEnumerator.enumerate(context)
        if (discovered.isEmpty()) {
            // No enumerable camera: fall back to CameraX's own default selector so
            // the stream still starts on devices with an unusual camera stack.
            AppLogger.w(TAG, "Camera enumeration returned nothing — using CameraX default back camera")
            slots.add(
                Slot(
                    CameraLensInfo(
                        id = DEFAULT_LENS_ID,
                        label = "1x",
                        lensFacing = "back",
                        focalLength35eq = 0f,
                        zoomFactor = 1f,
                        isDefault = true
                    )
                )
            )
            return
        }
        discovered.forEach { slots.add(Slot(it)) }
        AppLogger.i(TAG, "Discovered ${slots.size} lens(es): " +
            slots.joinToString { "${it.info.label}(${it.info.id})" })
    }

    /** Last-used lens first, then the hardware default, then the rest in order. */
    private fun preferredOrder(): List<Slot> {
        val lastUsed = activeId
        return slots.sortedWith(
            compareBy<Slot> {
                when {
                    lastUsed != null && it.info.id == lastUsed -> 0
                    it.info.isDefault -> 1
                    else -> 2
                }
            }
        )
    }

    private fun doSwitch(targetId: String): SwitchOutcome {
        val slot = slots.firstOrNull { it.info.id == targetId }
            ?: return SwitchOutcome(targetId, SwitchStatus.FAILED, "unknown lens")

        if (stopping || state == State.STOPPED) {
            return SwitchOutcome(targetId, SwitchStatus.FAILED, "stream stopped")
        }

        // Re-selecting the live lens is a no-op ONLY while it really delivers
        // frames; a frozen pipeline answers the same tap with a forced reopen.
        val current = capturer
        if (state == State.RUNNING && current != null && activeId == targetId && framesAdvancing(current)) {
            return SwitchOutcome(targetId, SwitchStatus.ALREADY_ACTIVE)
        }

        val previousSlot = slots.firstOrNull { it.info.id == activeId }
        val token = sessionToken.get()
        state = State.SWITCHING
        AppLogger.i(TAG, "Switching lens ${activeId ?: "none"} -> $targetId")

        releaseCapturer()
        activeId = null

        if (stopping || token != sessionToken.get()) {
            state = State.STOPPED
            return SwitchOutcome(targetId, SwitchStatus.FAILED, "stream stopped")
        }

        val opened = openLens(slot)
        if (opened && capturer != null && awaitFirstFrame(capturer!!, FIRST_FRAME_TIMEOUT_MS)) {
            state = State.RUNNING
            resetFrameClock()
            AppLogger.i(TAG, "Lens switch complete: $targetId")
            return SwitchOutcome(targetId, SwitchStatus.OK)
        }

        val reason = "no frame within ${FIRST_FRAME_TIMEOUT_MS}ms"
        AppLogger.w(TAG, "Lens switch to $targetId failed ($reason); rolling back")
        releaseCapturer()
        activeId = null

        var restored = false
        if (previousSlot != null && previousSlot.info.id != targetId && !stopping) {
            restored = openLens(previousSlot) &&
                capturer != null && awaitFirstFrame(capturer!!, FIRST_FRAME_TIMEOUT_MS)
        }
        state = if (restored) State.RUNNING else State.IDLE
        if (restored) {
            resetFrameClock()
        } else {
            // Nothing is streaming and there is nothing to roll back to. Tell the
            // owner, otherwise the camera stays dark until the app is restarted
            // (the frame watchdog only watches a RUNNING pipeline).
            onActiveLensChanged?.invoke(null)
            if (!stopping) onCameraLost?.invoke()
        }
        AppLogger.w(TAG, "Lens switch rollback: restored=${activeId ?: "none"}")
        return SwitchOutcome(targetId, SwitchStatus.FAILED, reason)
    }

    private fun openLens(slot: Slot): Boolean {
        val ob = observer ?: return false
        val egl = eglContext ?: return false
        if (stopping) return false

        val token = sessionToken.get()
        val lens = slot.info
        val cameraId = lens.id.takeIf { it != DEFAULT_LENS_ID }

        val newCapturer = CameraXVideoCapturer(
            context = context,
            preferNightMode = preferNightMode,
            targetCameraId = cameraId,
            allowNightExtension = lens.isDefault
        )
        val newHelper = SurfaceTextureHelper.create("CaptureThread-${lens.id}", egl)

        newCapturer.onFpsDetermined = { determined -> onFpsDetermined?.invoke(determined) }
        newCapturer.onNightModeChanged = { active -> onNightModeChanged?.invoke(active) }
        newCapturer.onCameraError = { reason ->
            AppLogger.w(TAG, "Lens ${lens.label}(${lens.id}) reported an error: $reason")
            slot.available = false
            publishSnapshot()
            // Only the lens that is currently streaming can invalidate the session.
            if (capturer === newCapturer) {
                val wasRunning = state == State.RUNNING
                state = State.IDLE
                activeId = null
                onActiveLensChanged?.invoke(null)
                // While SWITCHING the rollback owns the recovery; asking the owner
                // for a full pipeline rebuild at the same time would fight it.
                if (!stopping && wasRunning) onCameraLost?.invoke()
            }
        }

        // Apply the persisted night mode before the first bind; setNightModeSetting
        // stores the value even when the capturer is not capturing yet.
        newCapturer.setNightModeSetting(nightSetting)

        return try {
            newCapturer.initialize(newHelper, context, ob)
            newCapturer.startCapture(width, height, fps)

            if (stopping || token != sessionToken.get()) {
                runCatching { newCapturer.dispose() }
                runCatching { newHelper.dispose() }
                return false
            }

            capturer = newCapturer
            helper = newHelper
            activeId = lens.id
            if (torchEnabled) runCatching { newCapturer.setTorch(true) }
            AppLogger.i(TAG, "Lens opened: ${lens.label} (cameraId=${lens.id})")
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to open lens ${lens.id}: ${t.message}")
            slot.available = false
            publishSnapshot()
            runCatching { newCapturer.dispose() }
            runCatching { newHelper.dispose() }
            false
        }
    }

    /** Releases the sensor + its capture thread. The WebRTC source/track stay alive. */
    private fun releaseCapturer() {
        val oldCapturer = capturer
        val oldHelper = helper
        capturer = null
        helper = null
        if (oldCapturer != null) {
            runCatching { oldCapturer.stopCapture() }
            runCatching { oldCapturer.dispose() }
        }
        if (oldHelper != null) {
            runCatching { oldHelper.dispose() }
        }
        lastFrameAt = 0L
        lastFrameCount = -1
    }

    private fun awaitFirstFrame(target: CameraXVideoCapturer, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val token = sessionToken.get()
        while (System.currentTimeMillis() < deadline) {
            if (target.framesCaptured > 0) return true
            if (stopping || token != sessionToken.get()) return false
            try {
                Thread.sleep(80L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (target.framesCaptured > 0) return true
        // Two very different failures used to share one message. "No surface"
        // points at a blocked main thread; "surface but no frames" points at the
        // sensor/HAL. Guessing wrong sends the next debugging session the wrong way.
        AppLogger.w(
            TAG,
            if (!target.isSurfaceGranted) {
                "No frame within ${timeoutMs}ms: surface was never granted (main thread blocked?)"
            } else {
                "No frame within ${timeoutMs}ms although the surface was granted (sensor/HAL stall)"
            }
        )
        return false
    }

    private fun framesAdvancing(target: CameraXVideoCapturer): Boolean {
        val before = target.framesCaptured
        return try {
            Thread.sleep(FRAME_PROBE_MS)
            target.framesCaptured > before
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun resetFrameClock() {
        lastFrameCount = capturer?.framesCaptured ?: 0
        lastFrameAt = System.currentTimeMillis()
    }

    private fun publishSnapshot() {
        snapshot = slots.map { it.info.copy(available = it.available) }
        mainHandler.post { runCatching { onLensListChanged?.invoke() } }
    }

    /**
     * Detects a sensor that bound successfully and then went silent.
     *
     * This is the failure mode that made "connected, audio only, black screen"
     * survive a reconnect: the track stays LIVE, the state flag stays true, and
     * only actual frame counting reveals the pipeline is dead.
     */
    private fun healthCheck() {
        if (state != State.RUNNING || stopping || switching.get()) return
        val current = capturer ?: return
        val frames = current.framesCaptured
        val now = System.currentTimeMillis()

        if (lastFrameAt == 0L) {
            lastFrameCount = frames
            lastFrameAt = now
            return
        }
        if (frames != lastFrameCount) {
            lastFrameCount = frames
            lastFrameAt = now
            return
        }
        if (now - lastFrameAt < STALL_TIMEOUT_MS) return

        AppLogger.w(TAG, "No camera frame for ${now - lastFrameAt}ms on lens ${activeId ?: "?"} — forcing reopen")
        if (!switching.compareAndSet(false, true)) return
        ops.execute {
            try {
                reopenActive()
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Camera pipeline recovery failed: ${t.message}")
                state = State.IDLE
            } finally {
                switching.set(false)
            }
        }
    }

    private fun reopenActive() {
        val slot = slots.firstOrNull { it.info.id == activeId }
            ?: slots.firstOrNull { it.info.isDefault }
            ?: slots.firstOrNull()
            ?: return

        releaseCapturer()
        activeId = null
        if (openLens(slot) && capturer != null && awaitFirstFrame(capturer!!, FIRST_FRAME_TIMEOUT_MS)) {
            state = State.RUNNING
            resetFrameClock()
            AppLogger.i(TAG, "Camera pipeline recovered on lens ${activeId ?: slot.info.id}")
        } else {
            state = State.IDLE
            AppLogger.e(TAG, "Camera pipeline recovery failed on lens ${slot.info.id}")
        }
        onActiveLensChanged?.invoke(activeId)
    }
}
