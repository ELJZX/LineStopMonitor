package com.finnah.linestop.plc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlcSnapshotTest {

    @Test
    fun `значения по умолчанию`() {
        val snapshot = PlcSnapshot()
        assertFalse(snapshot.connected)
        assertFalse(snapshot.alarmActive)
        assertEquals(0, snapshot.startCounter)
        assertEquals(0, snapshot.ackCounter)
        assertEquals(0, snapshot.lastCause)
    }

    @Test
    fun `снимок хранит состояние аварии`() {
        val snapshot = PlcSnapshot(
            connected = true,
            alarmActive = true,
            startCounter = 3,
            ackCounter = 2,
            lastCause = 7
        )
        assertEquals(true, snapshot.alarmActive)
        assertEquals(3, snapshot.startCounter)
        assertEquals(2, snapshot.ackCounter)
        assertEquals(7, snapshot.lastCause)
    }
}
