package com.hardlinelabs.relay.atak.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest

internal object ProfileCatalog {
    private val uri = Uri.parse("content://com.hardlinelabs.relay.profiles/profiles")
    private var grantRetained = false

    data class Entry(
        val id: String,
        val name: String,
        val locked: Boolean,
        val activation: String,
        val node: Int,
        val index: Int,
        val slot: Int,
        val fingerprint: String,
        val switching: Boolean,
    )

    data class Snapshot(val entries: List<Entry>, val switching: Boolean)

    fun read(context: Context): Snapshot {
        var switching = false
        val result = mutableListOf<Entry>()
        val cursor =
            context.contentResolver.query(uri, null, null, null, null)
                ?: error(
                    "Open Hardline Relay once to connect saved channels to ATAK. Sending paused."
                )
        if (!grantRetained)
            grantRetained =
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                    .isSuccess
        cursor.use { c ->
            switching = c.extras.getBoolean("switching", false)
            while (c.moveToNext() && result.size < 64) {
                fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
                fun n(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
                result.add(
                    Entry(
                        s("id"),
                        s("name"),
                        n("locked") == 1,
                        s("activation"),
                        n("node"),
                        n("index"),
                        n("slot"),
                        s("fingerprint"),
                        n("switching") == 1,
                    )
                )
            }
        }
        return Snapshot(result, switching || result.any { it.switching })
    }

    fun open(context: Context, id: String? = null) {
        context.startActivity(
            Intent()
                .setClassName("com.hardlinelabs.relay", "com.hardlinelabs.relay.MainActivity")
                .putExtra("profileId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun fingerprint(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
