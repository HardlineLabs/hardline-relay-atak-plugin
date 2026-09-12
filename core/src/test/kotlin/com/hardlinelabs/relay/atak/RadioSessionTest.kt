package com.hardlinelabs.relay.atak

import org.junit.Assert.*
import org.junit.Test

class RadioSessionTest {
    @Test
    fun connectedChannelChangeRebuildsChoicesAndRequiresReselection() {
        val session = RadioSession<String>()
        assertTrue(session.observe("radio A/channel A"))
        session.select(1)
        assertFalse(session.observe("radio A/channel A"))
        assertEquals(1, session.selected)
        assertTrue(session.observe("radio A/channel B"))
        assertNull(session.selected)
        assertEquals("radio A/channel B", session.snapshot)
        session.select(2)
        assertEquals(2, session.selected)
    }

    @Test
    fun oldWorkersCannotClearNewBusyFlagOrConsumeNewQueueSlots() {
        val session = RadioSession<String>()
        val oldRefresh = session.beginRefresh()!!
        val oldSend = session.beginTransmit()!!
        session.reset()
        val newRefresh = session.beginRefresh()!!
        val newSend = session.beginTransmit()!!
        assertFalse(session.finishRefresh(oldRefresh))
        assertFalse(session.finishTransmit(oldSend))
        assertTrue(session.busy)
        assertEquals(1, session.queued)
        assertTrue(session.finishRefresh(newRefresh))
        assertTrue(session.finishTransmit(newSend))
        assertFalse(session.busy)
        assertEquals(0, session.queued)
    }

    @Test
    fun resetInvalidatesQueuedSendsAndPreservesQueueBound() {
        val session = RadioSession<String>()
        session.observe("radio")
        session.select(1)
        val epoch = session.beginTransmit()!!
        repeat(3) { assertNotNull(session.beginTransmit()) }
        assertNull(session.beginTransmit())
        session.reset()
        assertNull(session.snapshot)
        assertNull(session.selected)
        assertNotEquals(epoch, session.generation)
        assertEquals(0, session.queued)
        assertTrue(session.observe("radio"))
    }
}
