package com.hardlinelabs.relay.atak.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.atakmap.android.chat.ChatDatabase
import com.atakmap.android.chat.ChatManagerMapComponent
import com.atakmap.android.contact.Contacts
import com.atakmap.android.contact.IndividualContact
import com.atakmap.android.contact.PluginConnector
import com.atakmap.android.contact.ContactConnectorManager
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.ipc.AtakBroadcast
import com.atakmap.android.maps.MapView
import com.hardlinelabs.relay.atak.ChatWire
import com.hardlinelabs.relay.atak.PointWire
import java.util.UUID

/** ATAK owns conversation history and UI. Relay carries explicit contact messages only. */
internal class ChatSharing(private val map: MapView, private val localNode: () -> String?,
                           private val transmit: (ByteArray, (String?) -> Unit) -> Unit,
                           private val contact: (String, String) -> Unit,
                           private val changed: () -> Unit) {
    companion object {
        const val SEND = "com.hardlinelabs.relay.atak.SEND_CHAT"
        fun connector() = object : PluginConnector(SEND) {
            override fun getConnectionLabel() = "Relay chat"
            override fun getIconUri() = "android.resource://android/${android.R.drawable.sym_action_chat}"
        }
    }
    private data class Pending(val node: Long, val at: Long, val bundle: Bundle, var confirmed: Boolean = false)
    private val pending = linkedMapOf<UUID, Pending>()
    private val receipts = linkedMapOf<String, Long>()
    private var started = false
    var status = "Chat · Select a Relay contact in ATAK chat"; private set
    var lastProof = -1L; private set
    private val db get() = ChatDatabase.getInstance(map.context)
    private val contactHandler = object : ContactConnectorManager.ContactConnectorHandler() {
        override fun isSupported(type: String) = type == PluginConnector.CONNECTOR_TYPE
        override fun getName() = "Hardline Relay chat"
        override fun getDescription() = "Open ATAK conversations for Relay contacts"
        override fun hasFeature(feature: ContactConnectorManager.ConnectorFeature) = false
        override fun getFeature(type: String, feature: ContactConnectorManager.ConnectorFeature, uid: String, address: String): Any? = null
        override fun handleContact(type: String, uid: String, address: String): Boolean {
            if (address != SEND || !uid.startsWith("hardline-relay:")) return false
            val target = Contacts.getInstance().getContactByUuid(uid) as? IndividualContact ?: return false
            ChatManagerMapComponent.getInstance().openConversation(target, true)
            return true
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started || intent.action != SEND) return
            try {
                val bundle = intent.getBundleExtra(ChatManagerMapComponent.PLUGIN_SEND_MESSAGE_EXTRA) ?: return
                check(localNode() != null) { "Select a connected private Relay channel before chatting." }
                val destinations = bundle.getStringArray("destinations").orEmpty()
                check(destinations.size == 1) { "Choose one Relay contact for each chat. Public rooms and group fan-out are not supported." }
                val uid = destinations.single()
                check(uid.startsWith("hardline-relay:")) { "Choose a Relay contact." }
                val contact = Contacts.getInstance().getContactByUuid(uid) as? IndividualContact
                    ?: error("Relay contact unavailable. Receive fresh PLI on this channel first.")
                val node = PointWire.nodeId(uid.removePrefix("hardline-relay:"))
                val id = UUID.fromString(requireNotNull(bundle.getString("messageId")))
                val message = ChatWire.Text(id, node, System.currentTimeMillis(), callsign(), requireNotNull(bundle.getString("message")))
                val bytes = ChatWire.encode(message)
                val now = SystemClock.elapsedRealtime()
                pending.entries.removeAll { now - it.value.at > 300_000 }
                check(pending.size < 32) { "Relay chat has 32 recent messages. Wait before sending more." }
                check(id !in pending) { "This message is already tracked. Check its delivery status." }
                pending[id] = Pending(node, now, Bundle(bundle))
                status = "Chat to ${contact.name} · Awaiting recipient receipt"
                transmit(bytes) { error ->
                    if (error != null && pending[id]?.confirmed != true) {
                        pending.remove(id); status = "Chat not submitted · $error"
                        Toast.makeText(map.context, status, Toast.LENGTH_LONG).show()
                    }
                    changed()
                }
            } catch (e: Exception) {
                status = e.message ?: "Chat not submitted."
                Toast.makeText(map.context, status, Toast.LENGTH_LONG).show()
            }
            changed()
        }
    }
    private fun callsign(): String {
        var name = map.deviceCallsign.ifBlank { "Relay" }
        while (name.toByteArray(Charsets.UTF_8).size > 24) name = name.substring(0, name.offsetByCodePoints(name.length, -1))
        return name
    }
    fun start() {
        if (started) return
        CotMapComponent.getInstance().contactConnectorMgr.addContactHandler(contactHandler)
        AtakBroadcast.getInstance().registerReceiver(receiver, AtakBroadcast.DocumentedIntentFilter(SEND, "Send ATAK chat to a Relay contact"))
        started = true
    }
    fun reset() { pending.clear(); receipts.clear(); lastProof = -1; status = "Chat · Select a Relay contact in ATAK chat" }
    fun stop() {
        if (started) {
            AtakBroadcast.getInstance().unregisterReceiver(receiver)
            CotMapComponent.getInstance().contactConnectorMgr.removeContactHandler(contactHandler)
        }
        started = false; reset()
    }
    fun tick(now: Long) {
        val waiting = pending.values.count { !it.confirmed && now - it.at >= 120_000 }
        if (waiting > 0) status = "Chat · $waiting unconfirmed after 2 minutes; delivery unknown"
        pending.entries.removeAll { now - it.value.at > 300_000 }
    }
    fun receive(from: String, bytes: ByteArray, now: Long) {
        val local = localNode()?.let(PointWire::nodeId) ?: return
        val sender = PointWire.nodeId(from)
        val message = ChatWire.decode(bytes)
        if (message.recipient != local || sender == local) return
        when (message) {
            is ChatWire.Receipt -> {
                val sent = pending[message.id] ?: return
                if (sent.node != sender || sent.confirmed || now - sent.at !in 0..300_000) return
                sent.confirmed = true
                val bundle = Bundle(sent.bundle).apply { putString("status", "DELIVERED"); putLong("receiveTime", System.currentTimeMillis()) }
                persist(bundle)
                status = "Chat · Recipient received message in ATAK"
            }
            is ChatWire.Text -> {
                contact(from, message.callsign)
                val uid = "hardline-relay:$from"
                // Stable per-sender ID deduplicates across BLE reconnections and plugin restarts.
                val id = "relay:$from:${message.id}"
                if (db.getChatMessage(id) == null) {
                    val self = map.selfMarker?.uid ?: return
                    val bundle = Bundle().apply {
                        putString("conversationId", uid); putString("conversationName", "${message.callsign} [Relay]")
                        putString("messageId", id); putString("senderUid", uid); putString("senderCallsign", message.callsign)
                        putString("uid", self); putStringArray("destinations", arrayOf(self)); putString("parent", "RootContactGroup")
                        putString("status", "NONE"); putString("message", message.text)
                        putLong("sentTime", message.time); putLong("receiveTime", System.currentTimeMillis())
                    }
                    persist(bundle)
                }
                receipts.entries.removeAll { now - it.value > 300_000 }
                if (now - (receipts[id] ?: -5000L) >= 5000) {
                    receipts[id] = now
                    while (receipts.size > 128) receipts.remove(receipts.keys.first())
                    transmit(ChatWire.encode(ChatWire.Receipt(message.id, sender))) { }
                }
                status = "Chat · Message received in ATAK"
            }
        }
        lastProof = now
        changed()
    }
    private fun persist(bundle: Bundle) {
        val ids = db.addChat(bundle)
        AtakBroadcast.getInstance().sendBroadcast(Intent("com.atakmap.android.chat.NEW_CHAT_MESSAGE").apply {
            putExtra("id", ids.firstOrNull() ?: bundle.getLong("id"))
            putExtra("groupId", ids.getOrNull(1) ?: bundle.getLong("groupId"))
            putExtra("conversationId", bundle.getString("conversationId"))
        })
    }
}
