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
import org.meshtastic.proto.Config
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Plugin owns mesh PLI and explicit points. Never writes radio configuration. */
class RelayPlugin(services: IServiceController) : IPlugin {
    private val pluginContext = services.getService(PluginContextProvider::class.java).pluginContext
    private val map = MapView.getMapView()
    private val host = map.context
    private val ui = services.getService(IHostUIService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private val state = PliState()
    private val session = RadioSession<Snapshot>()
    private val markers = linkedMapOf<String, Marker>()
    private val random = SecureRandom()
    @Volatile private var active = false
    private var bound = false
    private var registered = false
    private var service: IMeshService? = null
    private var interval = 0
    private var nextSend = 0L
    private var ackWait = 120
    private var lastSend = -10_000L
    private var status = "Connecting to Meshtastic…"
    private var pane: Pane? = null
    private var body: LinearLayout? = null
    private var statusText: TextView? = null
    private var peerText: TextView? = null
    private var pointText: TextView? = null
    private var chatText: TextView? = null
    private var channelButtons: LinearLayout? = null
    private var lastRefresh = -5_000L
    private val points = PointSharing(map,
        { if (active && session.selected != null) session.snapshot?.node else null },
        { bytes, done -> transmit(bytes, DataPacket.ID_BROADCAST, null, done) },
        { render() })
    private val chat = ChatSharing(map,
        { if (active && session.selected != null) session.snapshot?.node else null },
        { bytes, done -> transmit(bytes, DataPacket.ID_BROADCAST, null, done) }, points::chatContact, { render() })
    private val button = ToolbarItem.Builder("Hardline Relay", MarshalManager.marshal(
        pluginContext.getDrawable(R.drawable.ic_hardline), android.graphics.drawable.Drawable::class.java,
        gov.tak.api.commons.graphics.Bitmap::class.java))
        .setIdentifier("com.hardlinelabs.relay.atak.plugin")
        .setListener(object : ToolbarItemAdapter() {
            override fun onClick(item: ToolbarItem) { showPane() }
        }).build()

    private data class Snapshot(val node: String, val channels: List<ChannelSettings>, val hops: Int, val lora: Config.LoRaConfig)
    private fun read(s: IMeshService): Snapshot {
        @Suppress("DEPRECATION")
        val version = host.packageManager.getPackageInfo("com.geeksville.mesh", 0).versionCode
        check(version == 29320069) { "Meshtastic version must be 2.7.13." }
        check(s.connectionState() == "Connected") { "Radio disconnected. Reconnect in Meshtastic." }
        check(s.myNodeInfo?.firmwareVersion == "2.7.15.567b8ea") { "Radio firmware must be 2.7.15.567b8ea." }
        val config = LocalConfig.ADAPTER.decode(s.config)
        check(config.lora?.hop_limit == 7) { "Expected 7 hops. Inspect Meshtastic; no settings changed." }
        return Snapshot(s.myId, ChannelSet.ADAPTER.decode(s.channelSet).settings, 7, requireNotNull(config.lora))
    }
    private fun privateChannel(c: ChannelSettings) = c.psk.size == 32 && c.name.isNotBlank() &&
        !c.name.equals("admin", true) && !c.uplink_enabled && !c.downlink_enabled

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!active) return
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
            if (!active || session.selected == null) return
            try {
                intent.setExtrasClassLoader(DataPacket::class.java.classLoader)
                @Suppress("DEPRECATION") val packet = intent.getParcelableExtra<DataPacket>("com.geeksville.mesh.Payload") ?: return
                if (packet.dataType != PliWire.PORT || packet.channel != session.selected || packet.viaMqtt) return
                val from = packet.from?.lowercase(Locale.ROOT) ?: return
                if (!from.matches(Regex("![0-9a-fA-F]{8}")) || from == session.snapshot?.node) return
                val bytes = packet.bytes?.toByteArray() ?: return
                val now = SystemClock.elapsedRealtime()
                if (ChatWire.isChat(bytes)) { chat.receive(from, bytes, now); return }
                if (PointWire.isPointMessage(bytes)) {
                    val previous = points.last.status
                    points.receive(from, bytes, now)
                    if (previous != PointSendState.Status.RECEIVED && points.last.status == PointSendState.Status.RECEIVED) lastPointProof = now
                    render(); return
                }
                val message = PliWire.decode(bytes)
                when (message) {
                    is PliWire.Receipt -> if (state.receipt(message.id, from, now)) status = "Peer $from confirmed processing PLI ${shortId(message.id)}."
                    is PliWire.Position -> {
                        if (state.accept(from, message.pli, now, System.currentTimeMillis())) {
                            // Publish marker before issuing the application receipt.
                            updateMarkers(now)
                            // Keep receipts on the same PSK channel as PLI. Direct-message
                            // radio routing can use a different encryption/channel path.
                            receipts.enqueue(from, message.pli, now, random.nextInt(2501).toLong() + 500)
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
        points.start()
        chat.start()
        indicator = MeshIndicator(map) { showPane() }.also { it.start() }
        connect(); handler.post(tick)
    }
    override fun onStop() {
        active = false; session.reset(); interval = 0
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        if (registered) host.unregisterReceiver(receiver)
        registered = false; disconnect(); clearPeers()
        points.stop()
        chat.stop()
        indicator?.stop(); indicator = null
        pane?.let { ui?.closePane(it) }; ui?.removeToolbarItem(button)
        pane = null; body = null
        statusText = null; peerText = null; pointText = null; chatText = null; channelButtons = null
    }
    private fun clearPeers() {
        markers.values.forEach { map.rootGroup.removeItem(it) }
        markers.clear(); state.clear(); receipts.clear(); lastPointProof = -1L
        points.resetContacts()
        chat.reset()
    }
    private fun reset(message: String) {
        session.reset(); interval = 0; clearPeers()
        status = message; rebuildChannels(); render()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (!session.busy && now - lastRefresh >= 5000) { lastRefresh = now; refresh() }
            state.waiting(now, ackWait * 1000L)
            if (interval > 0 && now >= nextSend && !session.busy) sendPli()
            if (session.selected != null && !session.busy) receipts.poll(now)?.let { transmit(PliWire.ack(it), DataPacket.ID_BROADCAST, null) }
            state.expire(now); updateMarkers(now); render()
            handler.postDelayed(this, 1000)
        }
    }
    private fun refresh() {
        val s = service ?: return
        val epoch = session.beginRefresh() ?: return
        worker.execute {
            val result = runCatching { read(s) }
            val catalog = runCatching { ProfileCatalog.read(host) }
            handler.post {
                if (!active || !session.finishRefresh(epoch)) return@post
                result.onSuccess { current ->
                    val newProfiles = catalog.getOrNull()?.entries.orEmpty()
                    profileSwitching = catalog.getOrNull()?.switching == true
                    catalogIssue = if (catalog.isFailure) "Open Hardline Relay to reconnect saved channels. Sending paused." else null
                    val profilesChanged = profiles != newProfiles
                    profiles = newProfiles
                    val old = session.snapshot
                    if (old != null && old != current) reset("Radio/channel changed. Select channel again; automatic sending stopped.")
                    val changed = session.observe(current)
                    if (changed || profilesChanged) {
                        status = "Radio connected (${current.node}), 7 hops. Select a private channel."
                        rebuildChannels()
                    }
                    if (profileSwitching || catalogIssue != null) {
                        session.select(null); interval = 0; clearPeers()
                        status = catalogIssue ?: "Radio activation in progress or unverified. Open Relay for status."
                        rebuildChannels()
                    } else profiles.firstOrNull { it.activation.isNotEmpty() && it.activation != lastActivation && matchesActive(it) }?.let { entry ->
                        lastActivation = entry.activation; chooseChannel(entry.index)
                    }
                }.onFailure { reset(it.message ?: "Radio unavailable.") }
                render()
            }
        }
    }
    private fun positionFix(): Pair<GeoPoint, android.location.Location> {
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
        return point to fix
    }
    private fun sendPli() {
        if (session.selected == null) { status = "Select a private channel first."; return }
        val now = SystemClock.elapsedRealtime()
        if (session.busy || now - lastSend < 5000) return
        if (state.waiting(now, ackWait * 1000L) != null) {
            status = "Previous PLI is awaiting a receipt. Latest position will be sent when the wait ends."
            render(); return
        }
        try {
            val (point, fix) = positionFix()
            var id = random.nextLong(); while (id == 0L) id = random.nextLong()
            val name = map.deviceCallsign.take(20).ifBlank { "Relay" }
            val pli = Pli(id, fix.time, point.latitude, point.longitude, interval, name)
            lastSend = now
            nextSend = now + interval * 1000L
            positionIssue = null
            transmit(PliWire.encode(pli), DataPacket.ID_BROADCAST, id)
        } catch (e: Exception) { positionIssue = e.message ?: "No fresh GPS fix; PLI paused."; status = positionIssue!!; render() }
    }
    private fun transmit(bytes: ByteArray, destination: String, pliId: Long?, done: (String?) -> Unit = {}) {
        val s = service
        val before = session.snapshot
        val index = session.selected
        if (!active || s == null || before == null || index == null) {
            done("Radio/channel unavailable; point not submitted."); return
        }
        val expected = before.channels.getOrNull(index)
        if (expected == null) { done("Channel unavailable; point not submitted."); return }
        val epoch = session.beginTransmit()
        if (epoch == null) {
            status = "Transmit queue full; packet not submitted."; done(status); return
        }
        // Register before send: a fast receipt may arrive before the worker completion callback.
        if (pliId != null) state.sent(pliId, SystemClock.elapsedRealtime())
        worker.execute {
            val result = runCatching {
                val current = read(s)
                check(!ProfileCatalog.read(host).switching) { "Radio activation is pending verification in Relay. Sending paused." }
                check(current == before && privateChannel(expected)) { "Radio/channel changed; packet not submitted." }
                check(active && epoch == session.generation) { "Session changed; packet not submitted." }
                s.send(DataPacket(to = destination, bytes = bytes.toByteString(), dataType = PliWire.PORT,
                    channel = index, hopLimit = current.hops, wantAck = false))
            }
            handler.post {
                if (!active || !session.finishTransmit(epoch)) return@post
                result.onSuccess {
                    if (pliId != null && state.pending[pliId]?.receipts?.isEmpty() == true)
                        status = "PLI ${shortId(pliId)} submitted; awaiting plugin receipt."
                    done(null)
                }.onFailure {
                    if (pliId != null) state.failed(pliId, it.message ?: "Radio submission failed.")
                    status = it.message ?: "Packet submission failed."; interval = 0; done(status)
                }
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
        points.updateContacts(state.peers, markers, now)
    }
    private val ink = android.graphics.Color.rgb(237, 242, 243)
    private val muted = android.graphics.Color.rgb(153, 171, 179)
    private val accent = android.graphics.Color.rgb(93, 218, 196)
    private var healthText: TextView? = null
    private var gpsText: TextView? = null
    private var retryButton: Button? = null
    private var myPliText: TextView? = null
    private var diagnostics: TextView? = null
    private var intervalSpinner: Spinner? = null
    private var syncingControls = false
    private var positionIssue: String? = null
    private var profiles = emptyList<ProfileCatalog.Entry>()
    private var profileSwitching = false
    private var lastActivation = ""
    private val receipts = ReceiptSchedule()
    private var indicator: MeshIndicator? = null
    private var lastPointProof = -1L
    private var catalogIssue: String? = null
    private fun dp(n: Int) = (host.resources.displayMetrics.density * n).toInt()
    private fun column() = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private fun text(parent: LinearLayout, value: String, size: Float = 15f, color: Int = ink) = TextView(host).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false; setPadding(0, dp(2), 0, dp(2)); parent.addView(this)
    }
    private fun card(parent: LinearLayout) = column().apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(25, 35, 42)); cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(10), dp(6), dp(10), dp(6))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    private fun spinner(parent: LinearLayout, labels: List<String>, selected: Int, choose: (Int) -> Unit): Spinner {
        val widget = Spinner(host).apply { setBackgroundColor(android.graphics.Color.rgb(44,67,73)) }
        val adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                super.getView(position, convertView, parent).apply { (this as TextView).setTextColor(ink); text = "${getItem(position)} ▾"; isSingleLine = false; ellipsize = null; textSize = 15f; minHeight = dp(48); setPadding(dp(10), dp(10), dp(10), dp(10)) }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                super.getDropDownView(position, convertView, parent).apply {
                    (this as TextView).setTextColor(ink); setBackgroundColor(android.graphics.Color.rgb(25,35,42))
                    isSingleLine = false; ellipsize = null; textSize = 15f; minHeight = dp(48)
                    setPadding(dp(12), dp(16), dp(12), dp(16))
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        widget.adapter = adapter
        widget.setSelection(selected, false)
        var lastChoice = selected
        widget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != lastChoice) { lastChoice = position; if (!syncingControls) choose(position) }
            }
        }
        parent.addView(widget, LinearLayout.LayoutParams(-1, -2))
        return widget
    }
    private fun showPane() {
        if (pane == null) {
            body = column().apply { setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(android.graphics.Color.rgb(13,21,27)) }
            val layout = body!!
            text(layout, "HARDLINE  /  RELAY", 18f, accent).typeface = android.graphics.Typeface.DEFAULT_BOLD
            val control = card(layout)
            val choices = column()
            control.addView(choices)
            val channels = column(); choices.addView(channels, LinearLayout.LayoutParams(-1, -2))
            val reporting = column(); choices.addView(reporting, LinearLayout.LayoutParams(-1, -2))
            text(channels, "CHANNEL", 11f, muted)
            channelButtons = column(); channels.addView(channelButtons)
            text(reporting, "REPORTING", 11f, muted)
            intervalSpinner = spinner(reporting, listOf("Off / Manual", "10 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"),
                PliWire.intervals.indexOf(interval)) { selected ->
                val seconds = PliWire.intervals[selected]
                if (seconds == interval) return@spinner
                if (seconds == 0 || session.selected != null) {
                    interval = seconds; nextSend = SystemClock.elapsedRealtime() + seconds * 1000L + random.nextInt(1000)
                    status = if (seconds == 0) "Automatic PLI paused." else "Reporting every ${seconds}s."
                } else status = "Select a connected channel first."
                render()
            }
            text(reporting, "PLI ACK WAIT / RETRY", 11f, muted)
            val waits = listOf(30, 60, 120, 180, 300)
            spinner(reporting, listOf("30 seconds", "1 minute", "2 minutes", "3 minutes", "5 minutes"), waits.indexOf(ackWait)) {
                ackWait = waits[it]; render()
            }
            text(reporting, "One PLI at a time. Overdue updates wait for a peer receipt or this deadline, then send the newest position.", 12f, muted)
            val actions = LinearLayout(host); control.addView(actions)
            addButton(actions, "Send PLI now") { sendPli() }
            addButton(actions, "Pause PLI") { interval = 0; status = "Automatic PLI paused."; render() }
            addButton(actions, "Channels") { openProfiles() }
            val summary = card(layout)
            text(summary, "MESH STATUS", 11f, accent)
            healthText = text(summary, "", 15f)
            statusText = text(summary, "", 13f, muted)
            gpsText = text(summary, "", 13f, muted)
            myPliText = text(summary, "", 13f)
            pointText = text(summary, "", 13f)
            chatText = text(summary, "", 13f)
            text(summary, "Chat from ATAK's normal contact conversation · 160 UTF-8 bytes per message · Private Relay channel only", 12f, muted)
            retryButton = addButton(summary, "Retry last point") { points.retry() }
            peerText = text(summary, "", 13f)
            diagnostics = text(summary, "", 13f, muted)
            addButton(summary, "Reconnect / refresh") { if (service == null) { disconnect(); connect() }; refresh() }
            pane = PaneBuilder(ScrollView(host).apply { isFillViewport = true; setBackgroundColor(android.graphics.Color.rgb(13,21,27)); addView(layout) })
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.65).setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 1.0).build()
            rebuildChannels()
        }
        render(); if (!ui.isPaneVisible(pane)) ui.showPane(pane, null)
    }
    private fun addButton(layout: LinearLayout, label: String, action: () -> Unit): Button {
        val widget = Button(host).apply {
            text = label; isAllCaps = false; textSize = 12f; minHeight = dp(36); minimumHeight = dp(36); setPadding(dp(4), dp(4), dp(4), dp(4)); setTextColor(ink)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(44,67,73))
            setOnClickListener { action() }
        }
        layout.addView(widget, if (layout.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        return widget
    }
    private fun chooseChannel(index: Int) {
        if (profileSwitching || catalogIssue != null) { status = catalogIssue ?: "Finish or verify channel activation in Relay first."; rebuildChannels(); render(); return }
        session.select(index); interval = 0; clearPeers(); positionIssue = null
        status = "Channel selected · Automatic PLI off."
        rebuildChannels(); render()
    }
    private fun openProfiles(id: String? = null) {
        try {
            session.select(null); interval = 0; clearPeers()
            status = "Channel activation in Relay · Sending paused."
            rebuildChannels(); render()
            ProfileCatalog.open(host, id)
        } catch (_: Exception) { status = "Install Hardline Relay to manage channels."; render() }
    }
    private fun matchesActive(entry: ProfileCatalog.Entry): Boolean {
        val current = session.snapshot ?: return false
        val channel = current.channels.getOrNull(entry.index) ?: return false
        return entry.activation.isNotEmpty() &&
            current.node == "!" + java.lang.Integer.toUnsignedString(entry.node, 16).padStart(8, '0') &&
            current.lora.channel_num == entry.slot && current.lora.region == Config.LoRaConfig.RegionCode.US &&
            current.lora.use_preset && current.lora.modem_preset == Config.LoRaConfig.ModemPreset.LONG_FAST &&
            current.lora.override_frequency == 0f && current.lora.frequency_offset == 0f &&
            ProfileCatalog.fingerprint(channel.encode()) == entry.fingerprint
    }

    private fun rebuildChannels() {
        val parent = channelButtons ?: return
        parent.removeAllViews()
        val installed = session.snapshot?.channels?.mapIndexedNotNull { i, c ->
            if (i > 0 && privateChannel(c)) i to c.name else null
        }.orEmpty()
        val saved = profiles.filter { p -> installed.none { it.second.equals(p.name, true) } }
        val labels = listOf("Select a channel") + installed.map { it.second } + saved.map { "${it.name} · ${if (it.locked) "Locked" else "Saved"}" }
        val selected = installed.indexOfFirst { it.first == session.selected }.let { if (it < 0) 0 else it + 1 }
        spinner(parent, labels, selected) { pos ->
            if (pos == 0) { session.select(null); interval = 0; clearPeers(); render() }
            else if (pos <= installed.size) {
                val channel = installed[pos - 1]
                val profile = profiles.firstOrNull { it.name.equals(channel.second, true) }
                // Installed keys do not imply the radio is on this profile's RF settings.
                if (profile != null && !matchesActive(profile)) openProfiles(profile.id)
                else chooseChannel(channel.first)
            }
            else openProfiles(saved[pos - installed.size - 1].id)
        }
    }
    private fun age(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000)
        return if (seconds < 60) "${seconds}s" else if (seconds < 3600) "${seconds / 60}m" else "${seconds / 3600}h"
    }
    private fun render() {
        val now = SystemClock.elapsedRealtime()
        points.last.tick(now)
        chat.tick(now); chatText?.text = chat.status
        val p = points.last
        val gps = runCatching { positionFix().second }
        positionIssue = gps.exceptionOrNull()?.message
        gpsText?.text = gps.getOrNull()?.let { "GPS · Fix ${age((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000)} old" } ?: "GPS unavailable · No position sent"
        retryButton?.visibility = if (p.status in listOf(PointSendState.Status.UNCONFIRMED, PointSendState.Status.FAILED)) android.view.View.VISIBLE else android.view.View.GONE
        val label = when (p.status) {
            PointSendState.Status.NONE -> "No point sent"
            PointSendState.Status.SUBMITTING -> "Sending"
            PointSendState.Status.AWAITING -> "Awaiting receipt"
            PointSendState.Status.RECEIVED -> "Received"
            PointSendState.Status.UNCONFIRMED -> "Unconfirmed"
            PointSendState.Status.FAILED -> "Send failed"
        }
        pointText?.text = "Last point · $label" + (p.point?.let { "\n${it.name} → ${p.recipientName}" } ?: "") + "\n" + roundTripText(p.roundTrips)
        pointText?.setTextColor(when (p.status) {
            PointSendState.Status.RECEIVED -> accent
            PointSendState.Status.FAILED -> android.graphics.Color.rgb(255,130,130)
            PointSendState.Status.AWAITING, PointSendState.Status.UNCONFIRMED -> android.graphics.Color.rgb(255,195,100)
            else -> ink
        })
        val recent = state.peers.values.any { !it.stale(now) } ||
            (state.lastConfirmation >= 0 && now - state.lastConfirmation < maxOf(60_000L, interval * 3000L)) ||
            (lastPointProof >= 0 && now - lastPointProof < 60_000L) ||
            (chat.lastProof >= 0 && now - chat.lastProof < 60_000L)
        val health = if (profileSwitching || catalogIssue != null) MeshHealth.State.WAITING else MeshHealth.assess(session.snapshot != null && service != null, session.selected != null,
            recent, interval > 0 && positionIssue != null)
        indicator?.update(health)
        healthText?.text = when (health) {
            MeshHealth.State.FAULT -> "Radio unavailable"
            MeshHealth.State.INACTIVE -> "Choose a channel"
            MeshHealth.State.WAITING -> if (catalogIssue != null) "Open Relay to connect channels" else if (profileSwitching) "Channel activation needs verification" else if (positionIssue != null && interval > 0) "Position reporting needs attention" else "Waiting for mesh contact"
            MeshHealth.State.CONFIRMED -> "Recent mesh contact"
        }
        val name = session.selected?.let { session.snapshot?.channels?.getOrNull(it)?.name } ?: "None"
        statusText?.text = "$name · ${if (interval == 0) "PLI off" else "PLI every ${interval}s"}"
        val sent = state.latest
        val waiting = state.waiting(now, ackWait * 1000L)
        myPliText?.text = buildString {
            append("My PLI · ")
            append(if (sent == null) "No report this session" else pliAttempt(sent, now))
            if (waiting != null) {
                append("\nACK deadline in ${age(ackWait * 1000L - (now - waiting.at))}")
                if (interval > 0 && now >= nextSend) append(" · Next update held")
            }
            state.pending.values.toList().takeLast(4).filter { it.id != sent?.id }.reversed().forEach {
                append("\nPrevious ${shortId(it.id)} · ${pliAttempt(it, now)}")
            }
            append("\nLast confirmed · ").append(if (state.lastConfirmation >= 0) "${age(now - state.lastConfirmation)} ago" else "None this session")
            append("\n").append(roundTripText(state.roundTrips))
            append("\n${state.unanswered} unanswered in retained attempts · Silence does not prove loss")
        }
        peerText?.text = buildString {
            val overdue = state.peers.values.count { it.stale(now) }
            append("Contacts · ${state.peers.size - overdue} reporting · $overdue overdue")
            state.peers.values.sortedByDescending { it.stale(now) }.forEach { peer ->
                append("\n${peer.pli.callsign} · ${if (peer.stale(now)) "Overdue · " else ""}Position ${age(System.currentTimeMillis() - peer.pli.fixTime)} old")
            }
        }
        val problem = health in listOf(MeshHealth.State.FAULT, MeshHealth.State.WAITING) ||
            p.status in listOf(PointSendState.Status.FAILED, PointSendState.Status.UNCONFIRMED) || positionIssue != null || sent?.failure != null || catalogIssue != null || profileSwitching
        diagnostics?.visibility = if (problem) android.view.View.VISIBLE else android.view.View.GONE
        diagnostics?.text = buildString {
            if (positionIssue != null) append(positionIssue).append("\n")
            append(status)
            sent?.failure?.let { append("\n").append(it) }
            if (p.status in listOf(PointSendState.Status.FAILED, PointSendState.Status.UNCONFIRMED)) append("\n").append(p.detail)
            if (health == MeshHealth.State.WAITING && !recent && !profileSwitching && catalogIssue == null) append("\nNo recent peer evidence. Check teammates are on the same channel/frequency and keep radios clear of obstructions.")
            session.snapshot?.let { append("\nRadio ${it.node} · ${it.lora.modem_preset} · Slot ${it.lora.channel_num} · ${it.hops} hops") }
            catalogIssue?.let { append("\n").append(it) }
        }
        syncingControls = true
        val mode = PliWire.intervals.indexOf(interval)
        if (intervalSpinner?.selectedItemPosition != mode) intervalSpinner?.setSelection(mode, false)
        syncingControls = false
    }
    private fun pliAttempt(sent: PliState.Sent, now: Long) = when {
        sent.receipts.isNotEmpty() -> "Confirmed by ${sent.receipts.size} · sent ${age(now - sent.at)} ago"
        sent.failure != null -> "Send failed · ${age(now - sent.at)} ago"
        sent.deadlineReached -> "Unconfirmed · acknowledgment deadline reached"
        else -> "Waiting ${age(now - sent.at)} for peer receipt"
    }
    private fun roundTripText(stats: RoundTrips): String {
        fun seconds(ms: Long?) = ms?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "—"
        return "Avg round trip ${seconds(stats.average)} · ${stats.count} samples\nLatest ${seconds(stats.latest)} · Longest ${seconds(stats.longest)}"
    }
}
