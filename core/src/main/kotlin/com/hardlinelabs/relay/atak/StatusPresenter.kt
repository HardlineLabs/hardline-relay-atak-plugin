package com.hardlinelabs.relay.atak

/** Pure presentation seam, with no ATAK SDK or radio dependency. */
object StatusPresenter {
    fun label(version: Int, mode: String, state: String): String {
        if (version != 1) return "Incompatible status protocol"
        if (mode != "live" && mode != "simulated") return "Unknown mesh state"
        if (mode == "simulated")
            return when (state) {
                "radio_connected" -> "SIMULATION: Radio connected"
                "service_connected" -> "SIMULATION: Radio status unknown"
                "disconnected" -> "SIMULATION: Mesh disconnected"
                else -> "Unknown mesh state"
            }
        return when (state) {
            "radio_connected" -> "Radio connected; mesh reachability unverified"
            "service_connected" -> "Radio status unknown"
            "disconnected" -> "Mesh disconnected"
            else -> "Unknown mesh state"
        }
    }
}

class StatusModel {
    var takServerConnected: Boolean = false
    var meshState: String = "disconnected"

    fun meshLabel(): String = StatusPresenter.label(1, "live", meshState)
}
