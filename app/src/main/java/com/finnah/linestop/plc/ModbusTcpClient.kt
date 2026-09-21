package com.finnah.linestop.plc

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Минимальный клиент Modbus TCP (function codes 0x03, 0x06, 0x10).
 *
 * Реализован вручную, без внешних библиотек, чтобы приложение работало
 * автономно на планшете и не зависело от устаревших Java-библиотек
 * (jamod/modbus4j), которые не дружат с Android.
 *
 * Контроллер Delta DVP12SE11R является Modbus TCP slave (порт 502).
 * Регистры данных D устройства отображаются на holding registers
 * с базовым адресом 0x1000 (см. [PlcRegisters]).
 */
class ModbusTcpClient(
    var host: String,
    var port: Int = 502,
    var unitId: Int = 1,
    private val timeoutMs: Int = 1500
) {

    companion object {
        const val FUNC_READ_HOLDING = 0x03
        const val FUNC_WRITE_SINGLE = 0x06
        const val FUNC_WRITE_MULTIPLE = 0x10
        private const val PROTOCOL_ID = 0
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var txId = 0

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    @Synchronized
    fun connect() {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    @Synchronized
    fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
    }

    /** FC 0x03 — чтение группы holding registers. */
    @Synchronized
    fun readHoldingRegisters(startAddress: Int, quantity: Int): IntArray {
        require(quantity in 1..125) { "quantity must be 1..125" }
        val pdu = byteArrayOf(
            FUNC_READ_HOLDING.toByte(),
            hi(startAddress), lo(startAddress),
            hi(quantity), lo(quantity)
        )
        val resp = exchange(pdu)
        if ((resp[0].toInt() and 0xFF) != FUNC_READ_HOLDING) {
            throw IOException("Unexpected function code 0x${(resp[0].toInt() and 0xFF).toString(16)}")
        }
        val byteCount = resp[1].toInt() and 0xFF
        if (byteCount != quantity * 2) {
            throw IOException("Bad byte count: $byteCount (expected ${quantity * 2})")
        }
        val out = IntArray(quantity)
        for (i in 0 until quantity) {
            out[i] = ((resp[2 + i * 2].toInt() and 0xFF) shl 8) or
                    (resp[3 + i * 2].toInt() and 0xFF)
        }
        return out
    }

    /** FC 0x06 — запись одного holding register. */
    @Synchronized
    fun writeSingleRegister(address: Int, value: Int) {
        val pdu = byteArrayOf(
            FUNC_WRITE_SINGLE.toByte(),
            hi(address), lo(address),
            hi(value), lo(value)
        )
        exchange(pdu)
    }

    /** FC 0x10 — запись нескольких holding registers. */
    @Synchronized
    fun writeMultipleRegisters(startAddress: Int, values: IntArray) {
        require(values.isNotEmpty() && values.size <= 123) { "values must be 1..123" }
        val byteCount = values.size * 2
        val pdu = ByteArray(6 + byteCount)
        pdu[0] = FUNC_WRITE_MULTIPLE.toByte()
        pdu[1] = hi(startAddress)
        pdu[2] = lo(startAddress)
        pdu[3] = hi(values.size)
        pdu[4] = lo(values.size)
        pdu[5] = byteCount.toByte()
        for (i in values.indices) {
            pdu[6 + i * 2] = hi(values[i])
            pdu[7 + i * 2] = lo(values[i])
        }
        exchange(pdu)
    }

    private fun exchange(pdu: ByteArray): ByteArray {
        val out = output ?: throw IOException("Not connected")
        val inp = input ?: throw IOException("Not connected")

        txId = (txId + 1) and 0xFFFF
        val frame = ByteArray(7 + pdu.size)
        frame[0] = hi(txId)
        frame[1] = lo(txId)
        frame[2] = hi(PROTOCOL_ID)
        frame[3] = lo(PROTOCOL_ID)
        frame[4] = hi(pdu.size + 1)
        frame[5] = lo(pdu.size + 1)
        frame[6] = unitId.toByte()
        System.arraycopy(pdu, 0, frame, 7, pdu.size)

        out.write(frame)
        out.flush()

        val header = readFully(inp, 7)
        val rxTx = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val proto = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val len = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

        if (proto != PROTOCOL_ID) throw IOException("Bad protocol id $proto")
        if (len < 2) throw IOException("Bad MBAP length $len")
        if (rxTx != txId) throw IOException("Transaction id mismatch")

        val resp = readFully(inp, len - 1)
        val func = resp[0].toInt() and 0xFF
        if (func and 0x80 != 0) {
            val ex = if (resp.size > 1) resp[1].toInt() and 0xFF else -1
            throw IOException("Modbus exception 0x${ex.toString(16)}")
        }
        return resp
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException("Connection closed by PLC")
            off += r
        }
        return buf
    }

    private fun hi(v: Int) = ((v ushr 8) and 0xFF).toByte()
    private fun lo(v: Int) = (v and 0xFF).toByte()
}
