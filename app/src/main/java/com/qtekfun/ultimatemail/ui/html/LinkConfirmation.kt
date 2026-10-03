// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.RemoteBanner

/** Asks the reader to confirm [link] before it is opened; the real address is always shown. */
@Composable
fun LinkConfirmationDialog(link: PendingLink, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.html_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(link.target)
                if (link.deceptive) {
                    Text(
                        text = stringResource(R.string.html_link_deceptive_warning),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.html_link_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.html_link_cancel)) }
        }
    )
}

/**
 * Tells the reader remote content was blocked and lets them load it for this message only. The
 * wording follows the [banner] kind: a plain notice under the "never" policy, a question under
 * "ask"; neither loads anything on its own.
 */
@Composable
fun RemoteContentBanner(banner: RemoteBanner, onShow: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(
                if (banner ==
                    RemoteBanner.ASK
                ) {
                    R.string.html_remote_ask
                } else {
                    R.string.html_remote_blocked
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.html_remote_show))
        }
    }
}

/** Switches a message between the dark theme's colours and the ones the sender chose. */
@Composable
fun OriginalColorsToggle(
    viewOriginal: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onToggle,
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp)
    ) {
        Text(
            stringResource(
                if (viewOriginal) R.string.html_colors_adapted else R.string.html_colors_original
            )
        )
    }
}
