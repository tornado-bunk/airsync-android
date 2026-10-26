package com.sameerasw.airsync.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.UUID

object TrustedNetworks {
    data class Observation(val network: Network, val fingerprint: String, val address: String, val subnet: String, val gateway: String)
    private var context: Context? = null
    private var pairingKey: String? = null
    private var storageKey = ""
    private val records = linkedMapOf<String, TrustedNetwork>()
    private var pendingRequest: String? = null
    private var pendingDeadline = 0L
    private val verification = LanVerificationWindow { android.os.SystemClock.elapsedRealtime() }
    val isVerifying: Boolean get() = verification.isActive
    private var observation: Observation? = null
    private var peerNetworkIDs = emptySet<String>()
    private val _networks = MutableStateFlow<List<TrustedNetwork>>(emptyList())
    val networks = _networks.asStateFlow()
    private val _status = MutableStateFlow("Unknown network · using Airbridge")
    val status = _status.asStateFlow()
    private val _enrolling = MutableStateFlow(false)
    val enrolling = _enrolling.asStateFlow()

    suspend fun load(context: Context) {
        // The active QR/relay key can precede persistence of lastConnectedDevice.
        if (synchronized(this) { pairingKey != null }) return
        val ds = DataStoreManager.getInstance(context)
        val key = ds.getAirBridgeSymmetricKey().first() ?: ds.getLastConnectedDevice().first()?.symmetricKey
        if (key != null) initialize(context, key)
    }

    @Synchronized fun initialize(context: Context, key: String) {
        this.context = context.applicationContext
        val bytes = runCatching { CryptoUtil.decodeKey(key).encoded }.getOrNull() ?: return
        val next = "catalog.v1." + hash(bytes)
        if (storageKey == next) return
        storageKey = next
        pairingKey = key
        records.clear()
        peerNetworkIDs = emptySet()
        pendingRequest = null
        pendingDeadline = 0
        verification.finish()
        _enrolling.value = false
        val saved = context.getSharedPreferences("trusted_networks", Context.MODE_PRIVATE).getString(next, "[]") ?: "[]"
        runCatching { decode(JSONArray(saved)) }.getOrNull()?.forEach { records[it.id] = it }
        refresh(context)
    }

