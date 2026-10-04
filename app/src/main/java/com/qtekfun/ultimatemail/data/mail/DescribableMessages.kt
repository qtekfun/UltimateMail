// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.StoreClosedException

/**
 * Maps every message of a batch, leaving out the ones the server cannot describe: a message
 * whose envelope does not load (it was expunged meanwhile, or its structure is broken) must not
 * fail the other 199 of its batch, or the whole folder. It is not stored, so the next sync
 * tries it again. A closed folder or store is the connection going away and still propagates.
 */
internal inline fun <T, R : Any> Iterable<T>.mapDescribable(transform: (T) -> R): List<R> =
    mapNotNull { item ->
        try {
            transform(item)
        } catch (closed: FolderClosedException) {
            throw closed
        } catch (closed: StoreClosedException) {
            throw closed
        } catch (@Suppress("SwallowedException") broken: MessagingException) {
            null
        }
    }
