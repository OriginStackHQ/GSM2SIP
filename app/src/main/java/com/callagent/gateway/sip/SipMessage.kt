package com.callagent.gateway.sip

import com.callagent.gateway.rtp.SrtpKeys

/**
 * Lightweight SIP message parser and builder.
 * Ported from the Python SIP implementation in sip/sip.py
 */
class SipMessage private constructor(
    val startLine: String,
    val headers: Map<String, String>,
    val body: String
) {

    // Be liberal about whitespace: a few SIP
    // stacks prepend a space/BOM to the status line; that used to look like
    // an unknown request here.
    private val normalizedStartLine: String get() = startLine.trimStart('\uFEFF', ' ', '\t')
    val isRequest: Boolean get() = !normalizedStartLine.startsWith("SIP/", ignoreCase = true)
    val isResponse: Boolean get() = normalizedStartLine.startsWith("SIP/", ignoreCase = true)

    val method: String?
        get() = if (isRequest) normalizedStartLine.trim().split(Regex("\\s+"), limit = 2).firstOrNull() else null

    val statusCode: Int?
        get() = if (isResponse) STATUS_CODE.matchEntire(normalizedStartLine.trim())
            ?.groupValues?.get(1)?.toIntOrNull() else null

    fun header(name: String): String? =
        headers[name.lowercase()]

    val callId: String? get() = header("call-id")
    val cseq: String? get() = header("cseq")
    val via: String? get() = header("via")
    val from: String? get() = header("from")
    val to: String? get() = header("to")
    val contact: String? get() = header("contact")
    val contentType: String? get() = header("content-type")

    /** Request-URI from a request start line, e.g. INVITE sip:x@y SIP/2.0. */
    val requestUri: String?
        get() = if (isRequest) normalizedStartLine.trim().split(Regex("\\s+"), limit = 3).getOrNull(1) else null

    val fromTag: String?
        get() = from?.let { extractTag(it) }

    val toTag: String?
        get() = to?.let { extractTag(it) }

    /** Extract SIP URI user part from a header value like <sip:user@host> */
    fun extractUser(headerValue: String): String? {
        val sipIdx = headerValue.indexOf("sip:")
        if (sipIdx < 0) return null
        val atIdx = headerValue.indexOf('@', sipIdx)
        if (atIdx < 0) return null
        return headerValue.substring(sipIdx + 4, atIdx)
    }

    val callerNumber: String? get() = from?.let { extractUser(it) }

    /** Extract display name from From header, e.g. "Display" <sip:...> */
    val callerDisplayName: String?
        get() {
            val f = from ?: return null
            val q1 = f.indexOf('"')
            if (q1 < 0) return null
            val q2 = f.indexOf('"', q1 + 1)
            if (q2 <= q1) return null
            return f.substring(q1 + 1, q2).trim().ifEmpty { null }
        }

    /** Extract Contact URI, e.g. <sip:user@host:port> → sip:user@host:port */
    val contactUri: String?
        get() {
            val c = contact ?: return null
            val s = c.indexOf('<')
            val e = c.indexOf('>', s + 1)
            return if (s >= 0 && e > s) c.substring(s + 1, e) else c.split(";")[0].trim()
        }

    /** Parse host:port from a SIP URI */
    val contactAddress: Pair<String, Int>?
        get() {
            val uri = contactUri ?: return null
            val hostPart = uri.removePrefix("sip:").substringAfter('@')
            val parts = hostPart.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 5060
            return Pair(host, port)
        }

    /** Extract RTP port from SDP body (m=audio PORT ...) */
    val sdpRtpPort: Int?
        get() {
            val mLine = body.lineSequence().firstOrNull { it.startsWith("m=audio") } ?: return null
            return mLine.split(" ").getOrNull(1)?.toIntOrNull()
        }

    /** Extract IP from SDP body (c=IN IP4 x.x.x.x) */
    val sdpAddress: String?
        get() {
            val cLine = body.lineSequence().firstOrNull { it.startsWith("c=IN IP4") } ?: return null
            return cLine.split(" ").getOrNull(2)?.trim()
        }

    /** Check which codecs the SDP offers */
    val sdpCodecs: List<Pair<Int, String>>
        get() {
            val result = mutableListOf<Pair<Int, String>>()
            body.lineSequence().forEach { line ->
                if (line.startsWith("a=rtpmap:")) {
                    // a=rtpmap:9 G722/8000
                    val parts = line.removePrefix("a=rtpmap:").split(" ", limit = 2)
                    val pt = parts.getOrNull(0)?.toIntOrNull()
                    val codec = parts.getOrNull(1)
                    if (pt != null && codec != null) {
                        result.add(Pair(pt, codec))
                    }
                }
            }
            return result
        }

    /** True when the peer's SDP carries an audio media line. */
    val hasAudioOffer: Boolean
        get() = body.lineSequence().any { it.startsWith("m=audio") }

    /** Payload types in the peer's m=audio line, in offer order. */
    val offeredAudioPayloadTypes: Set<Int>
        get() {
            val mLine = body.lineSequence().firstOrNull { it.startsWith("m=audio") }
                ?: return emptySet()
            return mLine.split(" ")
                .drop(3)
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        }

    /**
     * The payload type this gateway accepts from an offer.
     *
     * Returns -1 when there is no m=audio line, or when the offer contains
     * neither PCMA (8) nor G.722 (9).  The caller refuses that with 488 on an
     * initial INVITE; an in-dialog offer keeps the existing codec instead.
     */
    val sdpPreferredPayloadType: Int
        get() {
            if (!hasAudioOffer) return -1
            val offered = offeredAudioPayloadTypes
            return SipBuilder.acceptPreference().firstOrNull { it in offered } ?: -1
        }

    /**
     * True when the peer's media line asks for SRTP (`RTP/SAVP`).
     *
     * The profile is what decides whether media is protected -- a crypto line
     * on an `RTP/AVP` stream is not an SRTP offer, and answering one with
     * SAVP would send encrypted audio to something expecting plain RTP.
     */
    val sdpIsSavp: Boolean
        get() = body.lineSequence()
            .firstOrNull { it.startsWith("m=audio") }
            ?.contains("RTP/SAVP") == true

    /**
     * The peer's `a=crypto` lines (RFC 4568), in offer order, as
     * (tag, suite, inline-and-params).  Suites this build cannot do are left
     * in: the caller picks, and knowing what was offered makes a failure to
     * agree explainable rather than silent.
     */
    val sdpCryptoLines: List<Triple<Int, String, String>>
        get() = body.lineSequence()
            .filter { it.startsWith("a=crypto:") }
            .mapNotNull { line ->
                // a=crypto:<tag> <suite> inline:<key>[|lifetime][|MKI] [params]
                val parts = line.removePrefix("a=crypto:").trim().split(Regex("\\s+"), limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val tag = parts[0].toIntOrNull() ?: return@mapNotNull null
                val keyParam = parts[2].split(Regex("\\s+"))
                    .firstOrNull { it.startsWith("inline:") }
                    ?.removePrefix("inline:") ?: return@mapNotNull null
                Triple(tag, parts[1], keyParam)
            }
            .toList()

    /** GSM source number from X-GSM-From. */
    val fromNumber: String
        get() = header("x-gsm-from")?.trim().orEmpty()

    /** GSM destination number from X-GSM-To. */
    val toNumber: String
        get() = header("x-gsm-to")?.trim().orEmpty()

    /** Serialize this message back to a SIP packet string */
    fun encode(): String {
        // We store raw header lines for serialization, so the parsed form is
        // echoed back verbatim when there is one.  The `sb`/no-op-loop block
        // that used to sit here built a StringBuilder and threw it away —
        // it appended nothing (the loop body was empty) and the return below
        // never consulted it.
        return raw ?: buildString {
            append(startLine).append("\r\n")
            headers.forEach { (k, v) ->
                append("${k}: ${v}\r\n")
            }
            append("\r\n")
            append(body)
        }
    }

    private var raw: String? = null

    companion object {
        private val HEADER_SEPARATOR = Regex("\\r?\\n\\r?\\n")
        private val STATUS_CODE = Regex("^SIP/\\S+\\s+(\\d{3})(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

        /** Parse a raw SIP message string */
        fun parse(data: String): SipMessage? {
            val separator = HEADER_SEPARATOR.find(data) ?: return null
            val headerSection = data.substring(0, separator.range.first)
            val body = data.substring(separator.range.last + 1)
            val lines = headerSection.split(Regex("\\r?\\n"))
            if (lines.isEmpty() || lines[0].trim().isEmpty()) return null

            val startLine = lines[0].trimStart('\uFEFF')
            val headers = LinkedHashMap<String, String>()
            var previousKey: String? = null
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    previousKey?.let { headers[it] = "${headers[it]} ${line.trim()}" }
                    continue
                }
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    previousKey = key
                }
            }

            return SipMessage(startLine, headers, body).also { it.raw = data }
        }

        private fun extractTag(header: String): String? {
            val idx = header.indexOf(";tag=")
            if (idx < 0) return null
            return header.substring(idx + 5).split(";")[0].trim()
        }
    }
}

