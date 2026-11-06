package com.a0soft.gphone.camonitor.data.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.a0soft.gphone.camonitor.data.camera.LensStreamController
import com.a0soft.gphone.camonitor.data.model.CameraLensInfo
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcManager(private val context: Context) {
    private val tag = "WebRtcManager"

    val eglBase: EglBase = EglBase.create()
    var peerConnectionFactory: PeerConnectionFactory? = null
        private set

    // Sender state (PA)
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    val activeLocalVideoTrack: VideoTrack? get() = localVideoTrack
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null

    /**
     * Single-path lens streaming.
     *
     * Only ONE physical lens is open at a time. The camera feeds a single
     * [VideoSource]/[VideoTrack] that stays alive for the whole session; a lens
     * switch tears only the sensor and its capture thread down and reopens another
     * one against the SAME source. Consequences:
     *
     *  - the negotiated video m-line never changes → no renegotiation on switch;
     *  - the encoder/ISP budget belongs to one lens, which is also what the
     *    hardware can actually sustain (CameraX guarantees two concurrent use
     *    cases, and vendor HALs often refuse a third physical camera).
     */
    private var lensController: LensStreamController? = null

    private val _availableLenses = MutableStateFlow<List<CameraLensInfo>>(emptyList())
    val availableLenses: StateFlow<List<CameraLensInfo>> = _availableLenses.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Active PeerConnections mapped by peerId (max 2 for PA)
    private val peerConnections = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
    private val peerConnectionStates = java.util.concurrent.ConcurrentHashMap<String, PeerConnection.PeerConnectionState>()
    private val dataChannels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()

    // Flows
    private val _incomingIceCandidates = MutableSharedFlow<Pair<String, IceCandidate>>(extraBufferCapacity = 32)
    val incomingIceCandidates: SharedFlow<Pair<String, IceCandidate>> = _incomingIceCandidates.asSharedFlow()

    private val _dataChannelCommands = MutableSharedFlow<Pair<String, ControlCommand>>(extraBufferCapacity = 32)
    val dataChannelCommands: SharedFlow<Pair<String, ControlCommand>> = _dataChannelCommands.asSharedFlow()

    private val _connectionState = MutableStateFlow<PeerConnection.PeerConnectionState>(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    private val _activeViewersCount = MutableStateFlow(0)
    val activeViewersCount: StateFlow<Int> = _activeViewersCount.asStateFlow()

    private val _streamFps = MutableStateFlow(20)
    val streamFps: StateFlow<Int> = _streamFps.asStateFlow()

    private val _remoteStreamFps = MutableStateFlow(20)
    val remoteStreamFps: StateFlow<Int> = _remoteStreamFps.asStateFlow()

    fun setRemoteFps(fps: Int) {
        _remoteStreamFps.value = fps
    }

    // Receiver state (PB & PA)
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    /**
     * Multi-lens receiver state.
     *
     * [remoteVideoTracks] holds every incoming video track keyed by the transceiver
     * mid it arrived on; [remoteLensByMid] maps that mid to the physical lens
     * descriptor published by the camera.
     */
    private val remoteVideoTracks = java.util.concurrent.ConcurrentHashMap<String, VideoTrack>()
    private val remoteLensByMid = java.util.concurrent.ConcurrentHashMap<String, CameraLensInfo>()
    private val _remoteLenses = MutableStateFlow<List<CameraLensInfo>>(emptyList())
    val remoteLenses: StateFlow<List<CameraLensInfo>> = _remoteLenses.asStateFlow()
    private val _activeLensId = MutableStateFlow<String?>(null)
    val activeLensId: StateFlow<String?> = _activeLensId.asStateFlow()

    /**
     * The camera may publish its lens list BEFORE the remote video tracks arrive
     * (the DataChannel opens before/independently of onTrack). In that case the
     * requested lens cannot be selected yet; remember it and retry on every
     * incoming track instead of silently settling for whichever one arrived first.
     */
    @Volatile
    private var pendingPreferredLensId: String? = null
    private val remoteAudioTracks = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>()
    private val activePttPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _isPttReceiving = MutableStateFlow(false)
    val isPttReceiving: StateFlow<Boolean> = _isPttReceiving.asStateFlow()
    private var isRemoteAudioEnabled = false
    private var remoteRenderer: SurfaceViewRenderer? = null
    @Volatile
    private var remoteVideoRotationDegrees: Int = 0

    /** Diagnostic: counts frames that actually reach the renderer. */
    private val renderedFrameCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val warnedNoRenderer = java.util.concurrent.atomic.AtomicBoolean(false)

    private val remoteVideoSink = VideoSink { frame ->
        val renderer = remoteRenderer
        if (renderer == null) {
            // A frame arriving with no renderer attached is exactly the
            // "connected, audio only, black screen" failure. Report it once.
            if (warnedNoRenderer.compareAndSet(false, true)) {
                AppLogger.w(tag, "remoteVideoSink: frame dropped because remoteRenderer is null")
            }
            return@VideoSink
        }
        val n = renderedFrameCount.incrementAndGet()
        if (n == 1 || n % 100 == 0) {
            AppLogger.i(tag, "Remote frames delivered to renderer: $n " +
                "(${frame.buffer.width}x${frame.buffer.height})")
        }
        val userRot = remoteVideoRotationDegrees
        if (userRot == 0) {
            renderer.onFrame(frame)
        } else {
            val adjustedRotation = ((frame.rotation + userRot) % 360 + 360) % 360
            val rotatedFrame = VideoFrame(frame.buffer, adjustedRotation, frame.timestampNs)
            renderer.onFrame(rotatedFrame)
        }
    }

    // Viewer PTT audio capture state (PB)
    private var viewerAudioSource: AudioSource? = null
    private var viewerAudioTrack: AudioTrack? = null
    private var isPttActive = false

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        AppLogger.i(tag, "WebRTC PeerConnectionFactory initialized")
    }

    // ----------------------------------------------------
    // SENDER (PA) SETUP
    // ----------------------------------------------------

    @Volatile
    private var isCameraCapturing = false

    /**
     * Guards against overlapping rebuilds.
     *
     * The health check at the top of [startCameraCapture] consults
     * `lensController.isRunning`, but a just-created controller only becomes
     * RUNNING when its async callback fires. Any call landing in that window —
     * and on a reconnect burst there are several, 35 ms apart — saw "unhealthy"
     * and tore down the pipeline its predecessor had only just built. That is the
     * `rebuilding (hadTrack=true) -> rebuilding (hadTrack=true)` triple that
     * produced a phantom "no camera could be opened" in the field log.
     */
    private val cameraStartLock = Any()
    private var cameraStartStartedAt = 0L

    private companion object {
        /**
         * Upper bound on how long a start may stay "in flight". If the controller
         * callback never arrives, recovery must not be blocked forever.
         */
        const val CAMERA_START_GUARD_MS = 15_000L
    }

    /** @return true when this caller owns the rebuild and must release it. */
    private fun beginCameraStart(): Boolean = synchronized(cameraStartLock) {
        val now = SystemClock.elapsedRealtime()
        val stillRunning = cameraStartStartedAt != 0L &&
            now - cameraStartStartedAt < CAMERA_START_GUARD_MS
        if (stillRunning) return false
        cameraStartStartedAt = now
        true
    }

    private fun endCameraStart() = synchronized(cameraStartLock) {
        cameraStartStartedAt = 0L
    }
    val isCameraCaptureActive: Boolean get() = isCameraCapturing
    var onCameraUnexpectedClosed: (() -> Unit)? = null

    private val _isNightModeActive = MutableStateFlow(false)
    val isNightModeActive: StateFlow<Boolean> = _isNightModeActive.asStateFlow()

    private val _nightModeSetting = MutableStateFlow(
        try {
            com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.getNightModeSetting()
        } catch (_: Exception) {
            com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO
        }
    )
    val nightModeSetting: StateFlow<com.a0soft.gphone.camonitor.data.model.NightModeSetting> = _nightModeSetting.asStateFlow()

    fun setNightModeSetting(setting: com.a0soft.gphone.camonitor.data.model.NightModeSetting) {
        _nightModeSetting.value = setting
        // Stored on the controller as well, so a later lens switch reopens the new
        // sensor with the same exposure mode instead of resetting to AUTO.
        lensController?.setNightMode(setting)
        try {
            com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.setNightModeSetting(setting)
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to persist night mode setting: ${e.message}")
        }
    }

    /**
     * Tears the camera pipeline down.
     *
     * Ordering matters: the controller stops the frame flow synchronously before
     * the VideoSource is disposed. Disposing the source while a capturer is still
     * pushing frames is what made quick reconnect cycles hang or crash.
     */
    private fun releaseCameraHardwareOnly() {
        isCameraCapturing = false
        _isNightModeActive.value = false
        _availableLenses.value = emptyList()

        val controller = lensController
        lensController = null
        if (controller != null) {
            runCatching { controller.stop() }
            runCatching { controller.shutdown() }
        }

        localRenderer?.let { renderer -> localVideoTrack?.removeSink(renderer) }
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        AppLogger.i(tag, "Camera hardware released")
    }

    /**
     * Starts (or revives) the camera pipeline.
     *
     * Idempotent, but the health pre-check is a REAL check: a non-null track plus
     * a true flag can both survive a dead pipeline (camera service killed, sensor
     * released by another app, encoder stalled). The old code trusted that flag,
     * so every reconnect after such a failure was a silent no-op — "多次重连无响应".
     */
    @Synchronized
    fun startCameraCapture(width: Int = 1280, height: Int = 720, fps: Int = 20) {
        val factory = peerConnectionFactory ?: return

        if (isCameraCapturing && localVideoTrack != null && lensController?.isRunning == true) {
            AppLogger.i(tag, "startCameraCapture: pipeline already healthy (lens=${lensController?.activeLensId})")
            return
        }

        // A rebuild in progress is not an unhealthy pipeline — it is an
        // unfinished one. Coalesce instead of tearing it down; the in-flight
        // start will report its own result to the owner.
        if (!beginCameraStart()) {
            AppLogger.i(tag, "startCameraCapture: rebuild already in flight, coalescing this request")
            return
        }

        AppLogger.i(tag, "startCameraCapture: rebuilding pipeline (hadTrack=${localVideoTrack != null}, flag=$isCameraCapturing)")
        try {
            buildCameraPipeline(factory, width, height, fps)
        } catch (t: Throwable) {
            // Any synchronous failure (native OOM on createVideoSource, a throwing
            // track factory…) must release the coalescing gate, otherwise every
            // start request for the next 15 seconds is silently swallowed.
            endCameraStart()
            AppLogger.e(tag, "startCameraCapture failed during setup", t)
        }
    }

    /**
     * The synchronous half of a rebuild. Ends by posting work to the lens
     * controller, whose callback releases the start gate via [endCameraStart].
     */
    private fun buildCameraPipeline(
        factory: PeerConnectionFactory,
        width: Int,
        height: Int,
        fps: Int
    ) {
        releaseCameraHardwareOnly()

        val vSource = factory.createVideoSource(false)
        videoSource = vSource
        vSource.adaptOutputFormat(width, height, fps)

        val track = factory.createVideoTrack("CAM_VIDEO_TRACK", vSource).apply { setEnabled(true) }
        localVideoTrack = track
        localRenderer?.let { track.addSink(it) }

        val controller = LensStreamController(context = context, preferNightMode = true)
        controller.onFpsDetermined = { determinedFps ->
            _streamFps.value = determinedFps
            runCatching { videoSource?.adaptOutputFormat(width, height, determinedFps) }
            peerConnections.values.forEach { pc -> configureVideoSenders(pc) }
            broadcastDataChannelCommand(ControlCommand.FpsReport(determinedFps))
        }
        controller.onNightModeChanged = { active ->
            _isNightModeActive.value = active
            broadcastDataChannelCommand(
                ControlCommand.NightModeReport(_nightModeSetting.value.name, active)
            )
        }
        // Callbacks arrive on the controller worker: hop to the main thread before
        // touching StateFlow / DataChannel state.
        controller.onLensListChanged = { mainHandler.post { publishLensList() } }
        controller.onActiveLensChanged = { lensId ->
            mainHandler.post {
                _activeLensId.value = lensId
                publishLensList()
            }
        }
        // The streaming sensor died (camera service killed, another app grabbed
        // it, HAL error). Clear the flag so the service's recovery hook can
        // actually restart the pipeline instead of trusting a stale "capturing".
        controller.onCameraLost = {
            mainHandler.post {
                if (lensController === controller) {
                    isCameraCapturing = false
                    AppLogger.w(tag, "Camera lost — requesting pipeline recovery")
                    onCameraUnexpectedClosed?.invoke()
                }
            }
        }
        controller.setNightMode(_nightModeSetting.value)
        runCatching {
            controller.setTorch(com.a0soft.gphone.camonitor.CamMonitorApp.instance.torchManager.isTorchOn.value)
        }

        lensController = controller
        isCameraCapturing = true
        setupLocalAudioTrack(factory)

        // A rebuild creates a NEW VideoTrack. Peer connections created before the
        // rebuild still hold the old (disposed) track, and the camera is the
        // answerer so it cannot renegotiate on its own — without this swap every
        // viewer would stay on a dead track after a recovery restart.
        attachVideoTrackToExistingPeers(track)

        controller.start(
            observer = vSource.capturerObserver,
            eglContext = eglBase.eglBaseContext,
            width = width,
            height = height,
            fps = fps
        ) { opened ->
            // Release the coalescing gate on BOTH outcomes: a failed start must
            // not block the recovery path that is about to retry it.
            endCameraStart()
            if (opened) {
                AppLogger.i(tag, "Lens stream active: lens=${controller.activeLensId}")
            } else {
                AppLogger.e(tag, "Lens stream failed: no camera could be opened")
                isCameraCapturing = false
                // Let the owner retry with backoff instead of leaving the camera
                // permanently dead until the process restarts.
                onCameraUnexpectedClosed?.invoke()
            }
            publishLensList()
        }
    }

    /**
     * Points an already-negotiated peer connection at a freshly created track.
     *
     * Swapping the track does not change the SDP, so no renegotiation is needed.
     * Only when a peer somehow has no video sender at all do we add a track —
     * that DOES need renegotiation and is therefore logged loudly, because the
     * camera cannot initiate one (it always answers).
     */
    private fun attachVideoTrackToExistingPeers(track: VideoTrack) {
        peerConnections.forEach { (peerId, pc) ->
            try {
                val sender = pc.senders?.firstOrNull { s ->
                    runCatching { s.track()?.kind() == "video" }.getOrDefault(false)
                }
                if (sender != null) {
                    sender.setTrack(track, false)
                    configureVideoSender(sender)
                    AppLogger.i(tag, "Swapped camera track on existing peer $peerId")
                } else {
                    val newSender = pc.addTrack(track, listOf("CAM_STREAM"))
                    configureVideoSender(newSender)
                    AppLogger.w(tag, "Peer $peerId had no video sender; added one (renegotiation required)")
                }
            } catch (t: Throwable) {
                AppLogger.w(tag, "Failed to swap camera track on peer $peerId: ${t.message}")
            }
        }
    }

    /** Creates the local audio track once, shared by every peer connection. */
    private fun setupLocalAudioTrack(factory: PeerConnectionFactory) {
        if (localAudioTrack != null) return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        audioSource = aSource
        localAudioTrack = factory.createAudioTrack("CAM_AUDIO_TRACK", aSource).apply {
            setEnabled(true)
        }
    }

    /**
     * Publishes the lens list + the currently streaming lens to every viewer.
     *
     * Single-path: the active lens is global (one sensor at a time), so there is
     * no per-viewer mapping to compute and no mid bookkeeping.
     */
    private fun publishLensList() {
        val controller = lensController
        val lenses = controller?.lensList().orEmpty()
        _availableLenses.value = lenses
        if (lenses.isEmpty()) return
        val activeId = controller?.activeLensId
            ?: lenses.firstOrNull { it.isDefault }?.id
            ?: lenses.first().id
        val report = ControlCommand.LensListReport(lenses, activeId)
        dataChannels.keys.forEach { peerId -> sendDataChannelCommand(peerId, report) }
    }

    /**
     * Handles a viewer's lens switch request.
     *
     * Every request is answered — OK, ALREADY_ACTIVE, BUSY or FAILED. A silent
     * refusal is what left the viewer's lens button permanently greyed out in the
     * previous build. The lens list is broadcast before the result so a second
     * viewer attached to the same camera also updates.
     */
    fun switchLens(lensId: String, requesterPeerId: String? = null) {
        val controller = lensController
        if (controller == null) {
            AppLogger.w(tag, "switchLens($lensId) ignored: camera is not streaming")
            requesterPeerId?.let {
                sendDataChannelCommand(
                    it,
                    ControlCommand.LensSwitchResult(lensId, "FAILED", null, "camera not streaming")
                )
            }
            return
        }

        AppLogger.i(tag, "Lens switch requested: $lensId (peer=$requesterPeerId, active=${controller.activeLensId})")
        controller.switchTo(lensId) { outcome ->
            mainHandler.post {
                val active = controller.activeLensId
                AppLogger.i(tag, "Lens switch result: ${outcome.lensId} -> ${outcome.status} " +
                    "(active=$active, reason=${outcome.message})")
                _activeLensId.value = active
                publishLensList()
                val target = requesterPeerId ?: dataChannels.keys.firstOrNull()
                target?.let {
                    sendDataChannelCommand(
                        it,
                        ControlCommand.LensSwitchResult(
                            lensId = outcome.lensId,
                            status = outcome.status.name,
                            activeLensId = active,
                            message = outcome.message
                        )
                    )
                }
            }
        }
    }

    /** Sends the current lens list to a single viewer (called after its answer). */
    fun publishLensListTo(peerId: String) {
        val controller = lensController ?: return
        val lenses = controller.lensList()
        if (lenses.isEmpty()) return
        val activeId = controller.activeLensId
            ?: lenses.firstOrNull { it.isDefault }?.id
            ?: lenses.first().id
        sendDataChannelCommand(peerId, ControlCommand.LensListReport(lenses, activeId))
    }

    fun configureVideoSender(sender: RtpSender?) {
        sender ?: return
        try {
            val parameters = sender.parameters ?: return
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            if (parameters.encodings != null) {
                // Single-path: one lens owns the whole uplink, so no per-lens split.
                // 2.5 Mbps keeps the previous conservative LAN profile; the encoder
                // is no longer shared between lenses, so the same cap now buys a
                // noticeably cleaner 720p.
                val maxBitrate = 2_500_000
                val minBitrate = 800_000
                parameters.encodings.forEach { encoding ->
                    encoding.scaleResolutionDownBy = 1.0
                    encoding.maxFramerate = _streamFps.value
                    encoding.minBitrateBps = minBitrate
                    encoding.maxBitrateBps = maxBitrate
                }
                AppLogger.i(tag, "Video sender budget: min=${minBitrate}bps, max=${maxBitrate}bps (single lens)")
            }
            val result = sender.setParameters(parameters)
            AppLogger.i(tag, "Configured video sender parameters: degradationPreference=MAINTAIN_RESOLUTION, maxFramerate=${_streamFps.value}, result=$result")
        } catch (e: Throwable) {
            AppLogger.w(tag, "Failed to configure video sender parameters: ${e.message}")
        }
    }

    fun configureVideoSenders(pc: PeerConnection?) {
        pc ?: return
        try {
            pc.senders?.forEach { sender ->
                try {
                    // A sender whose peer connection was torn down mid-rebuild is
                    // already disposed: it can neither be read nor configured, and
                    // it is not coming back. Skipping is the correct behaviour —
                    // the previous code let the exception escape the loop and
                    // abandoned every remaining sender as well.
                    val track = sender.track() ?: return@forEach
                    if (track.kind() == "video") configureVideoSender(sender)
                } catch (e: Throwable) {
                    AppLogger.d(tag, "Skipping unusable video sender: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            // The PeerConnection itself is closed/gone (normal during a rebuild).
            AppLogger.d(tag, "configureVideoSenders skipped, peer connection unusable: ${e.message}")
        }
    }

    fun stopCameraCapture() {
        try {
            // An explicit stop overrides any in-flight rebuild: without this the
            // next start would be coalesced into a run that no longer exists.
            endCameraStart()
            isCameraCapturing = false
            activePttPeers.clear()
            _isPttReceiving.value = false
            try {
                com.a0soft.gphone.camonitor.CamMonitorApp.instance.torchManager.resetState()
            } catch (_: Exception) {}
            peerConnections.values.forEach { it.dispose() }
            peerConnections.clear()
            dataChannels.values.forEach { it.dispose() }
            dataChannels.clear()
            remoteDescriptionSetPeers.clear()
            _activeViewersCount.value = 0

            releaseCameraHardwareOnly()

            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null

            AppLogger.i(tag, "Camera capture stopped and hardware released")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error stopping camera capture", e)
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        // The torch lives on whatever sensor is streaming: CameraX exposes it
        // through CameraControl, and the controller re-applies the last state on
        // every lens switch.
        val controller = lensController ?: return false
        controller.setTorch(enabled)
        val facing = controller.activeLensFacing
        if (enabled && facing != null && facing != "back") {
            // Keep the requested state (it applies once a back lens is selected)
            // but say so, otherwise the UI claims a torch that cannot light up.
            AppLogger.w(tag, "Torch requested on a $facing lens — no flash unit, state stored for the next back lens")
        }
        AppLogger.i(tag, "Torch $enabled applied to lens ${controller.activeLensId}")
        return true
    }

    // ----------------------------------------------------
    // PEER CONNECTION MANAGEMENT
    // ----------------------------------------------------

    /**
     * Formats an ICE candidate's SDP attribute as `type=<t>, <proto> <ip>:<port>`.
     *
     * SDP layout: `candidate:<foundation> <component> <transport> <priority>
     * <address> <port> typ <type> ...`. Only the first eight fields are needed;
     * anything shorter is malformed and reported as unknown rather than throwing.
     */
    private fun describeCandidate(sdp: String): String {
        val fields = sdp.substringAfter("candidate:", sdp).split(" ")
        if (fields.size < 8) return "type=unknown"
        return "type=${fields[7]}, ${fields[2]} ${fields[4]}:${fields[5]}"
    }

    /**
     * Logs the ICE candidate pair that ended up carrying the media.
     *
     * On the viewer side the remote half is usually a *peer-reflexive* candidate:
     * the camera's NAT-mapped address, learned from the incoming STUN request.
     * That address is precisely what a firewall has to allow, so logging it here
     * makes cross-subnet failures diagnosable from the log alone.
     */
    private fun logSelectedCandidatePair(pc: PeerConnection, peerId: String, attempt: Int = 0) {
        try {
            pc.getStats { report ->
                val stats = report.statsMap
                var chosen: RTCStats? = null
                for (s in stats.values) {
                    if (s.type != "candidate-pair") continue
                    val m = s.members
                    if (m["selected"] == true) { chosen = s; break }
                    if (chosen == null && m["state"] == "succeeded" && m["nominated"] == true) chosen = s
                }
                val members = chosen?.members
                if (members == null) {
                    // Right after CONNECTED the stats report often does not carry a
                    // candidate-pair yet. Retry once instead of leaving the single
                    // most useful diagnostic blank.
                    if (attempt == 0) {
                        AppLogger.d(tag, "Selected ICE pair for $peerId: not in stats yet, retrying in 1500ms")
                        mainHandler.postDelayed({
                            if (peerConnections[peerId] === pc) {
                                logSelectedCandidatePair(pc, peerId, attempt + 1)
                            }
                        }, 1_500L)
                    } else {
                        AppLogger.w(tag, "Selected ICE pair for $peerId: still absent from stats after retry")
                    }
                    return@getStats
                }

                fun describe(idKey: String): String {
                    val id = members[idKey] as? String ?: return "?"
                    val c = stats[id] ?: return "?"
                    val type = c.members["candidateType"] ?: "?"
                    val proto = c.members["protocol"] ?: "?"
                    // Newer libwebrtc renamed the field from `ip` to `address`;
                    // without the fallback the address came out as a bare "?" and
                    // the log could not tell which NAT mapping to open.
                    val addr = c.members["address"] ?: c.members["ip"] ?: "?"
                    val port = c.members["port"] ?: "?"
                    return "$type $proto $addr:$port"
                }

                val remoteCandidates = addedRemoteIceCandidates[peerId]?.size ?: 0
                AppLogger.i(
                    tag,
                    "Selected ICE pair for $peerId: local[${describe("localCandidateId")}] " +
                        "<-> remote[${describe("remoteCandidateId")}] " +
                        "(remoteCandidates=$remoteCandidates)"
                )
            }
        } catch (e: Throwable) {
            AppLogger.w(tag, "Failed to read selected ICE pair for $peerId: ${e.message}")
        }
    }

    fun createPeerConnection(peerId: String, onIceCandidateGenerated: (IceCandidate) -> Unit): PeerConnection? {
        val factory = peerConnectionFactory ?: run {
            AppLogger.e(tag, "Cannot create PeerConnection: PeerConnectionFactory is null")
            return null
        }
        closePeer(peerId, keepPendingCandidates = true)
        AppLogger.i(tag, "Creating new PeerConnection for $peerId")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Log the actual socket address, not just the candidate type.
                //
                // Media ports are chosen by libwebrtc and cannot be pinned from
                // the Java layer, so a camera behind a NAT firewall has to have
                // its *current* port allowed. Without the port in the log,
                // diagnosing "video worked yesterday" means packet capture on a
                // laptop. This line makes it a log grep.
                AppLogger.i(
                    tag,
                    "Local ICE Candidate for $peerId: ${describeCandidate(candidate.sdp)} " +
                        "(mid=${candidate.sdpMid}, index=${candidate.sdpMLineIndex})"
                )
                onIceCandidateGenerated(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                AppLogger.i(tag, "IceCandidates removed for $peerId: count=${candidates?.size ?: 0}")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLogger.i(tag, "SignalingState for $peerId: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLogger.i(tag, "IceConnectionState for $peerId: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                AppLogger.i(tag, "ConnectionChange for $peerId: $newState")
                peerConnectionStates[peerId] = newState
                _connectionState.value = newState
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    // Record which address pair actually carries the media.
                    // On the viewer side the remote half is a peer-reflexive
                    // candidate, i.e. the camera's NAT-mapped address — the exact
                    // ip:port that has to be allowed through the firewall. Printing
                    // it turns "video is black again, go capture packets" into a
                    // single log grep.
                    peerConnections[peerId]?.let { logSelectedCandidatePair(it, peerId) }
                }
                if (newState == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) {
                    if (activePttPeers.remove(peerId)) {
                        _isPttReceiving.value = activePttPeers.isNotEmpty()
                        try {
                            remoteAudioTracks[peerId]?.setEnabled(false)
                        } catch (_: Throwable) {}
                    }
                }
                updateActiveViewersCount()
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                AppLogger.i(tag, "IceGatheringState for $peerId: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    val mid = transceiver?.mid ?: "unknown-$peerId"
                    AppLogger.i(tag, "Remote VideoTrack received: id=${track.id()}, mid=$mid, state=${track.state()}, enabled=${track.enabled()}")
                    track.setEnabled(true)

                    // Multi-lens: keep every incoming video track, keyed by mid.
                    // The legacy single-lens path still mirrors into
                    // remoteVideoTrack so the existing renderer code keeps working.
                    remoteVideoTracks[mid] = track

                    val knownLens = remoteLensByMid[mid]
                    // The lens list normally arrives before any track. Honour the
                    // deferred selection as soon as ITS track shows up, instead of
                    // leaving the renderer stuck on whichever track arrived first.
                    val pending = pendingPreferredLensId
                    val switched = if (pending != null) {
                        val ok = selectRemoteLens(pending)
                        if (ok) pendingPreferredLensId = null
                        ok
                    } else {
                        false
                    }

                    if (!switched) {
                        val isFirstVideo = remoteVideoTrack == null
                        if (isFirstVideo) {
                            remoteVideoTrack = track
                            track.addSink(remoteVideoSink)
                            AppLogger.i(tag, "Attached first remote VideoTrack (mid=$mid) to remoteVideoSink")
                        } else {
                            // Additional lens: do NOT steal the sink. The active lens
                            // keeps rendering; the viewer switches explicitly.
                            AppLogger.i(tag, "Additional remote VideoTrack stored (mid=$mid, lens=${knownLens?.label ?: "?"}), sink not stolen")
                        }
                        // Do not claim the active lens while a deferred selection is
                        // still pending — that would flip the UI highlight to the
                        // first track that happened to arrive.
                        if (knownLens != null && _activeLensId.value == null && pendingPreferredLensId == null) {
                            _activeLensId.value = knownLens.id
                        }
                    }
                } else if (track is AudioTrack) {
                    AppLogger.i(tag, "Remote AudioTrack received from $peerId: id=${track.id()}, state=${track.state()}")
                    remoteAudioTracks[peerId] = track
                    remoteAudioTrack = track
                    val isCamera = localVideoTrack != null
                    if (isCamera) {
                        val isPeerPttActive = activePttPeers.contains(peerId)
                        track.setEnabled(isPeerPttActive)
                        if (isPeerPttActive) {
                            com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
                        }
                        AppLogger.i(tag, "Camera (PA) received remote AudioTrack from viewer $peerId (initially enabled=$isPeerPttActive)")
                    } else {
                        track.setEnabled(isRemoteAudioEnabled)
                        AppLogger.i(tag, "Viewer (PB) remote AudioTrack enabled: $isRemoteAudioEnabled")
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel) {
                AppLogger.i(tag, "DataChannel received from $peerId: label=${dataChannel.label()}, id=${dataChannel.id()}")
                setupDataChannel(peerId, dataChannel)
            }

            override fun onRenegotiationNeeded() {
                AppLogger.d(tag, "Renegotiation needed for $peerId")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                AppLogger.i(tag, "IceConnection receiving for $peerId: receiving=$receiving")
            }
        })

        if (pc != null) {
            // Sender (PA): exactly one video track. The negotiated m-line survives
            // every lens switch, which is why a switch never renegotiates.
            localVideoTrack?.let { vTrack ->
                val sender = pc.addTrack(vTrack, listOf("CAM_STREAM"))
                configureVideoSender(sender)
            }
            localAudioTrack?.let { pc.addTrack(it, listOf("CAM_STREAM")) }

            peerConnections[peerId] = pc
            peerConnectionStates[peerId] = PeerConnection.PeerConnectionState.NEW
            updateActiveViewersCount()
        }

        return pc
    }

    private fun setupDataChannel(peerId: String, dc: DataChannel) {
        dataChannels[peerId] = dc
        // onStateChange() and the trailing call at the end of this function can
        // both fire while the channel is OPEN. Without a guard every initial
        // report (including LENS_LIST_REPORT) is delivered twice, which makes
        // the viewer re-apply the lens list and re-bind its sink mid-stream.
        val initialStatusSent = java.util.concurrent.atomic.AtomicBoolean(false)
        fun sendInitialStatusIfOpen() {
            if (dc.state() == DataChannel.State.OPEN && initialStatusSent.compareAndSet(false, true)) {
                val isCamera = localVideoTrack != null
                if (isCamera) {
                    try {
                        val currentName = com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.getDeviceName()
                        val renameCmd = ControlCommand.Rename(currentName)
                        val renameBytes = renameCmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(renameBytes), false))
                        AppLogger.i(tag, "Sent initial Rename command on DataChannel open to $peerId: $currentName")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial Rename command", e)
                    }

                    try {
                        val bInfo = com.a0soft.gphone.camonitor.ui.common.BatteryHelper.getBatteryInfo(context)
                        if (bInfo.percentage >= 0) {
                            val cmd = ControlCommand.Battery(bInfo.percentage, bInfo.isCharging)
                            val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
                            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        }
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial Battery command", e)
                    }

                    try {
                        val fps = _streamFps.value
                        val cmd = ControlCommand.FpsReport(fps)
                        val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        AppLogger.i(tag, "Sent initial FpsReport on DataChannel open to $peerId: $fps fps")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial FpsReport command", e)
                    }

                    try {
                        val modeCmd = ControlCommand.NightModeReport(
                            mode = _nightModeSetting.value.name,
                            isNightActive = _isNightModeActive.value
                        )
                        val bytes = modeCmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        AppLogger.i(tag, "Sent initial NightModeReport on DataChannel open to $peerId: mode=${modeCmd.mode}, active=${modeCmd.isNightActive}")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial NightModeReport command", e)
                    }

                    try {
                        val torchEnabled = com.a0soft.gphone.camonitor.CamMonitorApp.instance.torchManager.isTorchOn.value
                        val torchCmd = ControlCommand.TorchReport(torchEnabled)
                        val bytes = torchCmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        AppLogger.i(tag, "Sent initial TorchReport on DataChannel open to $peerId: enabled=$torchEnabled")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial TorchReport command", e)
                    }

                    // Publish the loaded lens list so the viewer knows which
                    // lenses it can switch to right away.
                    try {
                        val lenses = lensController?.lensList().orEmpty()
                        if (lenses.isNotEmpty()) {
                            val activeId = lensController?.activeLensId
                                ?: lenses.firstOrNull { it.isDefault }?.id
                                ?: lenses.first().id
                            val cmd = ControlCommand.LensListReport(lenses, activeId)
                            val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
                            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                            AppLogger.i(tag, "Sent initial LensListReport on DataChannel open to $peerId: ${lenses.size} lens(es)")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial LensListReport command", e)
                    }
                }
            }
        }

        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                AppLogger.i(tag, "DataChannel state for $peerId: ${dc.state()}")
                sendInitialStatusIfOpen()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val jsonStr = String(data, Charsets.UTF_8)
                val cmd = ControlCommand.fromJson(jsonStr)
                if (cmd != null) {
                    AppLogger.i(tag, "Received DataChannel command from $peerId: $jsonStr")
                    if (cmd is ControlCommand.FpsReport) {
                        _remoteStreamFps.value = cmd.fps
                    }
                    _dataChannelCommands.tryEmit(Pair(peerId, cmd))
                }
            }
        })
        sendInitialStatusIfOpen()
    }

    fun createDataChannelForSender(peerId: String): DataChannel? {
        val pc = peerConnections[peerId] ?: return null
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val dc = pc.createDataChannel("control", init)
        if (dc != null) {
            setupDataChannel(peerId, dc)
        }
        return dc
    }

    fun sendDataChannelCommand(peerId: String, cmd: ControlCommand): Boolean {
        val dc = dataChannels[peerId] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        return dc.send(buffer)
    }

    fun broadcastDataChannelCommand(cmd: ControlCommand) {
        dataChannels.keys.forEach { peerId ->
            sendDataChannelCommand(peerId, cmd)
        }
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localVideoTrack?.let { track ->
            track.removeSink(renderer)
            track.addSink(renderer)
        }
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (localRenderer == renderer) {
            localRenderer = null
        }
        localVideoTrack?.removeSink(renderer)
    }

    fun setRemoteVideoRotation(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        remoteVideoRotationDegrees = normalized
        AppLogger.i(tag, "setRemoteVideoRotation: degrees=$degrees, normalized=$normalized")
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.let { track ->
            track.removeSink(remoteVideoSink)
            track.addSink(remoteVideoSink)
            AppLogger.i(tag, "attachRemoteRenderer: Connected remoteVideoTrack to remoteVideoSink")
        }
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (remoteRenderer == renderer) {
            remoteRenderer = null
        }
        remoteVideoTrack?.removeSink(remoteVideoSink)
    }

    /**
     * Applies the lens list published by the camera.
     *
     * Single-path senders stream ONE track that never changes, so the report is
     * purely UI state: which lenses exist and which one is live right now. The
     * report is authoritative — the viewer shows exactly what the camera says,
     * including after a failed switch.
     *
     * Multi-track senders (an older build) additionally publish a mid per lens;
     * when such mids are present the matching remote track is attached, which is
     * why the sink-swap path below is kept.
     */
    fun applyRemoteLensList(lenses: List<CameraLensInfo>, activeLensId: String?) {
        remoteLensByMid.clear()
        lenses.forEach { lens ->
            lens.mid?.let { mid -> remoteLensByMid[mid] = lens }
        }
        _remoteLenses.value = lenses
        AppLogger.i(tag, "Remote lens list applied: ${lenses.size} lens(es), active=$activeLensId -> " +
            lenses.joinToString { "${it.label}(${it.id},avail=${it.available})" })

        val resolvedActive = activeLensId?.takeIf { id -> lenses.any { it.id == id && it.available } }
            ?: lenses.firstOrNull { it.isDefault && it.available }?.id
            ?: lenses.firstOrNull { it.available }?.id
            ?: return

        // The camera's active lens always wins: it is the only side that knows
        // which sensor is actually feeding the track.
        _activeLensId.value = resolvedActive
        pendingPreferredLensId = null

        // Multi-track compatibility: if this lens has a mid AND we hold that
        // track, render it. Single-path returns false here and the already
        // attached track keeps running, so this is a no-op.
        selectRemoteLens(resolvedActive)
    }

    /**
     * Attaches the remote track that belongs to [lensId], if the peer still
     * publishes one track per lens.
     *
     * Single-path peers stream one track that never changes, so this returns
     * false there and the already attached track keeps rendering — the caller
     * must NOT treat false as a switch failure.
     */
    fun selectRemoteLens(lensId: String): Boolean {
        val lens = _remoteLenses.value.firstOrNull { it.id == lensId }
        if (lens == null) {
            AppLogger.w(tag, "selectRemoteLens: unknown lens $lensId")
            return false
        }
        if (!lens.available) {
            AppLogger.w(tag, "selectRemoteLens: lens $lensId is unavailable on the camera")
            return false
        }
        val mid = lens.mid
        val track = mid?.let { remoteVideoTracks[it] }
        if (track == null) {
            // Expected on a single-path camera: no mid, one shared track.
            AppLogger.d(tag, "selectRemoteLens: no dedicated track for lens $lensId (mid=$mid)")
            return false
        }

        remoteVideoTrack?.let { oldTrack ->
            if (oldTrack !== track) {
                oldTrack.removeSink(remoteVideoSink)
                AppLogger.i(tag, "selectRemoteLens: detached previous track from sink")
            }
        }
        remoteVideoTrack = track
        track.removeSink(remoteVideoSink)
        track.addSink(remoteVideoSink)
        _activeLensId.value = lensId
        AppLogger.i(tag, "Switched remote lens to ${lens.label} (cameraId=$lensId, mid=$mid)")
        return true
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        isRemoteAudioEnabled = enabled
        remoteAudioTrack?.setEnabled(enabled)
        AppLogger.i(tag, "Remote audio track enabled: $enabled")
    }

    val isViewerAudioTrackActive: Boolean get() = viewerAudioTrack != null

    fun startViewerAudioCapture(): AudioTrack? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(tag, "Cannot start viewer audio capture: RECORD_AUDIO permission not granted")
            return null
        }
        val factory = peerConnectionFactory ?: run {
            AppLogger.e(tag, "Cannot start viewer audio capture: PeerConnectionFactory is null")
            return null
        }
        if (viewerAudioTrack != null) return viewerAudioTrack

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        viewerAudioSource = aSource
        val track = factory.createAudioTrack("VIEWER_PTT_AUDIO_TRACK", aSource).apply {
            setEnabled(false) // Initially muted until PTT is pressed
        }
        viewerAudioTrack = track
        AppLogger.i(tag, "Viewer PTT audio capture started (initially muted)")
        return track
    }

    fun stopViewerAudioCapture() {
        try {
            viewerAudioTrack?.setEnabled(false)
            viewerAudioTrack?.dispose()
            viewerAudioTrack = null
            viewerAudioSource?.dispose()
            viewerAudioSource = null
            isPttActive = false
            AppLogger.i(tag, "Viewer PTT audio capture stopped")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error stopping viewer audio capture", e)
        }
    }

    fun setPttActive(active: Boolean) {
        isPttActive = active
        viewerAudioTrack?.setEnabled(active)
        AppLogger.i(tag, "Viewer PTT active set to: $active (track enabled=${viewerAudioTrack?.enabled()})")
    }

    fun onPttCommand(peerId: String, isStart: Boolean) {
        if (isStart) {
            activePttPeers.add(peerId)
        } else {
            activePttPeers.remove(peerId)
        }
        _isPttReceiving.value = activePttPeers.isNotEmpty()
        val track = remoteAudioTracks[peerId]
        try {
            track?.setEnabled(isStart)
        } catch (e: Throwable) {
            AppLogger.w(tag, "Failed setting enabled=$isStart on remoteAudioTrack for $peerId: ${e.message}")
        }
        if (isStart) {
            com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
        }
        AppLogger.i(tag, "onPttCommand: peerId=$peerId, isStart=$isStart, activePttCount=${activePttPeers.size}, trackId=${track?.id()}")
    }

    private val remoteDescriptionSetPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<IceCandidate>>()
    private val addedRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    internal fun getAddedRemoteIceCandidates(peerId: String): Set<String>? = addedRemoteIceCandidates[peerId]?.toSet()
    internal fun getPendingRemoteIceCandidates(peerId: String): List<IceCandidate>? = pendingRemoteIceCandidates[peerId]?.toList()

    fun addRemoteIceCandidate(peerId: String, candidate: IceCandidate) {
        val seen = addedRemoteIceCandidates.getOrPut(peerId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        val candidateKey = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
        if (!seen.add(candidateKey)) {
            // Duplicate candidate ignored
            return
        }

        val pc = peerConnections[peerId]
        val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
        val isRemoteDescSet = remoteDescriptionSetPeers.contains(peerId)

        if (pc != null && isRemoteDescSet) {
            try {
                val added = pc.addIceCandidate(candidate)
                // Per-candidate detail is DEBUG: a single negotiation produces
                // 30-50 of these and the camera re-sends each one. The INFO-level
                // summary lives in logSelectedCandidatePair().
                AppLogger.d(tag, "Added remote ICE candidate directly for $peerId: type=$typeMatch, mid=${candidate.sdpMid}, added=$added")
            } catch (e: Throwable) {
                AppLogger.w(tag, "Failed adding candidate directly to pc for $peerId: ${e.message}")
            }
        } else {
            val list = pendingRemoteIceCandidates.getOrPut(peerId) { java.util.concurrent.CopyOnWriteArrayList() }
            list.add(candidate)
            AppLogger.d(tag, "Buffered remote ICE candidate for $peerId: type=$typeMatch, mid=${candidate.sdpMid} (pending count=${list.size}, pc=${if (pc != null) "exists(waiting remoteDesc)" else "null"})")
            if (remoteDescriptionSetPeers.contains(peerId)) {
                drainRemoteIceCandidates(peerId)
            }
        }
    }

    fun drainRemoteIceCandidates(peerId: String) {
        remoteDescriptionSetPeers.add(peerId)
        val pc = peerConnections[peerId] ?: run {
            AppLogger.w(tag, "Cannot drain ICE candidates: PeerConnection not found for $peerId")
            return
        }
        val pending = pendingRemoteIceCandidates.remove(peerId)
        if (pending.isNullOrEmpty()) {
            AppLogger.i(tag, "No buffered ICE candidates to drain for $peerId")
            return
        }
        AppLogger.i(tag, "Draining ${pending.size} buffered ICE candidates for $peerId into PeerConnection")
        pending.forEach { cand ->
            try {
                val added = pc.addIceCandidate(cand)
                AppLogger.d(tag, "Drained candidate mid=${cand.sdpMid} (added=$added)")
            } catch (e: Throwable) {
                AppLogger.w(tag, "Failed adding drained candidate for $peerId: ${e.message}")
            }
        }
    }

    private fun updateActiveViewersCount() {
        val count = peerConnectionStates.values.count {
            it == PeerConnection.PeerConnectionState.CONNECTED
        }
        _activeViewersCount.value = count
    }

    fun hasPeer(peerId: String): Boolean = peerConnections.containsKey(peerId)

    fun getPeerConnectionState(peerId: String): PeerConnection.PeerConnectionState? = peerConnectionStates[peerId]

    fun closePeer(peerId: String, keepPendingCandidates: Boolean = false) {
        peerConnectionStates.remove(peerId)
        remoteDescriptionSetPeers.remove(peerId)
        if (!keepPendingCandidates) {
            pendingRemoteIceCandidates.remove(peerId)
            addedRemoteIceCandidates.remove(peerId)
        } else {
            val pending = pendingRemoteIceCandidates[peerId]
            if (pending.isNullOrEmpty()) {
                addedRemoteIceCandidates.remove(peerId)
            } else {
                val freshSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                freshSet.addAll(pending.map { "${it.sdpMid}:${it.sdpMLineIndex}:${it.sdp}" })
                addedRemoteIceCandidates[peerId] = freshSet
            }
        }
        if (activePttPeers.remove(peerId)) {
            _isPttReceiving.value = activePttPeers.isNotEmpty()
        }
        dataChannels.remove(peerId)?.dispose()
        peerConnections.remove(peerId)?.dispose()
        if (localVideoTrack == null || peerConnections.isEmpty()) {
            remoteVideoTrack?.removeSink(remoteVideoSink)
            remoteVideoTrack = null
            // Drop every collected remote lens track as well, otherwise a
            // reconnect would accumulate stale tracks and leak renderers.
            remoteVideoTracks.values.forEach { track ->
                runCatching { track.removeSink(remoteVideoSink) }
            }
            remoteVideoTracks.clear()
            remoteLensByMid.clear()
            _remoteLenses.value = emptyList()
            _activeLensId.value = null
            pendingPreferredLensId = null
        }
        remoteAudioTracks.remove(peerId)
        remoteAudioTrack = remoteAudioTracks.values.firstOrNull()
        updateActiveViewersCount()
        AppLogger.i(tag, "Closed WebRTC connection with $peerId")
    }

    fun closeAll() {
        peerConnectionStates.clear()
        remoteDescriptionSetPeers.clear()
        activePttPeers.clear()
        _isPttReceiving.value = false
        stopCameraCapture()
        stopViewerAudioCapture()
        pendingRemoteIceCandidates.clear()
        addedRemoteIceCandidates.clear()
        dataChannels.values.forEach { it.dispose() }
        dataChannels.clear()
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        _activeViewersCount.value = 0
        localRenderer?.let { r -> localVideoTrack?.removeSink(r) }
        remoteVideoTrack?.removeSink(remoteVideoSink)
        remoteVideoTrack = null
        remoteVideoTracks.clear()
        remoteLensByMid.clear()
        _remoteLenses.value = emptyList()
        _activeLensId.value = null
        pendingPreferredLensId = null
        remoteRenderer = null
        remoteVideoRotationDegrees = 0
        remoteAudioTrack = null
        remoteAudioTracks.clear()
        isRemoteAudioEnabled = false
        AppLogger.i(tag, "All WebRTC connections closed")
    }
}
