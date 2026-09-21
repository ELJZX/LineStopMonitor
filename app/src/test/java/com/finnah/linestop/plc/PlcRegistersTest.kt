package com.finnah.linestop.plc

import org.junit.Assert.assertEquals
import org.junit.Test

class PlcRegistersTest {

    @Test
    fun `адреса регистров соответствуют программе ПЛК`() {
        assertEquals(0, PlcRegisters.STATE)
        assertEquals(1, PlcRegisters.START_COUNTER)
        assertEquals(4, PlcRegisters.ACK_COUNTER)
        assertEquals(5, PlcRegisters.LAST_CAUSE)
        assertEquals(100, PlcRegisters.CAUSE_CODE)
        assertEquals(101, PlcRegisters.ACK_REQUEST)
    }

    @Test
    fun `регистр D отображается в Modbus-адрес со смещением 0x1000`() {
        assertEquals(0x1000, PlcRegisters.d(PlcRegisters.STATE))
        assertEquals(0x1004, PlcRegisters.d(PlcRegisters.ACK_COUNTER))
        assertEquals(0x1064, PlcRegisters.d(PlcRegisters.CAUSE_CODE))
        assertEquals(0x1065, PlcRegisters.d(PlcRegisters.ACK_REQUEST))
    }
}
