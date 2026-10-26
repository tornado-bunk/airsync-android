package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Singleton that manages the WebSocket connection to the AirBridge relay server.
 * Runs alongside the local WebSocket connection as a fallback for remote communication.
 *
 * Uses HMAC-SHA256 challenge-response authentication:
 * 1. Server sends a challenge with a random nonce
 * 2. Client responds with register containing HMAC(K, nonce|pairingId|role) where K = SHA256(secret_raw)
 */
object AirBridgeClient {
    private const val TAG = "AirBridgeClient"

    // Connection state
    enum class State {
        DISCONNECTED,
        CONNECTING,
        CHALLENGE_RECEIVED,  // Received nonce, computing HMAC
        REGISTERING,
        WAITING_FOR_PEER,
        RELAY_ACTIVE,
        FAILED
    }

    // Connection state mutable state flow
    private val _connectionState = MutableStateFlow(State.DISCONNECTED)

    val connectionState: StateFlow<State> = _connectionState

    // WebSocket connection
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    // Symmetric key for relay encryption/decryption
    @Volatile private var symmetricKey: SecretKey? = null
    @Volatile private var relayProtocol: RelayProtocol? = null
    private var appContext: Context? = null

    // Manual disconnect flag
    private val isManuallyDisconnected = AtomicBoolean(false)

    // Reconnect backoff
    private var reconnectJob: Job? = null
    private var statusQueryJob: Job? = null

    // Number of consecutive reconnect attempts, used for exponential backoff calculation.
    private var reconnectAttempt = 0

    // Maximum backoff delay of 30 seconds to prevent excessively long waits.
    private val maxReconnectDelay = 30_000L // 30 seconds

    // Prevent concurrent connect attempts to
    private val connectInProgress = AtomicBoolean(false)
    private val connectRequestGeneration = AtomicLong(0L)
    private val activeConnectionGeneration = AtomicLong(0L)

    // Cached view of last status_reply from relay
    @Volatile
    private var lastStatusBothConnected: Boolean = false
    private val _peerReallyActive = MutableStateFlow(false)
    val peerReallyActive: StateFlow<Boolean> = _peerReallyActive

    // Message callback — routes relayed messages to the existing handler
    private var onMessageReceived: ((Context, String) -> Unit)? = null

    // Updates the connection state and logs the transition reason.
    private fun setState(newState: State, _reason: String) {
        _connectionState.value = newState
        if (newState != State.RELAY_ACTIVE) {
            _peerReallyActive.value = false
        }
        if (newState == State.RELAY_ACTIVE) {
            // Fresh relay session: the peer may have restarted and its transport
            // generation counter starts over, so stale higher generations must
            // not block the next negotiation round.
            WebSocketUtil.resetTransportNegotiationState()
        }
    }

