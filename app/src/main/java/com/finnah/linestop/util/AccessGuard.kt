package com.finnah.linestop.util

import java.security.MessageDigest

/**
 * Проверка кода доступа к настройкам.
 *
 * Хранится только SHA-256 хеш — сам код в приложении не записан
 * и нигде не отображается.
 */
object AccessGuard {

    private const val HASH = "73a2af8864fc500fa49048bf3003776c19938f360e56bd03663866fb3087884a"

    fun check(input: String): Boolean = sha256(input.trim()) == HASH

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