/** Builder for constructing SIP messages */
object SipBuilder {
    /**
     * How this gateway identifies itself in SIP traffic, the way a Fritz!Box
     * announces itself as "AVM FRITZ!Box 7530 AX".  Without it the device is
     * anonymous in the server's logs and CDRs.  SipClient sets it once at
     * startup so the configured server domain can be included.
     */
    @Volatile
    var userAgent: String = "GSM2SIP"

    /** Which codecs to offer and accept: "g722", "g711" or "both".
     *
     *  G.722 alone is the default and the reason audio is wideband: when G.711
     *  is offered alongside it, servers routinely pick PCMA and every call ends
     *  up narrowband regardless of what both ends support. */
    @Volatile
    var codecMode: String = "g722"

    /**
     * Transport token for Via, and the matching Contact parameter.
     *
     * Set once when the transport is created.  Via must name the transport the
     * request actually travelled over or responses are routed back wrongly,
     * and Contact must carry `transport=tls` so the server sends inbound
     * requests down the existing TLS connection instead of trying to open a
     * fresh UDP path to a port that is not listening.
     */
    @Volatile
    var transport: String = "UDP"

    private val contactParam: String
        get() = if (transport == "TLS") ";transport=tls" else ""

    private fun branch(): String = "z9hG4bK${(100000000..999999999).random()}"
    private fun tag(): String = "gw${(100000000..999999999).random()}"