    /**
     * Connects to the AirBridge relay server.
     * Reads configuration from DataStore.
     */
    fun connect(context: Context) {
        appContext = context.applicationContext
        isManuallyDisconnected.set(false)

        // Guard against duplicate connect attempts while a usable relay connection is already active/in-flight.
        if (isRelayConnectedOrConnecting()) {
            return
        }

        if (!connectInProgress.compareAndSet(false, true)) {
            return
        }

        val requestGeneration = connectRequestGeneration.incrementAndGet()
        // Read config and connect in background to avoid blocking the caller and allow suspend functions.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First check if AirBridge is enabled and config is present before attempting connection.
                val ds = DataStoreManager.getInstance(context)
                // If disabled, set state and exit early to avoid unnecessary connection attempts and log spam.
                val enabled = ds.getAirBridgeEnabled().first()
                // If AirBridge is disabled, set state to DISCONNECTED and return early to prevent connection attempts and log spam.
                if (!enabled) {
                    setState(State.DISCONNECTED, "AirBridge disabled in settings")
                    return@launch
                }
                ds.setUserManuallyDisconnected(false)
                val relayUrl = ds.getAirBridgeRelayUrl().first()
                val pairingId = ds.getAirBridgePairingId().first()
                val secretRaw = ds.getAirBridgeSecret().first()

                // Validate config values before connecting to prevent futile attempts and log spam.
                if (relayUrl.isBlank()) {
                    Log.w(TAG, "Relay URL is empty, skipping connection")
                    setState(State.DISCONNECTED, "Missing relay URL")
                    return@launch
                }

                // Pairing ID and secret are required for registration, so treat missing values as failed state to prompt user action.
                if (pairingId.isBlank() || secretRaw.isBlank()) {
                    Log.w(TAG, "Pairing ID or secret is empty, skipping connection")
                    setState(State.FAILED, "Missing pairing credentials")
                    return@launch
                }

                // Load/refresh symmetric key for relay encryption/decryption.
                // Fallback to network-aware records if lastConnected is empty/stale.
                if (symmetricKey == null) {
                    symmetricKey = resolveSymmetricKey(ds)
                }

                if (symmetricKey == null) {
                    Log.e(TAG, "SECURITY: No symmetric key resolved — refusing relay connection to prevent plaintext transport")
                    setState(State.FAILED, "No encryption key available")
                    return@launch
                }

                TrustedNetworks.initialize(context, android.util.Base64.encodeToString(symmetricKey!!.encoded, android.util.Base64.NO_WRAP))
                if (requestGeneration != connectRequestGeneration.get()) return@launch
                // A relay-first pairing must survive a restart before LAN's first successful handshake.
                ds.saveAirBridgeSymmetricKey(pairingId, android.util.Base64.encodeToString(symmetricKey!!.encoded, android.util.Base64.NO_WRAP))
                // Pass raw secret — HMAC computation happens after receiving challenge
                connectInternal(relayUrl, pairingId, secretRaw)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read AirBridge config")
                setState(State.FAILED, "Failed reading persisted config")
            } finally {
                if (requestGeneration == connectRequestGeneration.get()) connectInProgress.set(false)
            }
        }
    }

    /**
     * Allows LAN flow to explicitly refresh the relay key, so transport switching is seamless.
     */
    fun updateSymmetricKey(base64Key: String?) {
        if (base64Key != null) appContext?.let { TrustedNetworks.initialize(it, base64Key) }
        if (base64Key == null) {
            symmetricKey = null
            return
        }
        try {
            symmetricKey = CryptoUtil.decodeKey(base64Key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode symmetric key; keeping previous key", e)
        }
    }

    // Resolves the symmetric key for relay encryption/decryption.
    private suspend fun resolveSymmetricKey(ds: DataStoreManager): SecretKey? {
        ds.getAirBridgeSymmetricKey().first()?.let { saved ->
            runCatching { CryptoUtil.decodeKey(saved) }.getOrNull()?.let { return it }
        }
        // First try to get the most recently connected device's key, which is the most likely to be valid.
        val fromLast = ds.getLastConnectedDevice().first()?.symmetricKey
            ?.let { CryptoUtil.decodeKey(it) }
        // If that fails, look through all known devices for the most recently connected one with a valid key.
        if (fromLast != null) return fromLast

        // Only consider devices that have connected at least once (lastConnected > 0) to avoid using stale keys from old records that were never active.
        val fromNetwork = ds.getAllNetworkDeviceConnections().first()
            .sortedByDescending { it.lastConnected }
            .firstNotNullOfOrNull { conn ->
                conn.symmetricKey?.let { CryptoUtil.decodeKey(it) }
            }
        return fromNetwork
    }

    /**
     * Ensures relay connection is active when enabled.
     * If immediate=true, cancels pending backoff reconnect and retries now.
     */
    fun ensureConnected(context: Context, immediate: Boolean = false) {
        // Create a new coroutine scope for this operation to avoid blocking the caller and allow suspend functions.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = DataStoreManager.getInstance(context)
                val enabled = ds.getAirBridgeEnabled().first()
                if (!enabled) {
                    return@launch
                }
                // If already connected or in the process of connecting, do nothing. Otherwise, attempt to connect.
                when (_connectionState.value) {
                    State.RELAY_ACTIVE,
                    State.WAITING_FOR_PEER,
                    State.REGISTERING,
                    State.CHALLENGE_RECEIVED,
                    State.CONNECTING -> {
                        // Already connected/connecting
                        return@launch
                    }
                    else -> {
                        if (immediate) {
                            reconnectJob?.cancel()
                            reconnectJob = null
                            reconnectAttempt = 0
                        }
                        connect(context)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureConnected failed")
            }
        }
    }

    /** TCP sockets belong to the old route and cannot be carried across a physical handoff. */
    fun networkChanged(context: Context) {
        if (isManuallyDisconnected.get()) return
        connectRequestGeneration.incrementAndGet()
        activeConnectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        statusQueryJob?.cancel()
        statusQueryJob = null
        WebSocketUtil.stopLanFirstRelayProbe("network_changed")
        webSocket?.cancel()
        webSocket = null
        relayProtocol = null
        reconnectAttempt = 0
        connectInProgress.set(false)
        setState(State.DISCONNECTED, "Network changed")
        ensureConnected(context, immediate = true)
    }

    /**
     * Disconnects from the relay server. Disables auto-reconnect.
     */
    fun disconnect() {
        connectRequestGeneration.incrementAndGet()
        connectInProgress.set(false)
        relayProtocol = null
        isManuallyDisconnected.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        WebSocketUtil.stopLanFirstRelayProbe("relay_manual_disconnect")
        statusQueryJob?.cancel()
        statusQueryJob = null
        reconnectAttempt = 0
        lastStatusBothConnected = false
        _peerReallyActive.value = false

        // Close WebSocket connection gracefully.
        try {
            webSocket?.close(1000, "Manual disconnect")
        } catch (_: Exception) {}
        webSocket = null
        activeConnectionGeneration.incrementAndGet()

        client?.dispatcher?.executorService?.shutdown()
        client = null

        setState(State.DISCONNECTED, "Manual disconnect")
    }

    /**
     * Frames and encrypts an application message before sending it through the relay.
     * @return true if the message was enqueued successfully.
     */
    @Synchronized
    fun sendMessage(message: String): Boolean {
        // Only allow sending if we have an active WebSocket connection and are in a state that should allow relay messages.
        val ws = webSocket ?: return false
        // Application data requires the authenticated peer handshake to be complete.
        if (_connectionState.value != State.RELAY_ACTIVE) {
            return false
        }

        // Encrypt with the same symmetric key used for local connections.
        val key = symmetricKey
        if (key == null) {
            Log.e(TAG, "SECURITY: Cannot send relay message — no symmetric key available. Dropping message to prevent plaintext leak.")
            return false
        }

        // Encrypt the message for relay transport. If encryption fails, drop the message to prevent plaintext leak.
        val frame = relayProtocol?.wrap(message) ?: return false
        val messageToSend = CryptoUtil.encryptMessage(frame, key)
        if (messageToSend == null) {
            Log.e(TAG, "SECURITY: Encryption failed, dropping message to prevent plaintext leak.")
            return false
        }

        return try {
            ws.send(messageToSend)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send relay message")
            false
        }
    }

    /**
     * Returns true if the relay is active and ready to forward messages.
     */
    fun isRelayActive(): Boolean = _connectionState.value == State.RELAY_ACTIVE

    /**
     * Returns true if relay transport is already usable or being established.
     * Useful to suppress noisy LAN reconnect loops while relay failover is in progress.
     */
    fun isRelayConnectedOrConnecting(): Boolean {
        return when (_connectionState.value) {
            State.RELAY_ACTIVE,
            State.WAITING_FOR_PEER,
            State.REGISTERING,
            State.CHALLENGE_RECEIVED,
            State.CONNECTING -> true
            else -> false
        }
    }

    /**
     * Sets the message handler. Called once during app initialization.
     */
    fun setMessageHandler(handler: (Context, String) -> Unit) {
        onMessageReceived = handler
    }

    // MARK: - HMAC Computation

    /**
     * Computes the HMAC-SHA256 signature and kInit for challenge-response auth.
     *
     * @param secretRaw The plain-text secret from the QR code
     * @param nonce The server-provided nonce from the challenge message
     * @param pairingId The pairing ID
     * @return Pair of (sig, kInit) both hex-encoded
     */
    private fun computeHmac(secretRaw: String, nonce: String, pairingId: String): Pair<String, String> {
        // K = SHA256(secret_raw) as raw bytes
        val kBytes = MessageDigest.getInstance("SHA-256").digest(secretRaw.toByteArray(Charsets.UTF_8))

        // kInit = hex(K) — sent only for session bootstrap
        val kInit = kBytes.joinToString("") { "%02x".format(it) }

        // message = nonce|pairingId|role
        val role = "android"
        val message = "$nonce|$pairingId|$role"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(kBytes, "HmacSHA256"))
        val sig = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        return Pair(sig, kInit)
    }

    /**
     * Internal function to establish WebSocket connection and handle relay protocol.
     * Uses HMAC challenge-response: waits for challenge, then sends register with HMAC sig.
     */
    private fun connectInternal(relayUrl: String, pairingId: String, secretRaw: String) {
        // Allocate a fresh generation so callbacks from older sockets are ignored.
        val generation = activeConnectionGeneration.incrementAndGet()
        relayProtocol = null

        // Set state early to prevent concurrent connect attempts and log spam while connection is in progress.
        setState(State.CONNECTING, "Opening websocket to relay")

        // Normalize the relay URL to ensure it has the correct scheme and path. This also enforces ws:// for private hosts and wss:// for public hosts to prevent user misconfiguration that could lead to plaintext transport over the internet.
        val normalizedUrl = normalizeRelayUrl(relayUrl)
        // Lazily initialize OkHttpClient with timeouts suitable for a long-lived relay connection.
        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS) // Active pings to prevent silent TCP drops
                .build()
        }

        // Build the WebSocket request
        val request = Request.Builder()
            .url(normalizedUrl)
            .build()

        // Create a new WebSocket connection with a listener to handle the relay protocol.
        val listener = object : WebSocketListener() {
            fun isStale(): Boolean = generation != activeConnectionGeneration.get()

            // On open, wait for the server's challenge (do NOT send register immediately)
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStale()) {
                    try {
                        webSocket.close(1000, "Stale relay socket")
                    } catch (_: Exception) {}
                    return
                }
                this@AirBridgeClient.webSocket = webSocket
                setState(State.CONNECTING, "Socket open, waiting for challenge")
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStale()) return
                handleTextMessage(text, webSocket, pairingId, secretRaw)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                webSocket.close(1000, null)
                setState(State.DISCONNECTED, "Socket closing code=$code")
                WebSocketUtil.stopLanFirstRelayProbe("relay_onClosing")
                statusQueryJob?.cancel()
                statusQueryJob = null
                lastStatusBothConnected = false
                _peerReallyActive.value = false
                if (this@AirBridgeClient.webSocket == webSocket) {
                    this@AirBridgeClient.webSocket = null
                }
                scheduleReconnect(relayUrl, pairingId, secretRaw, generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                val msg = if (t is java.io.EOFException) "Server closed connection (EOF)" else (t.message ?: "Unknown error ($t)")
                Log.e(TAG, "Relay connection failed")
                setState(State.FAILED, "Socket failure: $msg")
                WebSocketUtil.stopLanFirstRelayProbe("relay_onFailure")
                statusQueryJob?.cancel()
                statusQueryJob = null
                lastStatusBothConnected = false
                _peerReallyActive.value = false
                if (this@AirBridgeClient.webSocket == webSocket) {
                    this@AirBridgeClient.webSocket = null
                }
                scheduleReconnect(relayUrl, pairingId, secretRaw, generation)
            }
        }

        client!!.newWebSocket(request, listener)
    }

    private fun handleTextMessage(text: String, ws: WebSocket, pairingId: String, secretRaw: String) {
        // First, try to parse as an AirBridge control message
        try {
            val json = JSONObject(text)
            val action = json.optString("action", "")

            when (action) {
                "challenge" -> {
                    // Server sent challenge — compute HMAC and respond with register
                    val nonce = json.optString("nonce", "")
                    if (nonce.isBlank()) {
                        Log.e(TAG, "Challenge received but nonce is empty")
                        setState(State.FAILED, "Invalid challenge from server")
                        return
                    }

                    setState(State.CHALLENGE_RECEIVED, "Computing HMAC signature")

                    val (sig, kInit) = computeHmac(secretRaw, nonce, pairingId)

                    // Send register with HMAC signature
                    val regMsg = JSONObject().apply {
                        put("action", "register")
                        put("role", "android")
                        put("pairingId", pairingId)
                        put("sig", sig)
                        put("kInit", kInit)
                        put("localIp", DeviceInfoUtil.getWifiIpAddress(appContext!!) ?: "unknown")
                        put("port", 0) // Android doesn't run a server, it's the client
                    }

                    setState(State.REGISTERING, "Sending register with HMAC")
                    if (ws.send(regMsg.toString())) {
                        setState(State.WAITING_FOR_PEER, "Registration accepted, waiting peer")
                        // Reset status cache on fresh registration
                        lastStatusBothConnected = false
                        _peerReallyActive.value = false
                    } else {
                        Log.e(TAG, "Failed to send registration")
                        setState(State.FAILED, "Registration send failed")
                    }
                    return
                }
                "relay_started" -> {
                    setState(State.WAITING_FOR_PEER, "Waiting for authenticated peer challenge")
                    val protocol = RelayProtocol("android")
                    relayProtocol = protocol
                    sendEncryptedFrame(ws, protocol.hello)
                    return
                }
                "status_reply" -> {
                    // Health snapshot from relay: record whether both peers are currently attached.
                    val both = json.optBoolean("bothConnected", false)
                    val macActive = json.optBoolean("macActive", false)
                    val androidActive = json.optBoolean("androidActive", false)
                    lastStatusBothConnected = both && macActive && androidActive
                    _peerReallyActive.value = isRelayActive() && lastStatusBothConnected
                    return
                }
                "mac_info" -> {
                    // A relay cannot authenticate LAN endpoint hints. Use the peer's
                    // encrypted transportOffer/transportAnswer messages instead.
                    return
                }
                "error" -> {
                    val msg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Relay server error")
                    setState(State.FAILED, "Server error action: $msg")
                    return
                }
            }
        } catch (_: Exception) {
            // Not a JSON control message, treat as relayed payload
        }

        val key = symmetricKey ?: return
        val protocol = relayProtocol ?: return
        val plaintext = CryptoUtil.decryptMessage(text, key) ?: return
        val result = protocol.receive(plaintext)
        result.reply?.let { reply ->
            sendEncryptedFrame(ws, reply)
            if (_connectionState.value != State.RELAY_ACTIVE) activateRelay()
        }
        result.payload?.let { payload ->
            if (isRelayActive()) appContext?.let { ctx -> onMessageReceived?.invoke(ctx, payload) }
        }
    }

    private fun sendEncryptedFrame(ws: WebSocket, frame: String) {
        val key = symmetricKey ?: return
        val encrypted = CryptoUtil.encryptMessage(frame, key) ?: return
        ws.send(encrypted)
    }

    private fun activateRelay() {
        setState(State.RELAY_ACTIVE, "Server confirmed relay tunnel")
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        startStatusPolling()
        TrustedNetworks.sendCatalog()
        // Advertise effective transport immediately so desktop UI can switch
        // icon/actions without waiting for stale local session cleanup.
        val transport = if (WebSocketUtil.isWifiConnected()) "wifi" else "relay"
        WebSocketUtil.notifyPeerTransportChanged(transport, force = true)
        appContext?.let { ctx ->
            if (!WebSocketUtil.isWifiConnected()) {
                val transportGeneration = WebSocketUtil.nextTransportGeneration()
                WebSocketUtil.sendTransportOffer(
                    context = ctx,
                    reason = "relay_started",
                    generation = transportGeneration
                )
                WebSocketUtil.startLanFirstRelayProbe(
                    context = ctx,
                    immediate = true,
                    source = "relay_started",
                    resetBackoff = true
                )
            }
        }

        // Trigger initial sync via relay now that the tunnel is active
        appContext?.let { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    delay(1000) // Stabilize connection before sending data
                    SyncManager.startPeriodicSync(ctx)
                    SyncManager.performInitialSync(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to perform initial sync via relay")
                }
            }
        }
    }

    // Schedules a reconnect attempt with exponential backoff. Resets the backoff if the connection is successful. Does nothing if the disconnect was manual.
    private fun scheduleReconnect(relayUrl: String, pairingId: String, secretRaw: String, sourceGeneration: Long) {
        if (isManuallyDisconnected.get()) return
        if (sourceGeneration != activeConnectionGeneration.get()) return

        val delayMs = minOf(
            (1L shl minOf(reconnectAttempt, 10)) * 1000L,
            maxReconnectDelay
        )
        reconnectAttempt++

        setState(State.CONNECTING, "Backoff reconnect scheduled in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            if (!isManuallyDisconnected.get() && sourceGeneration == activeConnectionGeneration.get()) {
                connectInternal(relayUrl, pairingId, secretRaw)
            }
        }
    }

    // Periodically asks relay for session health so UI can detect relay-zombie situations.
    private fun startStatusPolling() {
        statusQueryJob?.cancel()
        statusQueryJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRelayActive() && !isManuallyDisconnected.get()) {
                sendStatusQuery()
                delay(5_000)
            }
        }
    }

    private fun sendStatusQuery() {
        val ws = webSocket ?: return
        val msg = JSONObject().apply {
            put("action", "query_status")
        }.toString()
        try {
            ws.send(msg)
        } catch (e: Exception) {
        }
    }

    private fun normalizeRelayUrl(raw: String): String = RelayUrlPolicy.normalize(raw)
}
