package com.hardlinelabs.relay.atak.harness

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hardlinelabs.relay.atak.StatusPresenter

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var radioConnected = false
        var serverConnected = false
        val mesh = TextView(this).apply { textSize = 20f }
        val server = TextView(this)
        fun render() {
            mesh.text = StatusPresenter.label(1, "simulated",
                if (radioConnected) "radio_connected" else "disconnected")
            server.text = "SIMULATION: TAK server " + if (serverConnected) "connected" else "disconnected"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(context).apply { text = "Relay ATAK Test Harness"; textSize = 26f })
            addView(TextView(context).apply {
                text = "Hardware-free display tests. This app is not loaded inside ATAK."
            })
            addView(mesh)
            addView(server)
            addView(Button(context).apply {
                text = "Toggle fake radio"
                setOnClickListener { radioConnected = !radioConnected; render() }
            })
            addView(Button(context).apply {
                text = "Toggle fake TAK server"
                setOnClickListener { serverConnected = !serverConnected; render() }
            })
        }
        render()
        setContentView(layout)
    }
}
