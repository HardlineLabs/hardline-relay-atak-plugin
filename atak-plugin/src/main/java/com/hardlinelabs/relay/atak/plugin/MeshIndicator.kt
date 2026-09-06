package com.hardlinelabs.relay.atak.plugin

import android.graphics.Color
import android.graphics.Typeface
import com.atakmap.android.maps.MapView
import com.atakmap.android.widgets.MapWidget
import com.atakmap.android.widgets.RootLayoutWidget
import com.atakmap.android.widgets.TextWidget
import com.hardlinelabs.relay.atak.MeshHealth

/** Independent map badge anchored above ATAK's server connection icon. */
internal class MeshIndicator(private val map: MapView, open: () -> Unit) {
    private val root = map.getComponentExtra("rootLayoutWidget") as RootLayoutWidget
    private val badge = TextWidget("HL", MapView.getTextFormat(Typeface.DEFAULT_BOLD, 1), true).apply {
        name = "Hardline mesh status"
        setPadding(10f, 8f, 10f, 8f)
        setBackground(Color.argb(220, 13, 21, 27))
        addOnClickListener(MapWidget.OnClickListener { _, _ -> open() })
    }
    fun start() { root.addWidget(badge); position() }
    fun stop() { root.removeWidget(badge) }
    private fun position() {
        val server = root.findWidget("Connection Icon", true)
        val p = server?.absolutePosition
        val density = map.resources.displayMetrics.density
        val x = p?.x ?: (map.width - badge.getSize(true, true)[0] - 24 * density)
        val y = p?.y ?: (map.height - 90 * density)
        badge.setPoint(x.coerceIn(0f, (map.width - badge.getSize(true, true)[0] - 24 * density).coerceAtLeast(0f)),
            (y - badge.height - 8 * density).coerceAtLeast(0f))
        badge.setVisible(map.visibility == android.view.View.VISIBLE)
    }
    fun update(state: MeshHealth.State) {
        badge.color = when (state) {
            MeshHealth.State.CONFIRMED -> Color.rgb(93, 218, 160)
            MeshHealth.State.WAITING -> Color.rgb(255, 195, 100)
            MeshHealth.State.FAULT -> Color.rgb(255, 130, 130)
            MeshHealth.State.INACTIVE -> Color.rgb(160, 173, 181)
        }
        position()
    }
}