    fun hasWifiPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun observe(context: Context): List<Observation> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (!wifi && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            val links = cm.getLinkProperties(network) ?: return@mapNotNull null
            val link = links.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return@mapNotNull null
            val address = link.address.hostAddress ?: return@mapNotNull null
            val subnet = TrustedNetwork.subnet(address, link.prefixLength) ?: return@mapNotNull null
            val gateway = links.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: return@mapNotNull null
            val identity = if (wifi) {
                if (!hasWifiPermission(context)) return@mapNotNull null
                val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID } ?: return@mapNotNull null
                val security = if (Build.VERSION.SDK_INT >= 31) {
                    when (val type = info.currentSecurityType) {
                        WifiInfo.SECURITY_TYPE_UNKNOWN -> return@mapNotNull null
                        WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_SAE -> "personal"
                        WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "enterprise"
                        else -> type.toString()
                    }
                } else "legacy"
                "wifi|$ssid|$security"
            } else "ethernet"
            Observation(network, hash("v1|$identity|$gateway|$subnet".toByteArray(Charsets.UTF_8)), address, subnet, gateway)
        }
    }.getOrDefault(emptyList())

    @Synchronized fun refresh(context: Context) {
        val observed = observe(context)
        observation = observed.firstOrNull { candidate -> records.values.any { !it.deleted && it.androidFingerprint == candidate.fingerprint } } ?: observed.firstOrNull()
        if (_enrolling.value && System.currentTimeMillis() > pendingDeadline) {
            pendingRequest = null
            _enrolling.value = false
            _status.value = "Registration timed out. Keep both devices on the same network and retry."
        } else if (!_enrolling.value) publish()
    }

    @Synchronized fun currentObservation(): Observation? = observation
    fun beginVerification() { verification.begin() }
    @Synchronized fun isRecognized(): Boolean = observation?.let { candidate -> records.values.any { !it.deleted && it.androidFingerprint == candidate.fingerprint } } == true
    @Synchronized fun hasCommonNetwork(): Boolean = observation?.let { candidate ->
        records.values.any { !it.deleted && it.androidFingerprint == candidate.fingerprint && it.id in peerNetworkIDs }
    } == true
    @Synchronized fun canConnect(enrollmentRequestID: String? = null, manual: Boolean = false): Boolean {
        if (observation == null || pairingKey == null) return false
        return isRecognized() || (manual && records.isEmpty()) ||
            (enrollmentRequestID != null && enrollmentRequestID == pendingRequest && System.currentTimeMillis() <= pendingDeadline)
    }

    @Synchronized fun proof(context: Context, target: String, requestID: String?, nonce: String): JSONObject? {
        refresh(context)
        val observed = observation ?: return null
        verification.begin(10_000)
        return JSONObject().put("type", "trustedNetworks").put("data", JSONObject()
            .put("op", "proof").put("nonce", nonce).put("target", target)
            .put("address", observed.address).put("fingerprint", observed.fingerprint)
            .put("subnet", observed.subnet).put("gateway", observed.gateway)
            .put("requestID", requestID ?: "").put("firstPairing", records.isEmpty()))
    }

    @Synchronized fun acceptProof(context: Context, data: JSONObject, nonce: String, startedFingerprint: String): Boolean {
        refresh(context)
        if (data.optString("nonce") != nonce || observation?.fingerprint != startedFingerprint) return false
        val catalog = data.optJSONObject("catalog") ?: return false
        if (!merge(catalog)) return false
        val entry = records[data.optString("networkID")] ?: return false
        val observed = observation ?: return false
        if (entry.deleted || entry.androidFingerprint != observed.fingerprint || entry.subnet != observed.subnet || entry.gateway != observed.gateway) return false
        pendingRequest = null
        verification.finish()
        peerNetworkIDs = peerNetworkIDs + entry.id
        _enrolling.value = false
        publish()
        sendCatalog()
        return true
    }

    @Synchronized fun catalog(): JSONObject = JSONObject().put("op", "catalog").put("version", 1).put("entries", JSONArray(records.values.map(::encode)))

    fun sendCatalog() { send(catalog()) }

    @Synchronized fun merge(data: JSONObject): Boolean {
        if (data.optInt("version") != 1) return false
        val raw = data.optJSONArray("entries") ?: return false
        val incoming = runCatching { decode(raw) }.getOrNull() ?: return false
        for (entry in incoming) if (records.containsKey(entry.id) || records.size < 256) records[entry.id] = TrustedNetwork.merge(records[entry.id], entry)
        save()
        return true
    }

    @Synchronized fun rename(id: String, name: String) = mutate(id, name, false)
    @Synchronized fun remove(id: String) = mutate(id, null, true)
    private fun mutate(id: String, name: String?, deleted: Boolean) {
        val entry = records[id]?.takeIf { !it.deleted } ?: return
        val trimmed = name?.trim()
        if (trimmed != null && (trimmed.isEmpty() || trimmed.length > 80)) return
        records[id] = entry.copy(name = trimmed ?: entry.name, deleted = deleted, revision = (records.values.maxOfOrNull { it.revision } ?: 0) + 1, editor = "android")
        save()
        sendCatalog()
        enforcePolicy()
    }

    @Synchronized fun beginEnrollment(context: Context, name: String) {
        refresh(context)
        if (pairingKey == null || observation == null) {
            android.util.Log.w("TrustedNetworks", "Registration unavailable: pairingKey=${pairingKey != null}, physicalNetwork=${observation != null}, wifiPermission=${hasWifiPermission(context)}")
            _status.value = "Connect to Wi-Fi and allow precise location to recognize the network."
            return
        }
        val trimmed = name.trim().take(80)
        if (trimmed.isEmpty()) return
        val request = UUID.randomUUID().toString()
        pendingRequest = request
        pendingDeadline = System.currentTimeMillis() + 45_000
        _enrolling.value = true
        _status.value = "Checking both devices on this network…"
        if (!send(JSONObject().put("op", "requestEnrollment").put("requestID", request).put("name", trimmed))) {
            pendingRequest = null
            _enrolling.value = false
            _status.value = "Connect to your Mac through Airbridge, then retry."
        }
    }

    fun handle(context: Context, data: JSONObject) {
        when (data.optString("op")) {
            "catalog" -> if (merge(data)) { refresh(context); enforcePolicy() }
            "prepare" -> CoroutineScope(Dispatchers.IO).launch {
                android.util.Log.i("TrustedNetworks", "Received local network registration request")
                val id = data.optString("requestID")
                if (runCatching { UUID.fromString(id) }.isFailure) return@launch
                load(context)
                // An explicit enrollment replaces an older provisional/manual LAN attempt.
                if (WebSocketUtil.isConnecting()) WebSocketUtil.disconnect(context, manual = false, notifyPeer = false)
                synchronized(this@TrustedNetworks) {
                    pendingRequest = id
                    pendingDeadline = System.currentTimeMillis() + 45_000
                    _enrolling.value = true
                    _status.value = "Checking both devices on this network…"
                }
                refresh(context)
                if (WebSocketUtil.isWifiConnected() && isRecognized()) {
                    val known = synchronized(this@TrustedNetworks) {
                        records.values.firstOrNull { !it.deleted && it.androidFingerprint == observation?.fingerprint }
                    }
                    if (known != null) {
                        pendingRequest = null
                        _enrolling.value = false
                        publish()
                        send(JSONObject().put("op", "enrollmentFinished").put("requestID", id).put("networkID", known.id))
                        return@launch
                    }
                }
                val key = pairingKey ?: return@launch
                if (currentObservation() == null) {
                    rejectProof()
                    _status.value = "Open Trusted networks and allow Wi-Fi recognition, then retry."
                    _enrolling.value = false
                    return@launch
                }
                val ips = data.optJSONArray("ips") ?: return@launch
                val port = data.optInt("port")
                if (port !in 1..65535 || ips.length() !in 1..16) return@launch
                val candidates = (0 until ips.length()).map { ips.optString(it) }.filter { TrustedNetwork.ipv4(it) != null }
                if (candidates.isEmpty()) return@launch
                WebSocketUtil.connect(context, candidates.joinToString(","), port, key, manualAttempt = false, enrollmentRequestID = id,
                    onConnectionStatus = { success -> if (!success) rejectProof(id) })
            }
        }
    }

    @Synchronized fun updatePeerNetworks(context: Context, ids: Set<String>) {
        peerNetworkIDs = ids
        refresh(context)
        enforcePolicy()
    }

    @Synchronized private fun enforcePolicy() {
        if (_enrolling.value || isVerifying) return
        val ctx = context ?: return
        val locallyAllowed = isRecognized()
        val common = hasCommonNetwork()
        if (!locallyAllowed || (!common && AirBridgeClient.isRelayActive())) {
            WebSocketUtil.updatePeerLanPolicy(ctx, false)
        } else {
            WebSocketUtil.updatePeerLanPolicy(ctx, true)
            if (!WebSocketUtil.isWifiConnected()) WebSocketUtil.requestAutoReconnect(ctx)
        }
    }

    fun reconcileConnection(context: Context) {
        val previous = currentObservation()?.fingerprint
        refresh(context)
        if (previous != currentObservation()?.fingerprint) {
            AirBridgeClient.networkChanged(context)
            if (WebSocketUtil.isWifiConnected() || (WebSocketUtil.isConnecting() && !isVerifying && !enrolling.value)) {
                WebSocketUtil.disconnect(context, manual = false)
            }
        }
        enforcePolicy()
    }

    @Synchronized fun rejectProof(requestID: String? = pendingRequest) {
        if (pendingRequest != requestID) return
        pendingRequest?.let { send(JSONObject().put("op", "enrollmentFailed").put("requestID", it)) }
        pendingRequest = null
        verification.finish()
        _enrolling.value = false
        _status.value = "Network not authorized. Add the current network with both devices connected locally."
    }

    private fun publish() {
        _networks.value = records.values.filter { !it.deleted }.sortedBy { it.name.lowercase() }
        if (!_enrolling.value) {
            val matched = observation?.let { o -> _networks.value.firstOrNull { it.androidFingerprint == o.fingerprint } }
            _status.value = matched?.let { "Recognized: ${it.name}" } ?: "Unknown network · using Airbridge"
        }
    }
    private fun save() {
        if (storageKey.isNotEmpty()) context?.getSharedPreferences("trusted_networks", Context.MODE_PRIVATE)?.edit()?.putString(storageKey, JSONArray(records.values.map(::encode)).toString())?.apply()
        publish()
    }
    private fun send(data: JSONObject): Boolean {
        val payload = JSONObject().put("type", "trustedNetworks").put("data", data).toString()
        return if (AirBridgeClient.isRelayActive()) AirBridgeClient.sendMessage(payload) else WebSocketUtil.sendMessage(payload)
    }
    private fun encode(entry: TrustedNetwork): JSONObject = JSONObject().put("id", entry.id).put("name", entry.name)
        .put("macFingerprint", entry.macFingerprint).put("androidFingerprint", entry.androidFingerprint)
        .put("subnet", entry.subnet).put("gateway", entry.gateway).put("revision", entry.revision).put("editor", entry.editor).put("deleted", entry.deleted)
    private fun decode(raw: JSONArray): List<TrustedNetwork> {
        require(raw.length() <= 256 && raw.toString().length <= 262_144)
        return (0 until raw.length()).map { index ->
            val o = raw.getJSONObject(index)
            TrustedNetwork(o.getString("id"), o.getString("name"), o.getString("macFingerprint"), o.getString("androidFingerprint"),
                o.getString("subnet"), o.getString("gateway"), o.getLong("revision"), o.getString("editor"), o.getBoolean("deleted")).also { require(it.isValid()) }
        }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
