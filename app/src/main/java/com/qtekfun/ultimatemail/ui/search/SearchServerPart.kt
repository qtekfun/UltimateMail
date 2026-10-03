// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure

private val MinTouchTarget = 48.dp

/** The action to search the server too, and what came of it. */
@Composable
internal fun ServerPart(state: SearchState, actions: SearchActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (val server = state.server) {
            ServerPhase.Idle -> {
                if (state.offerServer) {
                    Text(
                        stringResource(R.string.search_server_offer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ServerButton(state.offerServer, actions.onServerSearch)
            }

            ServerPhase.Searching -> ServerRunning(actions.onCancelServer)

            is ServerPhase.Done -> ServerDone(server, state.serverResults.size, actions)

            is ServerPhase.Failed -> {
                Text(
                    stringResource(server.reason.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (server.reason != ServerSearchFailure.AUTHENTICATION) {
                    OutlinedButton(
                        onClick = actions.onServerSearch,
                        modifier = Modifier.heightIn(min = MinTouchTarget)
                    ) {
                        Text(stringResource(R.string.search_server_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerButton(primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = MinTouchTarget)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) {
            Text(stringResource(R.string.search_server_action))
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(stringResource(R.string.search_server_action))
        }
    }
}

@Composable
private fun ServerRunning(onCancel: () -> Unit) {
    val description = stringResource(R.string.search_server_searching)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = description
            }
        )
        Text(description, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.search_server_cancel))
        }
    }
}

@Composable
private fun ServerDone(server: ServerPhase.Done, shown: Int, actions: SearchActions) {
    val summary = if (server.added > 0 || shown > 0) {
        pluralStringResource(R.plurals.search_server_found, server.added, server.added)
    } else {
        stringResource(R.string.search_server_none)
    }
    Text(summary, style = MaterialTheme.typography.bodyMedium)
    if (server.incomplete) {
        Text(
            stringResource(R.string.search_server_incomplete),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    OutlinedButton(
        onClick = actions.onServerSearch,
        modifier = Modifier.heightIn(min = MinTouchTarget)
    ) {
        Text(stringResource(R.string.search_server_retry))
    }
}

private fun ServerSearchFailure.messageRes(): Int = when (this) {
    ServerSearchFailure.OFFLINE -> R.string.search_server_offline
    ServerSearchFailure.TIMEOUT -> R.string.search_server_timeout
    ServerSearchFailure.AUTHENTICATION -> R.string.search_server_auth
    ServerSearchFailure.SERVER -> R.string.search_server_failed
    ServerSearchFailure.NOTHING_TO_SEARCH -> R.string.search_server_nothing
}