    fun register(
        username: String, domain: String, serverPort: Int,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        auth: String? = null
    ): String {
        val uri = "sip:$domain:$serverPort"
        return buildString {
            append("REGISTER $uri SIP/2.0\r\n")
            append("Via: SIP/2.0/$transport $localIp:$localPort;branch=${branch()};rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
            append("To: <sip:$username@$domain>\r\n")
            append("From: <sip:$username@$localIp>;tag=${tag()}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:$username@$localIp:$localPort$contactParam>;expires=120\r\n")
            if (auth != null) append(auth)
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun invite(
        targetUri: String,
        username: String, domain: String,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        localRtpPort: Int,
        fromTag: String = tag(),
        auth: String? = null,
        srtp: SrtpKeys? = null,
        extraHeaders: List<String> = emptyList()
    ): String {
        val sdp = buildOfferSdp(localIp, localRtpPort, srtp)
        return buildString {
            append("INVITE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/$transport $localIp:$localPort;branch=${branch()};rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
            append("To: <$targetUri>\r\n")
            append("From: <sip:$username@$domain>;tag=$fromTag\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq INVITE\r\n")
            append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
            if (auth != null) append(auth)
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.length}\r\n\r\n")
            append(sdp)
        }
    }

    /**
     * A page-mode SIP MESSAGE (RFC 3428) — one request, one response, no
     * dialog.  Used to hand a received SMS to the server.
     *
     * Content-Length counts *bytes*: SMS text is UTF-8 and a message with any
     * non-ASCII character in it would otherwise declare a length shorter than
     * what goes on the wire, and the server would truncate the body.
     */
    fun message(
        targetUri: String,
        fromUser: String, domain: String,
        localIp: String, localPort: Int,
        callId: String, cseq: Int,
        body: String,
        contentType: String = "text/plain;charset=UTF-8",
        extraHeaders: List<String> = emptyList(),
        fromTag: String = tag(),
        auth: String? = null
    ): String = buildString {
        append("MESSAGE $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/$transport $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
        append("User-Agent: $userAgent\r\n")
        append("To: <$targetUri>\r\n")
        append("From: <sip:$fromUser@$domain>;tag=$fromTag\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq MESSAGE\r\n")
        for (h in extraHeaders) append("$h\r\n")
        if (auth != null) append(auth)
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        append(body)
    }

    /**
     * A bare status response for a page-mode request.
     *
     * 202 for a MESSAGE we have taken responsibility for, 4xx to refuse one.
     * No Contact: there is no dialog to route anything into.
     */
    fun statusResponse(
        msg: SipMessage,
        code: Int,
        reason: String,
        extraHeaders: List<String> = emptyList(),
        toTag: String = tag()
    ): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 $code $reason\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("User-Agent: $userAgent\r\n")
            for (h in extraHeaders) append("$h\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun ok200(
        msg: SipMessage,
        username: String, localIp: String, localPort: Int,
        localRtpPort: Int? = null,
        toTag: String = tag(),
        srtp: SrtpKeys? = null,
        srtpTag: Int = 1
    ): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        val sdp = if (localRtpPort != null) buildOfferSdp(localIp, localRtpPort, srtp, srtpTag) else null
        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
            if (sdp != null) {
                append("Content-Type: application/sdp\r\n")
                append("Content-Length: ${sdp.length}\r\n\r\n")
                append(sdp)
            } else {
                append("Content-Length: 0\r\n\r\n")
            }
        }
    }

    /**
     * 200 OK to an offer, carrying exactly one voice payload type.
     *
     * Returns null when [payloadType] is not one this build can answer, so the
     * caller can refuse the offer with 488 instead of answering a codec it
     * cannot decode.  The answer only includes telephone-event when the peer
     * offered it.
     */
    fun ok200Answer(
        msg: SipMessage,
        username: String, localIp: String, localPort: Int,
        localRtpPort: Int,
        payloadType: Int,
        includeDtmf: Boolean,
        toTag: String = tag(),
        srtp: SrtpKeys? = null,
        srtpTag: Int = 1
    ): String? {
        val sdp = buildAnswerSdp(
            localIp, localRtpPort, srtp, srtpTag, payloadType, includeDtmf
        ) ?: return null
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.length}\r\n\r\n")
            append(sdp)
        }
    }

