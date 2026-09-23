package com.finnah.linestop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonTest {

    private fun event(
        message: String = "ok",
        alarmId: Long? = 1L,
        causeText: String? = null
    ) = MonitorEvent(
        time = 1234L,
        type = "ALARM_START",
        level = "WARN",
        category = "ALARM",
        message = message,
        deviceId = "dev-1",
        alarmId = alarmId,
        causeText = causeText
    )

    @Test
    fun `кодирует основные поля`() {
        val json = EventJson.encode(event())
        assertTrue(json.contains("\"type\":\"ALARM_START\""))
        assertTrue(json.contains("\"deviceId\":\"dev-1\""))
        assertTrue(json.contains("\"alarmId\":1"))
        assertTrue(json.contains("\"time\":1234"))
    }

    @Test
    fun `опускает null-поля`() {
        val json = EventJson.encode(event(alarmId = null))
        assertFalse(json.contains("alarmId"))
        assertFalse(json.contains("causeText"))
        assertFalse(json.contains("stopTime"))
        assertFalse(json.contains("startTime"))
    }

    @Test
    fun `кодирует время начала и конца аварии`() {
        val e = MonitorEvent(
            time = 3000L, type = "ALARM_END", level = "INFO", category = "ALARM",
            message = "end", deviceId = "dev-1", alarmId = 1L,
            stopTime = 1000L, startTime = 3000L, durationMs = 2000L
        )
        val json = EventJson.encode(e)
        assertTrue(json.contains("\"stopTime\":1000"))
        assertTrue(json.contains("\"startTime\":3000"))
        assertTrue(json.contains("\"durationMs\":2000"))
    }

    @Test
    fun `экранирует кавычки слэши и переводы строк`() {
        val json = EventJson.encode(event(message = "Он сказал \"да\"\nC:\\tmp\\x"))
        assertTrue(json.contains("\\\"да\\\""))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("C:\\\\tmp\\\\x"))
    }

    @Test
    fun `батч кодируется как массив`() {
        val json = EventJson.encodeBatch(listOf(event(), event(message = "two")))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertEquals(2, json.split("{\"time\"").size - 1)
    }

    @Test
    fun `пустой батч - пустой массив`() {
        assertEquals("[]", EventJson.encodeBatch(emptyList()))
    }

    @Test
    fun `кодирует оператора и механика`() {
        val e = MonitorEvent(
            time = 1L, type = "SHIFT_START", level = "INFO", category = "SHIFT",
            message = "shift", deviceId = "dev-1", operator = "Иванов",
            mechanic = "Петров"
        )
        val json = EventJson.encode(e)
        assertTrue(json.contains("\"operator\":\"Иванов\""))
        assertTrue(json.contains("\"mechanic\":\"Петров\""))
    }

    // ------------------------------------------------------------ endpoint

    @Test
    fun `endpoint добавляет схему и путь`() {
        assertEquals(
            "http://172.16.29.1:8080/api/events",
            EventReporter.endpoint("172.16.29.1:8080")
        )
        assertEquals(
            "http://host:8080/api/events",
            EventReporter.endpoint("http://host:8080/")
        )
        assertEquals(
            "https://host/api/events",
            EventReporter.endpoint("https://host")
        )
    }

    @Test
    fun `endpoint не дублирует путь`() {
        assertEquals(
            "http://host:8080/api/events",
            EventReporter.endpoint("http://host:8080/api/events")
        )
    }

    // ------------------------------------------------------------- reporter

    @Test
    fun `flush отправляет накопленные события`() {
        val sent = mutableListOf<String>()
        val reporter = EventReporter("dev") { _, json ->
            sent.add(json); true
        }
        reporter.setUrl("http://x:1")
        reporter.enqueue(event())
        reporter.enqueue(event(message = "b"))

        assertEquals(2, reporter.flush())
        assertEquals(0, reporter.pending())
        assertEquals(1, sent.size)
        assertTrue(sent[0].startsWith("["))
    }

    @Test
    fun `при ошибке события остаются в очереди`() {
        val reporter = EventReporter("dev") { _, _ -> false }
        reporter.setUrl("http://x:1")
        reporter.enqueue(event())

        assertEquals(0, reporter.flush())
        assertEquals(1, reporter.pending())
    }

    @Test
    fun `без адреса отправка выключена`() {
        val reporter = EventReporter("dev") { _, _ -> true }
        reporter.enqueue(event())
        assertEquals(0, reporter.flush())
        assertEquals(1, reporter.pending())
        assertFalse(reporter.isEnabled())
    }

    @Test
    fun `батчи делятся по 50`() {
        var calls = 0
        val reporter = EventReporter("dev") { _, _ ->
            calls++; true
        }
        reporter.setUrl("http://x:1")
        repeat(120) { reporter.enqueue(event()) }

        assertEquals(120, reporter.flush())
        assertEquals(3, calls)
        assertEquals(0, reporter.pending())
    }

    @Test
    fun `очередь ограничена`() {
        val reporter = EventReporter("dev") { _, _ -> true }
        repeat(EventReporter.MAX_QUEUE + 20) { reporter.enqueue(event()) }
        assertEquals(EventReporter.MAX_QUEUE, reporter.pending())
    }
}
