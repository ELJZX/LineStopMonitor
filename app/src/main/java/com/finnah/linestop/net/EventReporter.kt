package com.finnah.linestop.net

import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * Отправляет события мониторинга на веб-сервер.
 *
 * События копятся в очереди и уходят пачками по HTTP. Если сервер недоступен,
 * события остаются в очереди и отправляются при следующей попытке. Очередь
 * ограничена, чтобы не расти бесконечно.
 */
class EventReporter(
    private val deviceId: String,
    private val transport: Transport = HttpTransport()
) {

    fun interface Transport {
        /** @return true, если сервер принял запрос (2xx). */
        fun post(url: String, json: String): Boolean
    }

    companion object {
        const val BATCH_SIZE = 50
        const val MAX_QUEUE = 500
        const val PATH = "/api/events"

        /** Приводит адрес сервера к полному URL приёма событий. */
        fun endpoint(url: String): String {
            var base = url.trim()
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                base = "http://$base"
            }
            base = base.trimEnd('/')
            return if (base.endsWith(PATH)) base else base + PATH
        }
    }

    private val lock = Any()
    private val queue = ArrayDeque<MonitorEvent>()

    @Volatile
    private var url: String? = null

    fun setUrl(value: String?) {
        url = value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isEnabled(): Boolean = url != null

    fun enqueue(event: MonitorEvent) {
        synchronized(lock) {
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(event)
        }
    }

    fun pending(): Int = synchronized(lock) { queue.size }

    /**
     * Пытается отправить накопленные события.
     * @return сколько событий удалось отправить.
     */
    fun flush(): Int {
        val target = url ?: return 0
        val endpoint = endpoint(target)
        var sent = 0
        while (true) {
            val batch = takeBatch() ?: break
            if (!transport.post(endpoint, EventJson.encodeBatch(batch))) {
                requeue(batch)
                break
            }
            sent += batch.size
        }
        return sent
    }

    private fun takeBatch(): List<MonitorEvent>? = synchronized(lock) {
        if (queue.isEmpty()) return null
        val batch = ArrayList<MonitorEvent>(BATCH_SIZE)
        while (batch.size < BATCH_SIZE && queue.isNotEmpty()) {
            batch.add(queue.removeFirst())
        }
        batch
    }

    private fun requeue(batch: List<MonitorEvent>) {
        synchronized(lock) {
            for (i in batch.indices.reversed()) {
                queue.addFirst(batch[i])
            }
        }
    }
}

/** Реальный транспорт поверх HttpURLConnection. */
class HttpTransport : EventReporter.Transport {
    override fun post(url: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) conn.errorStream?.close()
            code in 200..299
        } catch (t: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
