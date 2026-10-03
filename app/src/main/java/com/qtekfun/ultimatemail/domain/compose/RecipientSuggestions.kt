// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.AddressSample
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Suggests recipients while the user types (RF-07), from local data only: the people who appear
 * as sender, To or Cc in the account's stored messages. No contacts permission, no network.
 *
 * Ranking: each appearance counts one (three when the message is in a Sent folder, because
 * someone the user wrote to is a better guess than someone who wrote to them), multiplied by a
 * recency factor that goes from 2 for a message from now down to 1 for a very old one (half way
 * after [RECENCY_HALF_LIFE_DAYS] days). Ties go to the most recent, then alphabetically.
 *
 * Matching ignores case and accents and works on the start of the address, of the display name
 * or of any word in them (`jo`, `perez` and `ñor` find `José Pérez <jp@x.test>` and
 * `nora@x.test`). The user's own address is never suggested. Only the newest [SAMPLE_SIZE]
 * messages of the account are looked at, which keeps every keystroke cheap.
 */
class RecipientSuggestions @Inject constructor(
    private val messages: MessageDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** Up to [limit] suggestions for [query] on [accountId], best first; blank returns the top ones. */
    suspend fun suggest(
        accountId: Long,
        query: String,
        ownAddress: String? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<MailAddress> = withContext(io) {
        val now = clock.instant()
        val wanted = fold(query)
        val own = ownAddress?.let { it.trim().lowercase(Locale.ROOT) }
        tally(messages.addressSamples(accountId, SAMPLE_SIZE), now)
            .filter { it.address.lowercase(Locale.ROOT) != own && it.matches(wanted) }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.lastSeen }
                    .thenBy { it.address.lowercase(Locale.ROOT) }
            )
            .take(limit)
            .map { MailAddress(it.address, it.name) }
    }

    private class Candidate(val address: String) {
        var name: String? = null
        var score = 0.0
        var lastSeen: Instant = Instant.MIN

        fun matches(wanted: String): Boolean {
            if (wanted.isEmpty()) return true
            val haystacks = listOf(fold(address), fold(name.orEmpty()))
            return haystacks.any { text ->
                text.startsWith(wanted) || text.split(WORD_BOUNDARY).any { it.startsWith(wanted) }
            }
        }
    }

    private fun tally(samples: List<AddressSample>, now: Instant): Collection<Candidate> {
        val byAddress = LinkedHashMap<String, Candidate>()
        fun see(address: String, name: String?, sample: AddressSample) {
            val clean = address.trim()
            if (!RecipientParser.isValid(clean)) return
            val candidate = byAddress.getOrPut(clean.lowercase(Locale.ROOT)) { Candidate(clean) }
            val age = Duration.between(sample.sentAt, now).toDays().coerceAtLeast(0)
            val recency = 1.0 + HALF_LIFE / (HALF_LIFE + age)
            candidate.score += (if (sample.fromUser) SENT_WEIGHT else 1.0) * recency
            if (sample.sentAt > candidate.lastSeen) {
                candidate.lastSeen = sample.sentAt
                if (!name.isNullOrBlank()) candidate.name = name
            }
            if (candidate.name == null && !name.isNullOrBlank()) candidate.name = name
        }
        samples.forEach { sample ->
            see(sample.senderAddress, sample.senderName, sample)
            sample.toAddresses.forEach { see(it, null, sample) }
            sample.ccAddresses.forEach { see(it, null, sample) }
        }
        return byAddress.values
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        const val SAMPLE_SIZE = 3000
        const val RECENCY_HALF_LIFE_DAYS = 90
        private const val SENT_WEIGHT = 3.0
        private const val HALF_LIFE = RECENCY_HALF_LIFE_DAYS.toDouble()
        private val WORD_BOUNDARY = Regex("[^\\p{L}\\p{N}]+")
        private val ACCENTS = Regex("\\p{M}+")

        /** Lowercase, accents removed, so `Pérez` and `perez` compare equal. */
        internal fun fold(text: String): String =
            ACCENTS.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "")
                .lowercase(Locale.ROOT)
    }
}
