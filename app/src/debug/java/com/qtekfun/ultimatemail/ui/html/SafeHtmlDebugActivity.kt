// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.RemoteBanner
import com.qtekfun.ultimatemail.domain.html.HtmlSanitizer
import com.qtekfun.ultimatemail.domain.html.SanitizedHtml
import com.qtekfun.ultimatemail.ui.theme.UltimateMailTheme

/**
 * Debug builds only: opens the built-in sample messages (hostile, newsletter, links) in
 * [SafeHtmlView], with switches to allow remote content and to skip the sanitizer so the
 * WebView hardening can be checked on its own. It ignores every extra of its intent.
 */
class SafeHtmlDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UltimateMailTheme {
                Surface(modifier = Modifier.fillMaxSize()) { DebugScreen() }
            }
        }
    }
}

private class Sample(val label: Int, val html: String)

private val samples = listOf(
    Sample(R.string.debug_sample_hostile, DebugSamples.HOSTILE),
    Sample(R.string.debug_sample_newsletter, DebugSamples.NEWSLETTER),
    Sample(R.string.debug_sample_links, DebugSamples.LINKS)
)

@Composable
private fun DebugScreen() {
    val context = LocalContext.current
    val sanitizer = remember { HtmlSanitizer() }
    var selected by remember { mutableStateOf(0) }
    var allowRemote by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingLink?>(null) }
    val raw = samples[selected].html
    val content = remember(raw, allowRemote, bypass) {
        if (bypass) SanitizedHtml(raw, 0, emptyList()) else sanitizer.sanitize(raw, allowRemote)
    }
    Column(
        modifier = Modifier.safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            samples.forEachIndexed { index, sample ->
                FilterChip(
                    selected = index == selected,
                    onClick = { selected = index },
                    label = { Text(stringResource(sample.label)) }
                )
            }
        }
        ToggleRow(R.string.debug_allow_remote, allowRemote) { allowRemote = it }
        ToggleRow(R.string.debug_bypass_sanitizer, bypass) { bypass = it }
        if (content.hadBlockedRemoteContent && !allowRemote) {
            RemoteContentBanner(RemoteBanner.BLOCKED, onShow = { allowRemote = true })
        }
        SafeHtmlView(
            content = content,
            allowRemoteContent = allowRemote,
            onLinkClicked = { target, deceptive -> pending = PendingLink(target, deceptive) },
            modifier = Modifier.weight(1f)
        )
    }
    pending?.let { link ->
        LinkConfirmationDialog(
            link = link,
            onConfirm = {
                openExternally(context, link.target)
                pending = null
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}
