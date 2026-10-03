// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import javax.net.ssl.SSLSocketFactory

/**
 * Network settings of the mail client.
 *
 * [sslSocketFactory] is null in production, which means the system trust store with full
 * certificate and host name validation. It exists so tests can trust the throwaway certificate
 * of their local test server; nothing in the app may pass a factory that skips validation.
 */
data class MailClientConfig(
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    val sslSocketFactory: SSLSocketFactory? = null
) {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "Timeouts must be positive" }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
    }
}
