package com.a0soft.gphone.camonitor.ui.viewer

import android.view.ViewGroup
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.CameraLensInfo
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.NightModeSetting
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.model.ViewerConnectionProgress
import com.a0soft.gphone.camonitor.data.model.ViewerConnectionStep
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.webrtc.MultiLensConstants
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.theme.StatusLive
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*

sealed interface ViewerConnectionState {
    object Connecting : ViewerConnectionState
    object NoCamera : ViewerConnectionState
    object InitFailed : ViewerConnectionState
    object WaitingResponse : ViewerConnectionState
    object StartingStream : ViewerConnectionState
    data class CameraTimeout(val timeoutSec: Int = ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC) : ViewerConnectionState
    data class Rejected(val reason: String) : ViewerConnectionState
    object Live : ViewerConnectionState
    object Failed : ViewerConnectionState
    object Disconnected : ViewerConnectionState
}

@Composable
fun ViewerModeScreen(
    onExitViewerMode: () -> Unit,
    selectedDeviceId: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient
    val webRtcManager = app.webRtcManager

    val pairedCameras = remember { securityRepo.getPairedCameras() }
    val targetCamera = remember(selectedDeviceId) {
        if (selectedDeviceId != null) {
            pairedCameras.find { it.deviceId == selectedDeviceId } ?: pairedCameras.firstOrNull()
        } else {
            pairedCameras.firstOrNull()
        }
    }

    var cameraName by remember { mutableStateOf(targetCamera?.name ?: "") }
    var connectionState by remember { mutableStateOf<ViewerConnectionState>(ViewerConnectionState.Connecting) }
    var connectionProgress by remember {
        mutableStateOf(
            ViewerConnectionProgress(
                typicalConnectMs = targetCamera?.let { securityRepo.getTypicalConnectDurationMs(it.deviceId) }
            )
        )
    }
    var connectStartTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var stepStartTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var isAspectFit by remember { mutableStateOf(true) }
    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }
    var isControlsVisible by remember { mutableStateOf(true) }
    val remoteFps by webRtcManager.remoteStreamFps.collectAsState()
    var streamFps by remember { mutableStateOf(20) }

    LaunchedEffect(remoteFps) {
        streamFps = remoteFps
    }

    val displayName = cameraName.ifEmpty { stringResource(R.string.camera_default_name) }
    val connectionStatus = when (val s = connectionState) {
        ViewerConnectionState.Connecting -> stringResource(R.string.viewer_status_connecting)
        ViewerConnectionState.NoCamera -> stringResource(R.string.viewer_status_no_camera)
        ViewerConnectionState.InitFailed -> stringResource(R.string.viewer_status_init_failed)
        ViewerConnectionState.WaitingResponse -> stringResource(R.string.viewer_status_waiting_response)
        ViewerConnectionState.StartingStream -> stringResource(R.string.viewer_status_starting_stream)
        is ViewerConnectionState.CameraTimeout -> stringResource(R.string.viewer_status_camera_timeout, s.timeoutSec)
        is ViewerConnectionState.Rejected -> stringResource(R.string.viewer_status_camera_rejected, s.reason)
        ViewerConnectionState.Live -> stringResource(R.string.viewer_status_live, streamFps)
        ViewerConnectionState.Failed -> stringResource(R.string.viewer_status_connection_failed)
        ViewerConnectionState.Disconnected -> stringResource(R.string.viewer_status_disconnected)
    }
    val isLive = connectionState is ViewerConnectionState.Live
    val isConnecting = connectionState is ViewerConnectionState.Connecting ||
        connectionState is ViewerConnectionState.StartingStream ||
        connectionState is ViewerConnectionState.WaitingResponse

    fun completeConnectionToLive() {
        if (connectionState is ViewerConnectionState.Live) return
        val now = System.currentTimeMillis()
        val step4Dur = (now - stepStartTimeMs).coerceAtLeast(50L)
        val totalDur = (now - connectStartTimeMs).coerceAtLeast(100L)
        connectionProgress = connectionProgress.copy(
            step4DurationMs = connectionProgress.step4DurationMs ?: step4Dur
        )
        targetCamera?.let { cam ->
            securityRepo.recordConnectDurationMs(cam.deviceId, totalDur)
        }
        connectionState = ViewerConnectionState.Live
        if (!hasRenderedFirstFrame) {
            AppLogger.w("ViewerMode", "Marked LIVE via fallback WITHOUT a rendered first frame — video is likely black")
        }
        AppLogger.i("ViewerMode", "Stream transitioned to LIVE (totalConnectDuration=${totalDur}ms)")
    }
    var isAudioEnabled by remember { mutableStateOf(securityRepo.isViewerAudioPlaybackEnabled()) }
    var isTorchActive by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var hasSymmetricNatError by remember { mutableStateOf(false) }
    var nightModeSetting by remember { mutableStateOf(securityRepo.getNightModeSetting()) }
    var isCameraNightActive by remember { mutableStateOf(false) }

    // Multi-lens state: the lens list published by the camera and the lens the
    // viewer currently renders.
    var availableLenses by remember { mutableStateOf<List<CameraLensInfo>>(emptyList()) }
    val activeLensId by webRtcManager.activeLensId.collectAsState()

    val context = LocalContext.current

    // Pending lens switch. The camera answers every request, but the DataChannel
    // can drop or the sensor can hang, so a local timeout is the only thing that
    // guarantees the UI never gets stuck greyed out.
    var pendingLensId by remember { mutableStateOf<String?>(null) }
    var lensSwitchTimeoutJob by remember { mutableStateOf<Job?>(null) }
    val lensScope = rememberCoroutineScope()

    fun settleLensSwitch(lensId: String?, reason: String) {
        lensSwitchTimeoutJob?.cancel()
        lensSwitchTimeoutJob = null
        if (pendingLensId != null) {
            AppLogger.i("ViewerMode", "Lens switch settled (${pendingLensId}) by $reason")
        }
        pendingLensId = null
        if (lensId != null) {
            AppLogger.i("ViewerMode", "Lens switch result: $lensId / $reason")
        }
    }

    /**
     * Asks the camera to switch sensors and keeps the button pending until it
     * answers (LENS_SWITCH_RESULT / LENS_LIST_REPORT) or 4s elapse.
     */
    fun switchLens(lensId: String) {
        if (pendingLensId != null) {
            AppLogger.i("ViewerMode", "Lens switch ignored: ${pendingLensId} is still pending")
            return
        }
        val cam = targetCamera ?: return

        pendingLensId = lensId
        // Harmless on a single-track camera (returns false: no such mid track),
        // but required when the peer still publishes one track per lens.
        webRtcManager.selectRemoteLens(lensId)

        val sent = webRtcManager.sendDataChannelCommand(cam.deviceId, ControlCommand.SwitchLens(lensId))
        if (!sent) {
            settleLensSwitch(lensId, "datachannel not open")
            android.widget.Toast.makeText(context, "控制通道未就绪，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.i("ViewerMode", "Requested lens switch to $lensId")

        lensSwitchTimeoutJob?.cancel()
        lensSwitchTimeoutJob = lensScope.launch {
            // Longer than the camera's own first-frame budget (4s): a timeout
            // here must mean "really no answer", not "slow sensor".
            delay(6_000L)
            if (pendingLensId == lensId) {
                AppLogger.w("ViewerMode", "Lens switch to $lensId timed out after 6s (no answer from camera)")
                pendingLensId = null
                android.widget.Toast.makeText(context, "镜头切换超时", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE) }
    var showMicPermissionDialog by remember { mutableStateOf(false) }
    var isMicPermanentlyDenied by remember { mutableStateOf(false) }
    var reconnectTrigger by remember { mutableStateOf(0) }

    fun checkIsMicPermanentlyDenied(): Boolean {
        val hasRequested = prefs.getBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", false)
        return hasRequested && (activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } ?: false)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showMicPermissionDialog = false
            reconnectTrigger++
        } else {
            isMicPermanentlyDenied = checkIsMicPermanentlyDenied()
            showMicPermissionDialog = true
        }
    }

    // Entering the viewer starts a connection immediately, but the local audio
    // track only exists a few hundred milliseconds later. ON_RESUME lands inside
    // that window, saw `!isViewerAudioTrackActive`, and bumped reconnectTrigger —
    // tearing down the PeerConnection that had just been created and building a
    // second one. That is where the duplicate Offer/Answer pair came from. The
    // timestamp below makes the resume path stand down while a connect is still
    // in its own startup phase.
    val lastConnectStartedAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Monotonic id of the connection attempt, echoed by the camera on the Answer.
    // An Answer from a connection we already gave up on is structurally
    // indistinguishable from the current one, so without this a late Answer can
    // be applied to a freshly created PeerConnection.

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    showMicPermissionDialog = false
                    val sinceStart = SystemClock.elapsedRealtime() - lastConnectStartedAt.get()
                    if (!webRtcManager.isViewerAudioTrackActive && sinceStart > 2_000L) {
                        reconnectTrigger++
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var paBatteryInfo by remember { mutableStateOf<BatteryInfo?>(null) }
    var pbBatteryInfo by remember { mutableStateOf(BatteryHelper.getBatteryInfo(context)) }

    var surfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var isUserExited by remember { mutableStateOf(false) }

    fun exitViewer() {
        isUserExited = true
        webRtcManager.stopViewerAudioCapture()
        AudioRoutingManager.restoreNormalMode(context)
        webRtcManager.closeAll()
        coroutineScope.launch {
            try {
                signalingClient.stopListening()
            } catch (_: Exception) {}
        }
        onExitViewerMode()
    }

    BackHandler {
        exitViewer()
    }

    // Sync audio playback enabled state to WebRTC manager
    LaunchedEffect(isAudioEnabled) {
        webRtcManager.setRemoteAudioEnabled(isAudioEnabled)
        if (isAudioEnabled) {
            AudioRoutingManager.routeToSpeaker(context, adjustVolume = true)
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible, availableLenses.size) {
        // With multiple lenses the switcher has to stay reachable. Hiding the
        // whole overlay after 5s made it look as if lens switching did not exist.
        if (isControlsVisible && availableLenses.size <= 1) {
            delay(5000L)
            isControlsVisible = false
        }
    }

    // Ensure remoteRenderer is always attached when surfaceRenderer is available
    LaunchedEffect(surfaceRenderer) {
        surfaceRenderer?.let {
            webRtcManager.attachRemoteRenderer(it)
        }
    }

    // Connect to camera on launch and on reconnectTrigger
    // Keyed by deviceId, not by the object: the repository hands out a fresh
    // PairedDevice instance whenever anything about it changes (name, address,
    // last-seen), and a changing object identity restarted this whole effect —
    // another PeerConnection, another Offer. The device is the same device.
    LaunchedEffect(targetCamera?.deviceId, reconnectTrigger) {
        val effectScope = this
        if (targetCamera == null) {
            connectionState = ViewerConnectionState.NoCamera
            AppLogger.w("ViewerMode", "No paired camera found in repository")
            return@LaunchedEffect
        }
        if (isUserExited) return@LaunchedEffect

        lastConnectStartedAt.set(SystemClock.elapsedRealtime())
        webRtcManager.closePeer(targetCamera.deviceId)
        surfaceRenderer?.let { webRtcManager.attachRemoteRenderer(it) }
        val startTime = System.currentTimeMillis()
        connectStartTimeMs = startTime
        stepStartTimeMs = startTime
        hasRenderedFirstFrame = false
        connectionState = ViewerConnectionState.Connecting
        connectionProgress = ViewerConnectionProgress(
            currentStep = ViewerConnectionStep.CONNECTING_NETWORK,
            typicalConnectMs = securityRepo.getTypicalConnectDurationMs(targetCamera.deviceId)
        )

        AppLogger.i("ViewerMode", "Starting WebRTC connection to camera: ${targetCamera.name} (${targetCamera.deviceId}) (trigger=$reconnectTrigger)")
        val isMqttListening = signalingClient.startListening()
        val afterMqttTime = System.currentTimeMillis()
        val step1Dur = (afterMqttTime - stepStartTimeMs).coerceAtLeast(50L)
        stepStartTimeMs = afterMqttTime
        connectionProgress = connectionProgress.copy(
            currentStep = ViewerConnectionStep.REACHING_CAMERA,
            step1DurationMs = step1Dur,
            attempt = 1,
            remainingTimeoutSec = ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC
        )
        AppLogger.i("ViewerMode", "Signaling client startListening completed (connected=$isMqttListening, step1=${step1Dur}ms)")

        val remotePubKey = CryptoUtils.publicKeyFromBase64(targetCamera.publicKeyBase64)
        val sharedKey = CryptoUtils.deriveEcdhKey(securityRepo.getKeyPair().private, remotePubKey)

        val localIceCandidates = java.util.concurrent.CopyOnWriteArrayList<IceCandidate>()

        /**
         * candidate key -> true once the camera acknowledged the send.
         *
         * A candidate used to be transmitted two or three times: once from the
         * gatherer callback, once on every Offer retry, and once more - all of
         * them, unconditionally - when the Answer arrived. That was 48 candidates
         * re-sent three times per connection, most of a reconnect's signalling
         * traffic. The callback already delivers them; the only ones that need
         * re-sending are those whose first attempt failed while the TCP socket was
         * still being established.
         */
        val candidateDelivered = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        fun candidateKeyOf(c: IceCandidate) =
            "${c.sdpMid}:${c.sdpMLineIndex}:${c.sdp}"

        val pc = webRtcManager.createPeerConnection(targetCamera.deviceId) { candidate ->
            val key = candidateKeyOf(candidate)
            if (!candidateDelivered.containsKey(key)) {
                val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
                AppLogger.d("ViewerMode", "Forwarding local ICE candidate to camera: mid=${candidate.sdpMid}, type=$typeMatch")
                // false = dispatched but not yet confirmed; a failure leaves it
                // false so the post-Answer pass retries exactly this one.
                candidateDelivered[key] = false
                localIceCandidates.add(candidate)
                val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                effectScope.launch {
                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                    if (sent) candidateDelivered[key] = true
                    AppLogger.d("ViewerMode", "Sent ICE candidate to camera (success=$sent, mid=${candidate.sdpMid})")
                }
            }
        } ?: run {
            AppLogger.e("ViewerMode", "Failed creating PeerConnection for ${targetCamera.deviceId}")
            connectionState = ViewerConnectionState.InitFailed
            return@LaunchedEffect
        }

        // Explicit Unified Plan transceivers.
        //
        // MULTI-LENS: the camera may stream several physical lenses at once, each
        // on its own video m-line. The viewer therefore declares a fixed number of
        // RECV_ONLY video transceivers; the camera fills as many as it actually
        // has. Both sides use the same mid ordering (video m-lines first, audio
        // last), so the Nth video transceiver always corresponds to the Nth lens
        // without any extra negotiation round trip.
        //
        // We only ever fill the FIRST slot (one lens at a time); the extra slots
        // exist so an older multi-track sender can still be received.
        repeat(MultiLensConstants.MAX_VIDEO_M_LINES) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        AppLogger.i("ViewerMode", "Declared ${MultiLensConstants.MAX_VIDEO_M_LINES} RECV_ONLY video transceivers for multi-lens")
        val pttAudioTrack = webRtcManager.startViewerAudioCapture()
        if (pttAudioTrack != null) {
            pc.addTransceiver(
                pttAudioTrack,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
            AppLogger.i("ViewerMode", "Added SEND_RECV audio transceiver with viewer PTT track")
        } else {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            AppLogger.w("ViewerMode", "Viewer PTT track was null, falling back to RECV_ONLY audio")
        }

        webRtcManager.createDataChannelForSender(targetCamera.deviceId)

        val localOfferDesc = java.util.concurrent.atomic.AtomicReference<SessionDescription?>(null)

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                try {
                    val elapsed = System.currentTimeMillis() - startTime
                    AppLogger.i("ViewerMode", "Local Offer created (+${elapsed}ms, SDP size=${desc.description.length} chars). Setting local description...")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            try {
                                val setElapsed = System.currentTimeMillis() - startTime
                                AppLogger.i("ViewerMode", "Local Offer set successfully (+${setElapsed}ms), transmitting to camera: ${targetCamera.deviceId}")
                                localOfferDesc.set(desc)
                                val offerMsg = SignalingMessage.Offer(desc.description)
                                effectScope.launch {
                                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                                    AppLogger.i("ViewerMode", "Transmitted initial Offer to camera (success=$sent)")
                                }
                            } catch (t: Throwable) {
                                AppLogger.e("ViewerMode", "Error in setLocalDescription.onSetSuccess", t)
                            }
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting local offer: $err") }
                        override fun onCreateFailure(err: String?) {}
                    }, desc)
                } catch (t: Throwable) {
                    AppLogger.e("ViewerMode", "Error in createOffer.onCreateSuccess", t)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { AppLogger.e("ViewerMode", "Failed creating offer: $err") }
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())

        val isRemoteDescriptionSet = java.util.concurrent.atomic.AtomicBoolean(false)

        // Bounded 15-second countdown ticker while reaching camera
        val countdownJob = effectScope.launch {
            var remaining = ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC
            while (isActive && !isRemoteDescriptionSet.get() && remaining > 0) {
                delay(1000L)
                if (!isRemoteDescriptionSet.get()) {
                    remaining--
                    connectionProgress = connectionProgress.copy(remainingTimeoutSec = remaining)
                }
            }
            if (isActive && !isRemoteDescriptionSet.get() && remaining <= 0) {
                AppLogger.w("ViewerMode", "Camera did not respond within ${ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC}s; transitioning to CameraTimeout")
                connectionState = ViewerConnectionState.CameraTimeout(ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC)
                delay(10_000L)
                if (isActive && !isUserExited && connectionState is ViewerConnectionState.CameraTimeout) {
                    AppLogger.i("ViewerMode", "Auto-retrying after CameraTimeout...")
                    reconnectTrigger++
                }
            }
        }

        // Periodic Offer retry while waiting for camera to come online / answer (bounded to maxAttempts)
        val retryJob = effectScope.launch {
            var attempt = 1
            while (isActive && !isRemoteDescriptionSet.get() && attempt < ViewerConnectionProgress.DEFAULT_MAX_ATTEMPTS) {
                delay(ViewerConnectionProgress.OFFER_RETRY_INTERVAL_MS)
                val offer = localOfferDesc.get()
                if (!isRemoteDescriptionSet.get() && offer != null && connectionState !is ViewerConnectionState.CameraTimeout) {
                    attempt++
                    connectionProgress = connectionProgress.copy(attempt = attempt)
                    val elapsed = System.currentTimeMillis() - startTime
                    // Only candidates whose send was never confirmed. Re-sending
                    // the whole set on every retry tripled the signalling traffic
                    // for no benefit — the camera already had the rest.
                    val undelivered = localIceCandidates.filter { candidateDelivered[candidateKeyOf(it)] != true }
                    AppLogger.i("ViewerMode", "Waiting for camera answer (+${elapsed}ms); re-transmitting Offer (attempt #$attempt/${ViewerConnectionProgress.DEFAULT_MAX_ATTEMPTS}) and ${undelivered.size}/${localIceCandidates.size} undelivered ICE candidate(s)...")
                    val offerMsg = SignalingMessage.Offer(offer.description)
                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                    AppLogger.i("ViewerMode", "Re-transmitted Offer (attempt #$attempt, sent=$sent)")
                    undelivered.forEach { cand ->
                        launch {
                            val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                            val candSent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                            if (candSent) candidateDelivered[candidateKeyOf(cand)] = true
                            AppLogger.d("ViewerMode", "Re-transmitted candidate to camera (success=$candSent, mid=${cand.sdpMid})")
                        }
                    }
                    if (attempt >= 2 && webRtcManager.getPeerConnectionState(targetCamera.deviceId) == PeerConnection.PeerConnectionState.NEW) {
                        connectionState = ViewerConnectionState.WaitingResponse
                    }
                }
            }
        }

        // Observe incoming messages
        signalingClient.incomingMessages.collect collectLoop@ { (senderId, msg) ->
            if (senderId == targetCamera.deviceId) {
                when (msg) {
                    is SignalingMessage.CameraWaking -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.i("ViewerMode", "Received CameraWaking ACK from camera (+${elapsed}ms)")
                        msg.cameraName?.let { newName ->
                            cameraName = newName
                        }
                        connectionProgress = connectionProgress.copy(isCameraWakingUp = true)
                    }
                    is SignalingMessage.Answer -> {
                        // Idempotence guard.
                        //
                        // Entering the viewer used to create two PeerConnections
                        // within ~100 ms, so the camera answered twice and the
                        // second setRemoteDescription failed with
                        // "Called in wrong state: stable". Harmless only by luck —
                        // had they arrived reversed, the second (identical) SDP
                        // would have been applied on top of a live session.
                        // An Answer is only valid while we still hold a local offer.
                        // A stale Answer (from an abandoned attempt) must never be
                        // applied, even though it is perfectly valid SDP.
                        val answerState = pc.signalingState()
                        if (answerState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                            AppLogger.w(
                                "ViewerMode",
                                "Ignoring duplicate/late Answer: signalingState=$answerState"
                            )
                            return@collectLoop
                        }
                        val now = System.currentTimeMillis()
                        val elapsed = now - startTime
                        val step2Dur = (now - stepStartTimeMs).coerceAtLeast(50L)
                        stepStartTimeMs = now
                        connectionProgress = connectionProgress.copy(
                            currentStep = ViewerConnectionStep.ESTABLISHING_LINK,
                            step2DurationMs = step2Dur,
                            isCameraWakingUp = false
                        )
                        AppLogger.i("ViewerMode", "Received remote Answer from camera (+${elapsed}ms, step2=${step2Dur}ms, SDP size=${msg.sdp.length} chars). Applying to PeerConnection...")
                        msg.cameraName?.let { newName ->
                            cameraName = newName
                            val existing = securityRepo.getPairedDevice(targetCamera.deviceId) ?: targetCamera
                            securityRepo.addPairedDevice(existing.copy(name = newName))
                            AppLogger.i("ViewerMode", "Updated paired camera name from Answer to: $newName")
                        }
                        msg.fps?.let { newFps ->
                            streamFps = newFps
                            webRtcManager.setRemoteFps(newFps)
                            AppLogger.i("ViewerMode", "Received stream FPS from Answer: $newFps fps")
                        }
                        retryJob.cancel()
                        countdownJob.cancel()
                        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                        pc.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                isRemoteDescriptionSet.set(true)
                                try {
                                    val setElapsed = System.currentTimeMillis() - startTime
                                    AppLogger.i("ViewerMode", "Remote Answer applied successfully (+${setElapsed}ms). Draining buffered candidates...")
                                    webRtcManager.drainRemoteIceCandidates(targetCamera.deviceId)
                                    if (connectionState !is ViewerConnectionState.Live) {
                                        connectionState = ViewerConnectionState.StartingStream
                                    }

                                    // Only the candidates that never reached the
                                    // camera. This used to re-send all of them, so
                                    // every candidate travelled twice even when the
                                    // first send had succeeded.
                                    val unconfirmed = localIceCandidates.filter {
                                        candidateDelivered[candidateKeyOf(it)] != true
                                    }
                                    if (unconfirmed.isEmpty()) {
                                        AppLogger.i("ViewerMode", "Camera answered: all ${localIceCandidates.size} local candidate(s) already delivered")
                                    } else {
                                        AppLogger.i("ViewerMode", "Camera answered: re-sending ${unconfirmed.size}/${localIceCandidates.size} unconfirmed candidate(s)")
                                        effectScope.launch {
                                            unconfirmed.forEach { cand ->
                                                launch {
                                                    val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                                                    val candSent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                                                    if (candSent) candidateDelivered[candidateKeyOf(cand)] = true
                                                    AppLogger.d("ViewerMode", "Sent candidate on answer to camera (success=$candSent, mid=${cand.sdpMid})")
                                                }
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    AppLogger.e("ViewerMode", "Error in setRemoteDescription.onSetSuccess", t)
                                }
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting remote answer: $err") }
                            override fun onCreateFailure(err: String?) {}
                        }, answerDesc)
                    }
                    is SignalingMessage.IceCandidate -> {
                        // No per-candidate log here: WebRtcManager records each one
                        // (DEBUG) and the negotiated pair (INFO with a candidate
                        // count). Logging it again in this layer only duplicated
                        // the same lines across two tags.
                        val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.sdp)
                        webRtcManager.addRemoteIceCandidate(targetCamera.deviceId, candidate)
                    }
                    is SignalingMessage.Reject -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.w("ViewerMode", "Camera rejected connection (+${elapsed}ms): ${msg.reason}")
                        retryJob.cancel()
                        countdownJob.cancel()
                        connectionState = ViewerConnectionState.Rejected(msg.reason)
                    }
                    else -> {}
                }
            }
        }
    }

    // Observe DataChannel commands from PA (e.g. RENAME, BATTERY)
    LaunchedEffect(Unit) {
        webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
            when (cmd) {
                is ControlCommand.Rename -> {
                    cameraName = cmd.name
                    // Update local storage
                    val existing = securityRepo.getPairedDevice(peerId) ?: targetCamera
                    if (existing != null) {
                        securityRepo.addPairedDevice(existing.copy(name = cmd.name))
                        AppLogger.i("ViewerMode", "Updated paired camera name from DataChannel to: ${cmd.name}")
                    }
                }
                is ControlCommand.Battery -> {
                    paBatteryInfo = BatteryInfo(cmd.percentage, cmd.isCharging)
                }
                is ControlCommand.NightModeReport -> {
                    try {
                        val mode = NightModeSetting.valueOf(cmd.mode)
                        nightModeSetting = mode
                        securityRepo.setNightModeSetting(mode)
                    } catch (_: Exception) {}
                    isCameraNightActive = cmd.isNightActive
                    AppLogger.i("ViewerMode", "Received NightModeReport from camera: mode=${cmd.mode}, active=${cmd.isNightActive}")
                }
                is ControlCommand.FpsReport -> {
                    streamFps = cmd.fps
                    webRtcManager.setRemoteFps(cmd.fps)
                    AppLogger.i("ViewerMode", "Received FpsReport from camera: ${cmd.fps} fps")
                }
                is ControlCommand.TorchReport -> {
                    isTorchActive = cmd.enabled
                    AppLogger.i("ViewerMode", "Received TorchReport from camera: enabled=${cmd.enabled}")
                }
                is ControlCommand.LensListReport -> {
                    // The camera published its lens list; apply it and adopt the
                    // camera's active lens. A report is also an implicit answer to
                    // a pending switch, so the button never stays greyed out.
                    availableLenses = cmd.lenses
                    webRtcManager.applyRemoteLensList(cmd.lenses, cmd.activeLensId)
                    AppLogger.i("ViewerMode", "Received LensListReport: ${cmd.lenses.size} lens(es), active=${cmd.activeLensId}")
                    if (pendingLensId != null) {
                        val isPendingNowActive = pendingLensId == cmd.activeLensId
                        val pendingGone = cmd.lenses.none { it.id == pendingLensId && it.available }
                        if (isPendingNowActive || pendingGone) {
                            settleLensSwitch(pendingLensId, "LensListReport(active=${cmd.activeLensId})")
                        }
                    }
                }
                is ControlCommand.LensSwitchResult -> {
                    AppLogger.i(
                        "ViewerMode",
                        "Received LensSwitchResult: lens=${cmd.lensId}, status=${cmd.status}, " +
                            "active=${cmd.activeLensId}, message=${cmd.message}"
                    )
                    settleLensSwitch(cmd.lensId, cmd.status)
                    if (cmd.status == "FAILED") {
                        android.widget.Toast.makeText(
                            context,
                            "镜头切换失败" + (cmd.message?.let { "：$it" } ?: ""),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is ControlCommand.SwitchLens -> {
                    // Camera-side echo; nothing to do on the viewer.
                }
                else -> {}
            }
        }
    }

    // Periodically poll local battery and query PA battery
    LaunchedEffect(targetCamera) {
        while (isActive) {
            pbBatteryInfo = BatteryHelper.getBatteryInfo(context)
            if (targetCamera != null) {
                webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.QueryBattery)
            }
            delay(15_000L)
        }
    }

    // Observe WebRTC connection state
    LaunchedEffect(Unit) {
        webRtcManager.connectionState.collect { state ->
            AppLogger.i("ViewerMode", "WebRTC PeerConnectionState changed to: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    val now = System.currentTimeMillis()
                    val step3Dur = (now - stepStartTimeMs).coerceAtLeast(50L)
                    stepStartTimeMs = now
                    connectionProgress = connectionProgress.copy(
                        currentStep = ViewerConnectionStep.STARTING_VIDEO,
                        step1DurationMs = connectionProgress.step1DurationMs ?: 100L,
                        step2DurationMs = connectionProgress.step2DurationMs ?: 200L,
                        step3DurationMs = step3Dur
                    )
                    hasSymmetricNatError = false
                    if (hasRenderedFirstFrame) {
                        completeConnectionToLive()
                    } else {
                        coroutineScope.launch {
                            delay(ViewerConnectionProgress.FIRST_FRAME_FALLBACK_TIMEOUT_MS)
                            if (!isUserExited &&
                                targetCamera != null &&
                                webRtcManager.getPeerConnectionState(targetCamera.deviceId) == PeerConnection.PeerConnectionState.CONNECTED
                            ) {
                                completeConnectionToLive()
                            }
                        }
                    }
                    targetCamera?.let { cam ->
                        coroutineScope.launch {
                            delay(500L)
                            webRtcManager.sendDataChannelCommand(cam.deviceId, ControlCommand.QueryBattery)
                            delay(1500L)
                            webRtcManager.sendDataChannelCommand(cam.deviceId, ControlCommand.QueryBattery)
                        }
                    }
                }
                PeerConnection.PeerConnectionState.CONNECTING -> {
                    if (connectionState !is ViewerConnectionState.StartingStream) {
                        connectionState = ViewerConnectionState.Connecting
                    }
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    connectionState = ViewerConnectionState.Failed
                    hasSymmetricNatError = true
                    AppLogger.e("ViewerMode", "WebRTC connection failed (potential symmetric NAT traversal failure)")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    connectionState = ViewerConnectionState.Disconnected
                    AppLogger.w("ViewerMode", "WebRTC connection disconnected, scheduling auto-reconnect in 3s")
                    if (!isUserExited) {
                        coroutineScope.launch {
                            delay(3000L)
                            if (!isUserExited && connectionState is ViewerConnectionState.Disconnected) {
                                AppLogger.i("ViewerMode", "Auto-reconnecting after disconnect...")
                                reconnectTrigger++
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // Auto-reconnect when signaling reconnects after a network drop
    LaunchedEffect(Unit) {
        signalingClient.isConnectedFlow.collect { connected ->
            if (connected &&
                !isUserExited &&
                (connectionState is ViewerConnectionState.Disconnected ||
                    connectionState is ViewerConnectionState.Failed ||
                    connectionState is ViewerConnectionState.CameraTimeout)
            ) {
                AppLogger.i("ViewerMode", "Signaling restored, triggering camera reconnect")
                reconnectTrigger++
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { isAspectFit = !isAspectFit }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val aspect = videoAspectRatio ?: if (rotationDegrees.toInt() % 180 != 0) (9f / 16f) else (16f / 9f)
        val containerAspect = containerWidth / containerHeight

        val (viewWidth, viewHeight) = if (isAspectFit) {
            if (aspect > containerAspect) {
                Pair(containerWidth, containerWidth / aspect)
            } else {
                Pair(containerHeight * aspect, containerHeight)
            }
        } else {
            if (aspect > containerAspect) {
                Pair(containerHeight * aspect, containerHeight)
            } else {
                Pair(containerWidth, containerWidth / aspect)
            }
        }

        // Video Viewport with dynamic aspect-ratio and rotation support
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    init(webRtcManager.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            AppLogger.d("ViewerMode", "First remote frame rendered")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                hasRenderedFirstFrame = true
                                if (connectionProgress.currentStep == ViewerConnectionStep.STARTING_VIDEO ||
                                    (targetCamera != null && webRtcManager.getPeerConnectionState(targetCamera.deviceId) == PeerConnection.PeerConnectionState.CONNECTED)
                                ) {
                                    completeConnectionToLive()
                                }
                            }
                        }

                        override fun onFrameResolutionChanged(vw: Int, vh: Int, rot: Int) {
                            AppLogger.i("ViewerMode", "Remote frame resolution changed: ${vw}x${vh}, rot=$rot")
                            val rw = if (rot == 90 || rot == 270) vh else vw
                            val rh = if (rot == 90 || rot == 270) vw else vh
                            if (rw > 0 && rh > 0) {
                                val newAspect = rw.toFloat() / rh.toFloat()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    videoAspectRatio = newAspect
                                }
                            }
                        }
                    })
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(false)
                    surfaceRenderer = this
                    webRtcManager.setRemoteVideoRotation(rotationDegrees.toInt())
                    webRtcManager.attachRemoteRenderer(this)
                }
            },
            update = { renderer ->
                surfaceRenderer = renderer
                webRtcManager.setRemoteVideoRotation(rotationDegrees.toInt())
                webRtcManager.attachRemoteRenderer(renderer)
                renderer.setScalingType(
                    if (isAspectFit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
            },
            onRelease = { renderer ->
                surfaceRenderer = null
                webRtcManager.detachRemoteRenderer(renderer)
                try {
                    renderer.release()
                } catch (e: Exception) {
                    AppLogger.w("ViewerModeScreen", "Error releasing SurfaceViewRenderer in onRelease: ${e.message}")
                }
            },
            modifier = Modifier.size(viewWidth, viewHeight)
        )

        // Overlay Controls
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top overlay + dedicated lens switcher strip.
                //
                // The lens chips MUST NOT live inside the top bar's Row: that
                // Row is fillMaxWidth + SpaceBetween and already carries the
                // back button, device name, two battery readouts, night mode and
                // torch. Adding 3-4 lens chips overflows it and Compose clips
                // the trailing children — which is exactly why the switcher
                // looked like it did not exist at all. A separate strip below
                // the bar is always reachable.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            exitViewer()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.exit_viewer_mode))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isLive) StatusLive else MaterialTheme.colorScheme.error, CircleShape)
                                )
                                Text(
                                    text = connectionStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isLive) StatusLive else MaterialTheme.colorScheme.error
                                )
                                if (!isLive && !isConnecting) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledTonalButton(
                                        onClick = { reconnectTrigger++ },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(20.dp)
                                    ) {
                                        Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = stringResource(R.string.cd_camera_battery),
                                        modifier = Modifier.size(13.dp),
                                        tint = Color.LightGray
                                    )
                                    Text(
                                        text = stringResource(R.string.viewer_camera_battery_prefix, BatteryHelper.formatPercentage(paBatteryInfo)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = getBatteryColor(paBatteryInfo)
                                    )
                                }

                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Smartphone,
                                        contentDescription = stringResource(R.string.cd_viewer_battery),
                                        modifier = Modifier.size(13.dp),
                                        tint = Color.LightGray
                                    )
                                    Text(
                                        text = stringResource(R.string.viewer_device_battery_prefix, BatteryHelper.formatPercentage(pbBatteryInfo)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = getBatteryColor(pbBatteryInfo)
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Night Mode Toggle Button: Auto -> Night -> Normal -> Auto
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = when (nightModeSetting) {
                                    NightModeSetting.NIGHT -> MaterialTheme.colorScheme.primaryContainer
                                    NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                                    NightModeSetting.NORMAL -> MaterialTheme.colorScheme.surfaceContainer
                                },
                                modifier = Modifier.clickable {
                                    val nextSetting = when (nightModeSetting) {
                                        NightModeSetting.AUTO -> NightModeSetting.NIGHT
                                        NightModeSetting.NIGHT -> NightModeSetting.NORMAL
                                        NightModeSetting.NORMAL -> NightModeSetting.AUTO
                                    }
                                    nightModeSetting = nextSetting
                                    securityRepo.setNightModeSetting(nextSetting)
                                    targetCamera?.let { cam ->
                                        webRtcManager.sendDataChannelCommand(
                                            cam.deviceId,
                                            ControlCommand.SetNightMode(nextSetting.name)
                                        )
                                    }
                                    AppLogger.i("ViewerMode", "Toggled night mode to: $nextSetting")
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = when (nightModeSetting) {
                                            NightModeSetting.AUTO -> Icons.Default.BrightnessAuto
                                            NightModeSetting.NIGHT -> Icons.Default.Nightlight
                                            NightModeSetting.NORMAL -> Icons.Default.WbSunny
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = when (nightModeSetting) {
                                            NightModeSetting.NIGHT -> MaterialTheme.colorScheme.primary
                                            NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.primary else Color.White
                                            NightModeSetting.NORMAL -> Color.White
                                        }
                                    )
                                    Text(
                                        text = when (nightModeSetting) {
                                            NightModeSetting.AUTO -> stringResource(R.string.night_mode_auto)
                                            NightModeSetting.NIGHT -> stringResource(R.string.night_mode_night)
                                            NightModeSetting.NORMAL -> stringResource(R.string.night_mode_normal)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when (nightModeSetting) {
                                            NightModeSetting.NIGHT -> MaterialTheme.colorScheme.onPrimaryContainer
                                            NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                                            NightModeSetting.NORMAL -> Color.White
                                        }
                                    )
                                }
                            }

                            // Torch Button
                            IconButton(onClick = {
                                if (targetCamera != null) {
                                    isTorchActive = !isTorchActive
                                    webRtcManager.sendDataChannelCommand(
                                        targetCamera.deviceId,
                                        ControlCommand.Torch(isTorchActive)
                                    )
                                }
                            }) {
                                Icon(
                                    imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = stringResource(R.string.cd_torch),
                                    tint = if (isTorchActive) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                    }
                }

                // Lens switcher strip (scrollable so 4+ lenses never clip).
                if (availableLenses.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableLenses.forEach { lens ->
                            val selected = lens.id == activeLensId
                            val pending = lens.id == pendingLensId
                            // A lens can be reported but fail to switch in (the
                            // sensor refused to open). Keep the slot visible but
                            // unselectable.
                            val enabled = lens.available && pendingLensId == null
                            Surface(
                                color = when {
                                    !lens.available -> Color.Black.copy(alpha = 0.25f)
                                    pending -> MaterialTheme.colorScheme.secondary
                                    selected -> MaterialTheme.colorScheme.primary
                                    else -> Color.Black.copy(alpha = 0.55f)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = enabled) { switchLens(lens.id) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (pending) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = if (lens.available) lens.label else "× ${lens.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            !lens.available -> Color.White.copy(alpha = 0.4f)
                                            pending || selected -> MaterialTheme.colorScheme.onPrimary
                                            else -> Color.White
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                }

                // Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Rotate 90 Degrees Button
                    FilledTonalIconButton(
                        onClick = {
                            val newRotation = (rotationDegrees + 90f) % 360f
                            rotationDegrees = newRotation
                            videoAspectRatio?.let { currentAspect ->
                                if (currentAspect > 0f) {
                                    videoAspectRatio = 1f / currentAspect
                                }
                            }
                            webRtcManager.setRemoteVideoRotation(newRotation.toInt())
                            AppLogger.i("ViewerMode", "Rotate button clicked: new rotationDegrees=$newRotation")
                        }
                    ) {
                        Icon(Icons.Default.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_90))
                    }

                    // Aspect Ratio (Fit / Fill) Toggle Button
                    FilledTonalIconButton(
                        onClick = {
                            isAspectFit = !isAspectFit
                            AppLogger.i("ViewerMode", "Aspect Fit/Fill toggle clicked: isAspectFit=$isAspectFit")
                        }
                    ) {
                        Icon(
                            imageVector = if (isAspectFit) Icons.Default.Fullscreen else Icons.Default.FitScreen,
                            contentDescription = if (isAspectFit) stringResource(R.string.cd_fill_screen) else stringResource(R.string.cd_fit_screen)
                        )
                    }

                    // Push-to-Talk Button
                    Surface(
                        shape = CircleShape,
                        color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .height(56.dp)
                            .pointerInput(targetCamera?.deviceId) {
                                detectTapGestures(
                                    onPress = {
                                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (!hasMic) {
                                            val hasRequested = prefs.getBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", false)
                                            if (!hasRequested) {
                                                prefs.edit().putBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", true).apply()
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                isMicPermanentlyDenied = checkIsMicPermanentlyDenied()
                                                showMicPermissionDialog = true
                                            }
                                            return@detectTapGestures
                                        }

                                        isPttPressed = true
                                        val wasAudioEnabled = isAudioEnabled
                                        if (wasAudioEnabled) {
                                            webRtcManager.setRemoteAudioEnabled(false)
                                        }
                                        webRtcManager.setPttActive(true)
                                        targetCamera?.let {
                                            webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStart)
                                        }
                                        AppLogger.i("ViewerMode", "PTT pressed: transmitting voice")
                                        try {
                                            awaitRelease()
                                        } finally {
                                             isPttPressed = false
                                            webRtcManager.setPttActive(false)
                                            targetCamera?.let {
                                                webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStop)
                                            }
                                            if (wasAudioEnabled) {
                                                webRtcManager.setRemoteAudioEnabled(true)
                                            }
                                            AppLogger.i("ViewerMode", "PTT released: audio stopped")
                                        }
                                    }
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Text(
                                text = if (isPttPressed) stringResource(R.string.viewer_ptt_transmitting) else stringResource(R.string.viewer_ptt_hold_to_talk),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Audio Playback Button (Mute/Unmute)
                    FilledTonalIconToggleButton(
                        checked = isAudioEnabled,
                        onCheckedChange = { enabled ->
                            isAudioEnabled = enabled
                            securityRepo.setViewerAudioPlaybackEnabled(enabled)
                            webRtcManager.setRemoteAudioEnabled(enabled)
                        }
                    ) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (isAudioEnabled) stringResource(R.string.cd_audio_playback_on) else stringResource(R.string.cd_audio_playback_off)
                        )
                    }
                }
            }
        }

        // Persistent compact battery badge when controls are auto-hidden
        if (!isControlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = stringResource(R.string.cd_camera_battery),
                            modifier = Modifier.size(12.dp),
                            tint = Color.LightGray
                        )
                        Text(
                            text = BatteryHelper.formatPercentage(paBatteryInfo),
                            style = MaterialTheme.typography.labelSmall,
                            color = getBatteryColor(paBatteryInfo)
                        )
                    }

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = stringResource(R.string.cd_viewer_battery),
                            modifier = Modifier.size(12.dp),
                            tint = Color.LightGray
                        )
                        Text(
                            text = BatteryHelper.formatPercentage(pbBatteryInfo),
                            style = MaterialTheme.typography.labelSmall,
                            color = getBatteryColor(pbBatteryInfo)
                        )
                    }
                }
            }
        }

        // Center 4-Step Live Connection Progress Card when Connecting
        if (isConnecting) {
            ViewerConnectionProgressCard(
                cameraName = displayName,
                progress = connectionProgress,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 360.dp)
            )
        }

        // Center Reconnect / Timeout Overlay when Disconnected, Failed, or CameraTimeout
        if (connectionState is ViewerConnectionState.Disconnected ||
            connectionState is ViewerConnectionState.Failed ||
            connectionState is ViewerConnectionState.CameraTimeout
        ) {
            val isTimeout = connectionState is ViewerConnectionState.CameraTimeout
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .widthIn(max = 360.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (isTimeout) Icons.Default.TimerOff else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (isTimeout) stringResource(R.string.viewer_camera_timeout_desc)
                        else stringResource(R.string.viewer_reconnect_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { reconnectTrigger++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.viewer_reconnect_now))
                    }
                }
            }
        }

        // Symmetric NAT Error Dialog
        if (hasSymmetricNatError) {
            AlertDialog(
                onDismissRequest = { hasSymmetricNatError = false },
                title = { Text(stringResource(R.string.viewer_status_connection_failed)) },
                text = {
                    Text(stringResource(R.string.viewer_symmetric_nat_desc))
                },
                confirmButton = {
                    TextButton(onClick = {
                        hasSymmetricNatError = false
                        exitViewer()
                    }) {
                        Text(stringResource(R.string.action_exit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hasSymmetricNatError = false
                        reconnectTrigger++
                    }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            )
        }

        if (showMicPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showMicPermissionDialog = false },
                title = { Text(stringResource(R.string.permission_dialog_title)) },
                text = { Text(stringResource(R.string.permission_audio_reason)) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isMicPermanentlyDenied) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                prefs.edit().putBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", true).apply()
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Text(
                            if (isMicPermanentlyDenied) stringResource(R.string.permission_open_settings)
                            else stringResource(R.string.permission_grant)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMicPermissionDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        AudioRoutingManager.routeToSpeaker(context, adjustVolume = isAudioEnabled)
        onDispose {
            webRtcManager.stopViewerAudioCapture()
            AudioRoutingManager.restoreNormalMode(context)
            surfaceRenderer?.let { renderer ->
                surfaceRenderer = null
                webRtcManager.detachRemoteRenderer(renderer)
                try {
                    renderer.release()
                } catch (e: Exception) {
                    AppLogger.w("ViewerModeScreen", "Error releasing SurfaceViewRenderer in onDispose: ${e.message}")
                }
            }
        }
    }
}

@Composable
private fun ViewerConnectionProgressCard(
    cameraName: String,
    progress: ViewerConnectionProgress,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.viewer_connecting_to_title, cameraName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                progress.typicalConnectMs?.let { typicalMs ->
                    Text(
                        text = stringResource(
                            R.string.viewer_usually_connects_in,
                            ViewerConnectionProgress.formatDurationSec(typicalMs)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            ViewerConnectionStep.entries.forEach { step ->
                val isCompleted = progress.isStepCompleted(step)
                val isActive = progress.isStepActive(step)
                val durationMs = progress.stepDurationMs(step)

                val stepText = when (step) {
                    ViewerConnectionStep.CONNECTING_NETWORK -> when {
                        isCompleted -> stringResource(R.string.viewer_step_network_done)
                        else -> stringResource(R.string.viewer_step_network_active)
                    }
                    ViewerConnectionStep.REACHING_CAMERA -> when {
                        isCompleted -> stringResource(R.string.viewer_step_camera_done)
                        isActive && progress.isCameraWakingUp -> stringResource(R.string.viewer_step_camera_waking)
                        isActive -> stringResource(R.string.viewer_step_camera_active)
                        else -> stringResource(R.string.viewer_step_camera_pending)
                    }
                    ViewerConnectionStep.ESTABLISHING_LINK -> when {
                        isCompleted -> stringResource(R.string.viewer_step_link_done)
                        isActive -> stringResource(R.string.viewer_step_link_active)
                        else -> stringResource(R.string.viewer_step_link_pending)
                    }
                    ViewerConnectionStep.STARTING_VIDEO -> when {
                        isCompleted -> stringResource(R.string.viewer_step_video_done)
                        isActive -> stringResource(R.string.viewer_step_video_active)
                        else -> stringResource(R.string.viewer_step_video_pending)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isCompleted -> Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusLive,
                                    modifier = Modifier.size(18.dp)
                                )
                                isActive -> CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = Color.Gray.copy(alpha = 0.55f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = stepText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                isCompleted -> Color.White.copy(alpha = 0.9f)
                                isActive -> Color.White
                                else -> Color.Gray
                            },
                            modifier = Modifier.weight(1f)
                        )

                        if (isCompleted && durationMs != null) {
                            Text(
                                text = stringResource(
                                    R.string.viewer_step_duration_sec,
                                    ViewerConnectionProgress.formatDurationSec(durationMs)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusLive
                            )
                        }
                    }

                    if (step == ViewerConnectionStep.REACHING_CAMERA && progress.shouldShowCountdown()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 30.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.viewer_reaching_attempt_countdown,
                                    progress.attempt,
                                    progress.maxAttempts,
                                    progress.remainingTimeoutSec
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LinearProgressIndicator(
                                progress = { progress.timeoutProgressFraction() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.DarkGray
                            )
                            if (progress.shouldShowSlowHint()) {
                                Text(
                                    text = stringResource(R.string.viewer_reaching_slow_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getBatteryColor(info: BatteryInfo?): Color {
    return if (info != null && info.percentage in 0..15 && !info.isCharging) {
        Color(0xFFFF6E6E)
    } else {
        Color.White
    }
}
