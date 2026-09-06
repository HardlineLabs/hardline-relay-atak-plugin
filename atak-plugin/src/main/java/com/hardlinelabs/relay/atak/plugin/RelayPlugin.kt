package com.hardlinelabs.relay.atak.plugin

import android.content.*
import android.location.LocationManager
import android.os.*
import android.widget.*
import com.atak.plugins.impl.PluginContextProvider
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.coremap.maps.coords.GeoPoint
import com.hardlinelabs.relay.atak.*
import gov.tak.api.plugin.*
import gov.tak.api.ui.*
import gov.tak.platform.marshal.MarshalManager
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.LocalConfig
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Plugin owns PLI only. Never writes radio configuration or dispatches TAK-server CoT. */
class RelayPlugin(services: IServiceController) : IPlugin {
    private val pluginContext = services.getService(PluginContextProvider::class.java).pluginContext
    private val map = MapView.getMapView()
    private val host = map.context
    private val ui = services.getService(IHostUIService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private val state = PliState()
    private val markers = linkedMapOf<String, Marker>()
    private val random = SecureRandom()
    @Volatile private var active = false
    private var bound = false
    private var registered = false
    private var service: IMeshService? = null
    private var snapshot: Snapshot? = null
    private var selected: Int? = null
    private var interval = 0
    private var nextSend = 0L
    private var lastSend = -10_000L
    private var busy = false
    private var queuedTransmissions = 0
    @Volatile private var generation = 0
    private var status = "Connecting to Meshtastic…"
    private var pane: Pane? = null
    private var body: LinearLayout? = null
    private var statusText: TextView? = null
    private var peerText: TextView? = null
    private var channelButtons: LinearLayout? = null
    private var lastRefresh = -5_000L
    private val button = ToolbarItem.Builder("Hardline Relay", MarshalManager.marshal(
        pluginContext.getDrawable(android.R.drawable.ic_menu_share), android.graphics.drawable.Drawable::class.java,
        gov.tak.api.commons.graphics.Bitmap::class.java))
        .setIdentifier("com.hardlinelabs.relay.atak.plugin")
        .setListener(object : ToolbarItemAdapter() {
            override fun onClick(item: ToolbarItem) { showPane() }
        }).build()

    private data class Snapshot(val node: String, val channels: List<ChannelSettings>, val hops: Int)
    private fun read(s: IMeshService): Snapshot {
        @Suppress("DEPRECATION")
        val version = host.packageManager.getPackageInfo("com.geeksville.mesh", 0).versionCode
        check(version == 29320069) { "Meshtastic version must be 2.7.13." }
        check(s.connectionState() == "Connected") { "Radio disconnected. Reconnect in Meshtastic." }
        check(s.myNodeInfo?.firmwareVersion == "2.7.15.567b8ea") { "Radio firmware must be 2.7.15.567b8ea." }
        val config = LocalConfig.ADAPTER.decode(s.config)
        check(config.lora?.hop_limit == 7) { "Expected 7 hops. Inspect Meshtastic; no settings changed." }
        return Snapshot(s.myId, ChannelSet.ADAPTER.decode(s.channelSet).settings, 7)
    }
    private fun privateChannel(c: ChannelSettings) = c.psk.size == 32 && c.name.isNotBlank() &&
        !c.name.equals("admin", true) && !c.uplink_enabled && !c.downlink_enabled

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMeshService.Stub.asInterface(binder); refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; reset("Meshtastic service disconnected; automatic sending stopped.") }
        override fun onBindingDied(name: ComponentName) { disconnect(); reset("Binding lost. Tap Reconnect.") }
        override fun onNullBinding(name: ComponentName) { disconnect(); reset("Meshtastic binding unavailable.") }
    }
    private fun connect() {
        if (bound) return
        try {
            bound = host.bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"), connection, Context.BIND_AUTO_CREATE)
            if (!bound) status = "Cannot bind Meshtastic. Open it and reconnect."
        } catch (_: Exception) { status = "Meshtastic binding failed. Check installed version and permissions." }
    }
    private fun disconnect() { if (bound) host.unbindService(connection); bound = false; service = null }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active || selected == null) return
            try {
                intent.setExtrasClassLoader(DataPacket::class.java.classLoader)
                @Suppress("DEPRECATION") val packet = intent.getParcelableExtra<DataPacket>("com.geeksville.mesh.Payload") ?: return
                if (packet.dataType != PliWire.PORT || packet.channel != selected || packet.viaMqtt) return
                val from = packet.from ?: return
                if (!from.matches(Regex("![0-9a-fA-F]{8}")) || from == snapshot?.node) return
                val message = PliWire.decode(packet.bytes?.toByteArray() ?: return)
                val now = SystemClock.elapsedRealtime()
                when (message) {
                    is PliWire.Receipt -> if (state.receipt(message.id, from, now)) status = "Peer $from confirmed processing PLI ${shortId(message.id)}."
                    is PliWire.Position -> {
                        if (state.accept(from, message.pli, now, System.currentTimeMillis())) {
                            // Publish marker before issuing the application receipt.
                            updateMarkers(now)
                            // Keep receipts on the same PSK channel as PLI. Direct-message
                            // radio routing can use a different encryption/channel path.
                            transmit(PliWire.ack(message.pli.id), DataPacket.ID_BROADCAST, null)
                        }
                    }
                }
                render()
            } catch (_: Exception) { /* Reject malformed/unrelated input without payload logging. */ }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 branch; exported required for Meshtastic IPC.
    override fun onStart() {
        if (active) return
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        active = true
        ui?.addToolbarItem(button)
        val filter = IntentFilter("com.geeksville.mesh.RECEIVED.PRIVATE_APP")
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") host.registerReceiver(receiver, filter)
        registered = true
        connect(); handler.post(tick)
    }
    override fun onStop() {
        active = false; generation++; interval = 0
        worker.shutdownNow(); busy = false; queuedTransmissions = 0
        handler.removeCallbacksAndMessages(null)
        if (registered) host.unregisterReceiver(receiver)
        registered = false; disconnect(); clearPeers()
        pane?.let { ui?.closePane(it) }; ui?.removeToolbarItem(button)
        pane = null; body = null; snapshot = null; selected = null
    }
    private fun clearPeers() {
        markers.values.forEach { map.rootGroup.removeItem(it) }
        markers.clear(); state.clear()
    }
    private fun reset(message: String) {
        generation++; interval = 0; selected = null; snapshot = null; clearPeers()
        status = message; rebuildChannels(); render()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (!busy && now - lastRefresh >= 5000) { lastRefresh = now; refresh() }
            if (interval > 0 && now >= nextSend && !busy) { nextSend = now + interval * 1000L; sendPli() }
            state.expire(now); updateMarkers(now); render()
            handler.postDelayed(this, 1000)
        }
    }
    private fun refresh() {
        val s = service ?: return
        if (busy) return
        busy = true
        val epoch = generation
        worker.execute {
            val result = runCatching { read(s) }
            handler.post {
                busy = false
                if (!active || epoch != generation) return@post
                result.onSuccess { current ->
                    val old = snapshot
                    if (old != null && old != current) reset("Radio/channel changed. Select channel again; automatic sending stopped.")
                    snapshot = current
                    if (old == null) { status = "Radio connected (${current.node}), 7 hops. Select a private channel."; rebuildChannels() }
                }.onFailure { reset(it.message ?: "Radio unavailable.") }
                render()
            }
        }
    }
    private fun sendPli() {
        if (selected == null) { status = "Select a private channel first."; return }
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastSend < 5000) return
        try {
            val self = map.selfMarker ?: error("ATAK self position unavailable.")
            val point = self.point
            val lm = host.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            check(host.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "ATAK needs precise location permission. Enable it in Android settings."
            }
            val fix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.takeIf { it.elapsedRealtimeNanos > 0 && (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) in 0..120_000_000_000L }
                ?: error("No GPS fix newer than 120 seconds. Move phones near a window/outdoors.")
            val distance = FloatArray(1)
            android.location.Location.distanceBetween(point.latitude, point.longitude, fix.latitude, fix.longitude, distance)
            check(distance[0] <= 100) { "ATAK self position differs from current phone fix. Wait for ATAK location." }
            var id = random.nextLong(); while (id == 0L) id = random.nextLong()
            val name = map.deviceCallsign.take(20).ifBlank { "Relay" }
            val pli = Pli(id, fix.time, point.latitude, point.longitude, interval, name)
            lastSend = now
            transmit(PliWire.encode(pli), DataPacket.ID_BROADCAST, id)
        } catch (e: Exception) { status = e.message ?: "Location unavailable; no PLI sent."; render() }
    }
    private fun transmit(bytes: ByteArray, destination: String, pliId: Long?) {
        val s = service ?: return
        val before = snapshot ?: return
        val index = selected ?: return
        val expected = before.channels.getOrNull(index) ?: return
        val epoch = generation
        if (queuedTransmissions >= 4) { status = "Transmit queue full; packet not submitted."; return }
        queuedTransmissions++
        worker.execute {
            val result = runCatching {
                val current = read(s)
                check(current == before && privateChannel(expected)) { "Radio/channel changed; packet not submitted." }
                check(active && epoch == generation) { "Session changed; packet not submitted." }
                s.send(DataPacket(to = destination, bytes = bytes.toByteString(), dataType = PliWire.PORT,
                    channel = index, hopLimit = current.hops, wantAck = false))
            }
            handler.post {
                queuedTransmissions = (queuedTransmissions - 1).coerceAtLeast(0)
                if (!active || epoch != generation) return@post
                result.onSuccess {
                    if (pliId != null) { state.sent(pliId, SystemClock.elapsedRealtime()); status = "PLI ${shortId(pliId)} submitted; awaiting plugin receipt." }
                }.onFailure { status = it.message ?: "Packet submission failed."; interval = 0 }
                render()
            }
        }
    }
    private fun shortId(id: Long) = java.lang.Long.toHexString(id).takeLast(8)
    private fun updateMarkers(now: Long) {
        markers.keys.filter { it !in state.peers }.toList().forEach { markers.remove(it)?.let { m -> map.rootGroup.removeItem(m) } }
        state.peers.forEach { (node, peer) ->
            val p = peer.pli
            val marker = markers.getOrPut(node) {
                Marker("hardline-relay:$node").apply {
                    type = "a-f-G-U-C"
                    setMetaBoolean("nevercot", true); setMetaBoolean("movable", false)
                    setMetaBoolean("removable", false)
                    map.rootGroup.addItem(this)
                }
            }
            marker.point = GeoPoint(p.lat, p.lon)
            marker.title = "${p.callsign} [mesh ${if (peer.stale(now)) "STALE" else "recent"} ${peer.age(now)}s]"
            marker.setMetaString("callsign", marker.title)
            marker.setMetaString("remarks", "Mesh-only last-known position. Fix ${Date(p.fixTime)}; received ${Date(peer.receivedWall)}. Not proof of current location.")
        }
    }
    private fun showPane() {
        if (pane == null) {
            body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
            val layout = body!!
            layout.addView(TextView(host).apply { text = "Hardline Relay — PLI development test"; textSize = 18f })
            statusText = TextView(host); layout.addView(statusText)
            addButton(layout, "Reconnect / refresh") { if (service == null) { disconnect(); connect() }; refresh() }
            channelButtons = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }; layout.addView(channelButtons)
            addButton(layout, "Send PLI now") { sendPli() }
            val modes = LinearLayout(host)
            listOf("Off" to 0, "Every 10s" to 10, "Every 30s" to 30).forEach { (label, seconds) ->
                addButton(modes, label) {
                    if (seconds == 0 || selected != null) { interval = seconds; nextSend = SystemClock.elapsedRealtime() + seconds * 1000L }
                    else status = "Select a private channel first."
                    render()
                }
            }
            layout.addView(modes)
            peerText = TextView(host); layout.addView(peerText)
            val scroll = ScrollView(host).apply { addView(layout) }
            pane = PaneBuilder(scroll).setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.65).setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.8).build()
            rebuildChannels()
        }
        render(); if (!ui.isPaneVisible(pane)) ui.showPane(pane, null)
    }
    private fun addButton(layout: LinearLayout, label: String, action: () -> Unit) {
        layout.addView(Button(host).apply { text = label; setOnClickListener { action() } })
    }
    private fun rebuildChannels() {
        channelButtons?.removeAllViews()
        snapshot?.channels?.forEachIndexed { index, c ->
            if (index > 0 && privateChannel(c)) channelButtons?.let { layout ->
                addButton(layout, "Use ${c.name}") {
                    generation++; interval = 0; clearPeers(); selected = index
                    status = "Selected ${c.name}. Receiving PLI; automatic sending Off."; render()
                }
            }
        }
    }
    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val name = selected?.let { snapshot?.channels?.getOrNull(it)?.name } ?: "none"
        statusText?.text = "$status\nChannel: $name | Auto: ${if (interval == 0) "Off" else "${interval}s"}\nTAK server connections unchanged."
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        peerText?.text = buildString {
            append("\nPeer PLI (receipt age is local; fix time is sender clock)\n")
            if (state.peers.isEmpty()) append("No peer PLI received this session.\n")
            state.peers.forEach { (node, peer) ->
                append("${peer.pli.callsign} $node: ${if (peer.stale(now)) "STALE / last known" else "RECENT"}\n")
                append("Received ${time.format(Date(peer.receivedWall))} (${peer.age(now)}s ago); fix ${time.format(Date(peer.pli.fixTime))}; sender ${if (peer.pli.interval == 0) "manual" else "${peer.pli.interval}s"}\n")
            }
            append("\nMy recent PLI receipts (not a radio ACK):\n")
            state.pending.values.toList().takeLast(5).reversed().forEach {
                val age = (now - it.at) / 1000
                append("${shortId(it.id)}: ${if (it.receipts.isNotEmpty()) "CONFIRMED by ${it.receipts.joinToString()}" else if (age >= 60) "UNCONFIRMED (timeout)" else "awaiting receipt"} (${age}s ago)\n")
            }
        }
    }
}
