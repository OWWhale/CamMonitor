package com.a0soft.gphone.camonitor.data.webrtc

/**
 * Constants shared by the camera side (sender) and the viewer side (receiver).
 *
 * The camera now streams a SINGLE lens at a time (see
 * `LensStreamController`) — switching sensors reuses the same video track, so the
 * negotiated m-line never changes.
 *
 * [MAX_VIDEO_M_LINES] is therefore no longer "one m-line per lens": it is the
 * number of RECV_ONLY video slots the viewer declares up front so it can still
 * receive a multi-track sender (an older build, or a peer that publishes several
 * video tracks). Our own camera fills only the first slot; the remaining slots
 * stay inactive, which is legal SDP and costs nothing at runtime.
 *
 * Both ends must agree on the value: the viewer declares exactly this many slots
 * in its offer, and any sender must not exceed it.
 */
object MultiLensConstants {

    /**
     * Maximum number of video m-lines declared by the viewer.
     *
     * Keep > 1 unless every peer in the field is guaranteed single-track: a peer
     * that attaches a second video track to an offer with a single video m-line
     * fails the negotiation.
     */
    const val MAX_VIDEO_M_LINES = 4
}
