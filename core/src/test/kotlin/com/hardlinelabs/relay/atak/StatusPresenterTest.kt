package com.hardlinelabs.relay.atak

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StatusPresenterTest {
    @Test
    fun contractVectorsMatch() {
        File("../protocol/status-v1.tsv")
            .readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .forEach {
                val row = it.split('\t')
                assertEquals(row[3], StatusPresenter.label(row[0].toInt(), row[1], row[2]))
            }
    }

    @Test
    fun takServerCannotMakeMeshAppearConnected() {
        val model = StatusModel()
        model.takServerConnected = true
        assertEquals("Mesh disconnected", model.meshLabel())
        model.meshState = "radio_connected"
        val meshLabel = model.meshLabel()
        model.takServerConnected = false
        assertEquals(meshLabel, model.meshLabel())
        model.meshState = "disconnected"
        assertEquals("Mesh disconnected", model.meshLabel())
    }

    @Test
    fun unknownModeFailsClosed() {
        assertEquals("Unknown mesh state", StatusPresenter.label(1, "future", "radio_connected"))
    }
}