    fun trying100(msg: SipMessage): String = buildString {
        append("SIP/2.0 100 Trying\r\n")
        append("Via: ${msg.via}\r\n")
        append("To: ${msg.to}\r\n")
        append("From: ${msg.from}\r\n")
        append("Call-ID: ${msg.callId}\r\n")
        append("CSeq: ${msg.cseq}\r\n")
        append("Server: $userAgent\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun ringing180(msg: SipMessage, toTag: String = tag()): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 180 Ringing\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Server: $userAgent\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Turn an INVITE down.  Same shape as 180, with the code and reason the
     * caller chose, so the server is told why rather than being answered and
     * bridged to nothing.
     */
    fun reject(msg: SipMessage, code: Int, reason: String, toTag: String = tag()): String {
        val to = msg.to ?: ""
        val toWithTag = if (to.contains(";tag=")) to else "$to;tag=$toTag"
        return buildString {
            append("SIP/2.0 $code $reason\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: $toWithTag\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Server: $userAgent\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    fun ack(
        targetUri: String,
        via: String?, toHeader: String?, fromHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("ACK $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/$transport $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("To: $toHeader\r\n")
        append("From: $fromHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq ACK\r\n")
        append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun bye(
        targetUri: String,
        fromHeader: String?, toHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("BYE $targetUri SIP/2.0\r\n")
        append("Via: SIP/2.0/$transport $localIp:$localPort;branch=${branch()};rport\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("From: $fromHeader\r\n")
        append("To: $toHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq BYE\r\n")
        append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    fun cancel(
        targetUri: String,
        viaHeader: String?, fromHeader: String?, toHeader: String?,
        callId: String?, cseq: Int,
        username: String, localIp: String, localPort: Int
    ): String = buildString {
        append("CANCEL $targetUri SIP/2.0\r\n")
        append("Via: $viaHeader\r\n")
        append("Max-Forwards: 70\r\n")
            append("User-Agent: $userAgent\r\n")
        append("From: $fromHeader\r\n")
        append("To: $toHeader\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq CANCEL\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    /**
     * 200 to an OPTIONS: both a liveness answer and a capability dump.
     *
     * The server qualifies every registered peer with OPTIONS and stores what
     * comes back, so this is the only place it learns what this gateway can
     * do.  Answering with a bare Allow line left it with nothing to store.
     *
     * Everything advertised here is something this build actually implements.
     * There is no Supported: header because this stack supports none of the
     * extensions worth naming -- no 100rel, no replaces, no UPDATE -- and
     * claiming them invites requests it would have to reject mid-call, which
     * is worse than admitting the gap.  The Allow list is likewise the real
     * set of handled methods, not the conventional long one.
     */
    fun optionsResponse(msg: SipMessage, username: String, localIp: String, localPort: Int): String {
        // A capability description, not an offer: no port is reserved and no
        // crypto line appears.  SRTP keys are per-call and generated when the
        // call is set up -- putting one here would publish a key outside any
        // dialog that used it.  This is also what a FRITZ!Box reports, which
        // likewise answers OPTIONS with plain AVP while doing SDES on calls.
        val payloads = when (codecMode) {
            "g711" -> "8 101"
            "both" -> "9 8 101"
            else -> "9 101"
        }
        val sdp = buildString {
            append("v=0\r\n")
            append("o=gateway 0 0 IN IP4 $localIp\r\n")
            append("s=SIP Gateway capabilities\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP $payloads\r\n")
            if (codecMode != "g711") append("a=rtpmap:9 G722/8000\r\n")
            if (codecMode != "g722") {
                append("a=rtpmap:8 PCMA/8000\r\n")
            }
            append("a=rtpmap:101 telephone-event/8000\r\n")
            append("a=fmtp:101 0-16\r\n")
            append("a=ptime:20\r\n")
            append("a=sendrecv\r\n")
        }
        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: ${msg.via}\r\n")
            append("To: ${msg.to}\r\n")
            append("From: ${msg.from}\r\n")
            append("Call-ID: ${msg.callId}\r\n")
            append("CSeq: ${msg.cseq}\r\n")
            append("Contact: <sip:$username@$localIp:$localPort$contactParam>\r\n")
            append("User-Agent: $userAgent\r\n")
            // The real set: CANCEL and MESSAGE included because inbound ones
            // are handled; nothing here is aspirational.
            append("Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, MESSAGE\r\n")
            // text/plain because inbound MESSAGE carries SMS bodies.
            append("Accept: application/sdp, text/plain\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(sdp)
        }
    }

    /**
     * Voice payload types this gateway offers. 101 is added separately.
     */
    val offerVoicePayloadTypes: LinkedHashSet<Int>
        get() = when (codecMode) {
            "g711" -> linkedSetOf(8)
            "both" -> linkedSetOf(9, 8)
            else -> linkedSetOf(9)
        }

    /**
     * Order in which a peer's offer is accepted.
     *
     * Wider than [offerVoicePayloadTypes] deliberately: G.722-only still
     * answers a PCMA-only offer with PCMA rather than failing the call.
     */
    fun acceptPreference(): List<Int> = when (codecMode) {
        "g711" -> listOf(8, 9)
        else -> listOf(9, 8)
    }

    /**
     * @param srtp keying material for this call, or null for plain RTP.  It is
     *   a parameter rather than shared state because every call needs its own
     *   key: one master key reused across calls would let anyone who recorded
     *   an earlier one decrypt every later one.
     * @param srtpTag the crypto tag to answer with.  Answering an offer must
     *   echo the tag that was accepted (RFC 4568 §5.1.2); 1 is right for an
     *   offer we originate.
     */
    private fun buildOfferSdp(
        localIp: String,
        rtpPort: Int,
        srtp: SrtpKeys? = null,
        srtpTag: Int = 1
    ): String = buildString {
        // G.722 is wideband (16 kHz sampling) but its SDP clock rate is
        // written as 8000 per RFC 3551 — a historical quirk, not a typo.
        // telephone-event is always offered: it carries DTMF, not voice.
        val voice = offerVoicePayloadTypes
        val payloads = (voice + 101).joinToString(" ")
        append("v=0\r\n")
        append("o=gateway 0 0 IN IP4 $localIp\r\n")
        append("s=SIP Call\r\n")
        append("c=IN IP4 $localIp\r\n")
        append("t=0 0\r\n")
        // SAVP is the SRTP profile.  The transport name and the crypto line
        // have to agree: one without the other is a malformed offer that a
        // peer may accept and then fail to decrypt.
        append("m=audio $rtpPort ${if (srtp != null) "RTP/SAVP" else "RTP/AVP"} $payloads\r\n")
        if (srtp != null) {
            append("a=crypto:$srtpTag ${srtp.suite.sdpName} inline:${srtp.toInline()}\r\n")
        }
        if (9 in voice) append("a=rtpmap:9 G722/8000\r\n")
        if (8 in voice) append("a=rtpmap:8 PCMA/8000\r\n")
        append("a=rtpmap:101 telephone-event/8000\r\n")
        append("a=fmtp:101 0-16\r\n")
        append("a=ptime:20\r\n")
        append("a=sendrecv\r\n")
    }

    /**
     * An answer carrying exactly one voice payload type, plus 101 only when
     * the peer offered it.  Returns null for a payload type this build cannot
     * speak, which the caller turns into 488.
     */
    private fun buildAnswerSdp(
        localIp: String,
        rtpPort: Int,
        srtp: SrtpKeys? = null,
        srtpTag: Int = 1,
        payloadType: Int,
        includeDtmf: Boolean,
    ): String? {
        if (payloadType !in acceptPreference()) return null
        val payloads = if (includeDtmf) "$payloadType 101" else "$payloadType"
        return buildString {
            append("v=0\r\n")
            append("o=gateway 0 0 IN IP4 $localIp\r\n")
            append("s=SIP Call\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio $rtpPort ${if (srtp != null) "RTP/SAVP" else "RTP/AVP"} $payloads\r\n")
            if (srtp != null) {
                append("a=crypto:$srtpTag ${srtp.suite.sdpName} inline:${srtp.toInline()}\r\n")
            }
            when (payloadType) {
                9 -> append("a=rtpmap:9 G722/8000\r\n")
                8 -> append("a=rtpmap:8 PCMA/8000\r\n")
            }
            if (includeDtmf) {
                append("a=rtpmap:101 telephone-event/8000\r\n")
                append("a=fmtp:101 0-16\r\n")
            }
            append("a=ptime:20\r\n")
            append("a=sendrecv\r\n")
        }
    }
}
