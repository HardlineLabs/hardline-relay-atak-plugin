package com.hardlinelabs.relay.atak.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.atakmap.android.contact.Contacts
import com.atakmap.android.contact.IndividualContact
import com.atakmap.android.contact.IpConnector
import com.atakmap.android.ipc.AtakBroadcast
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.maps.coords.GeoPoint
import com.hardlinelabs.relay.atak.*
import java.security.SecureRandom
import java.util.Date

/** ATAK's contact Send connector; only explicit point sends enter this adapter. */
internal class PointSharing(
    private val map: MapView,
    private val localNode: () -> String?,
    private val transmit: (ByteArray, (String?) -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    companion object { const val SEND = "com.hardlinelabs.relay.atak.SEND_POINT" }
    val last = PointSendState()
    private val received = PointReceiveState()
    private val contacts = linkedMapOf<String, IndividualContact>()
    private val contactStaleness = mutableMapOf<String, Boolean>()
    private val pointMarkers = linkedMapOf<String, Marker>()
    private val random = SecureRandom()
    private var started = false
    private var retryMarker: String? = null
    private var retryNode: String? = null

    private val sendReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started || intent.action != SEND) return
            try {
                val contact = contacts.values.firstOrNull { it.getUid() == intent.getStringExtra("contactUID") }
                    ?: error("Relay contact unavailable. Receive PLI and select the channel again.")
                check(!intent.hasExtra("filename") && !intent.hasExtra("imageFilename") &&
                    !intent.hasExtra("MissionPackageManifest")) { "Only a single map point is supported; files/attachments cannot be sent over Relay." }
                val uids = intent.getStringExtra("targetUID")?.let { arrayOf(it) }
                    ?: intent.getStringArrayExtra("targetsUID")
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<CotEvent>("com.atakmap.contact.CotEvent")
                val uid = if (uids != null) {
                    check(uids.size == 1) { "Select one point at a time for Relay." }; uids.single()
                } else event?.uid ?: error("No map point in the ATAK Send request.")
                val marker = map.rootGroup.deepFindUID(uid) as? Marker
                    ?: error("Relay supports point markers, not shapes or routes.")
                check(marker != map.selfMarker && !uid.startsWith("hardline-relay:")) { "Use PLI for a live contact; select a point marker." }
                send(marker, contact)
            } catch (e: Exception) {
                retryMarker = null; retryNode = null
                last.failed(null, e.message ?: "Point not submitted.")
            }
            changed()
        }
    }

    fun start() {
        if (started) return
        last.clear()
        AtakBroadcast.getInstance().registerReceiver(sendReceiver,
            AtakBroadcast.DocumentedIntentFilter(SEND, "Send one map point to a Relay PLI contact"))
        started = true
    }

    fun stop() {
        if (started) AtakBroadcast.getInstance().unregisterReceiver(sendReceiver)
        started = false
        resetContacts()
        pointMarkers.values.forEach { it.removeFromGroup() }
        pointMarkers.clear(); received.clear()
    }

    fun resetContacts() {
        retryMarker = null; retryNode = null
        contacts.values.forEach { Contacts.getInstance().removeContact(it) }
        contacts.clear()
        contactStaleness.clear()
        last.interrupted()
    }

    fun updateContacts(peers: Map<String, PliState.Peer>, markers: Map<String, Marker>, now: Long) {
        contacts.keys.filter { it !in peers }.toList().forEach { node ->
            contacts.remove(node)?.let { Contacts.getInstance().removeContact(it) }
            contactStaleness.remove(node)
        }
        peers.forEach { (node, peer) ->
            val contact = contacts.getOrPut(node) {
                IndividualContact("${peer.pli.callsign} [Relay]", "hardline-relay:$node", markers[node]).apply {
                    // A send-intent IP connector appears in ATAK's standard point recipient picker.
                    addConnector(IpConnector(SEND))
                    Contacts.getInstance().addContact(this)
                }
            }
            val name = "${peer.pli.callsign} [Relay${if (peer.stale(now)) " / STALE" else ""}]"
            if (contact.name != name) contact.name = name
            val stale = peer.stale(now)
            if (contactStaleness.put(node, stale) != stale) {
                if (stale) contact.stale() else contact.current()
            }
        }
    }

    private fun send(marker: Marker, contact: IndividualContact) {
        check(localNode() != null) { "Select a connected Relay channel before sending." }
        val node = contact.getUid().removePrefix("hardline-relay:")
        val symbol = PointWire.symbols.indexOf(marker.type)
        check(symbol >= 0 && marker.getMetaString("usericon", "").isNullOrEmpty()) {
            "Unsupported point symbol. Use a basic spot or friendly/hostile/neutral/unknown ground marker."
        }
        var token = random.nextLong()
        while (token == 0L) token = random.nextLong()
        val revision = maxOf(System.currentTimeMillis(), marker.getMetaLong("relayPointRevision", 0) + 1)
        val point = MeshPoint(token, PointWire.pointId(marker.uid), revision, PointWire.nodeId(node),
            marker.point.latitude, marker.point.longitude, symbol,
            marker.color, marker.title.orEmpty())
        val bytes = PointWire.encode(point)
        retryMarker = marker.uid; retryNode = node
        marker.setMetaLong("relayPointRevision", revision)
        last.begin(point, contact.name, SystemClock.elapsedRealtime())
        changed()
        transmit(bytes) { error ->
            if (error == null) last.submitted(token) else last.failed(token, error)
            changed()
        }
    }

    fun receive(from: String, bytes: ByteArray, now: Long) {
        val local = localNode()?.let(PointWire::nodeId) ?: return
        val sender = PointWire.nodeId(from)
        when (val message = PointWire.decode(bytes)) {
            is PointWire.Receipt -> if (message.recipient == local) last.receipt(message.token, sender, now)
            is PointWire.Point -> {
                val p = message.point
                when (received.decide(sender, local, p, now)) {
                    PointReceiveState.Decision.IGNORE -> return
                    PointReceiveState.Decision.IMPORT -> importPoint(from, p)
                    PointReceiveState.Decision.RECEIPT_ONLY -> Unit
                }
                // Marker must exist before acknowledgment; re-ack duplicates after a lost receipt.
                received.committed(sender, p, now)
                transmit(PointWire.receipt(p.token, sender)) { /* Sender owns the receipt timeout. */ }
            }
        }
        changed()
    }

    fun retry() {
        try {
            check(last.status in listOf(PointSendState.Status.UNCONFIRMED, PointSendState.Status.FAILED)) { "Wait for the current attempt before retrying." }
            val marker = retryMarker?.let { map.rootGroup.deepFindUID(it) as? Marker }
                ?: error("Select the point and recipient again.")
            val contact = contacts[retryNode] ?: error("Recipient unavailable. Select the point and recipient again.")
            send(marker, contact)
        } catch (e: Exception) { last.failed(null, e.message ?: "Point not submitted."); changed() }
    }

    private fun importPoint(from: String, p: MeshPoint) {
        val uid = "hardline-point:$from:${java.lang.Long.toHexString(p.id)}"
        val marker = pointMarkers.getOrPut(uid) {
            check(pointMarkers.size < 128) { "Relay received-point limit reached." }
            Marker(uid).apply {
                setMetaBoolean("nevercot", true)
                setMetaBoolean("archive", false)
                setMetaBoolean("movable", false)
                setMetaBoolean("removable", false)
                map.rootGroup.addItem(this)
            }
        }
        marker.type = PointWire.symbols[p.symbol]
        marker.point = GeoPoint(p.lat, p.lon)
        marker.title = p.name
        marker.setMetaString("callsign", p.name)
        marker.color = p.color
        marker.setMetaString("remarks", "Relay point from $from. Updated ${Date(p.revision)}; received ${Date()}. Last-known shared point, not live PLI.")
        marker.refresh(map.mapEventDispatcher, null, javaClass)
    }
}
