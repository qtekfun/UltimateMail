// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.BodyFailure
import com.qtekfun.ultimatemail.domain.conversation.BodyView
import com.qtekfun.ultimatemail.domain.conversation.MessageView
import com.qtekfun.ultimatemail.domain.conversation.RenderedBody
import com.qtekfun.ultimatemail.domain.conversation.TextRun
import com.qtekfun.ultimatemail.domain.html.MailDarkMode
import com.qtekfun.ultimatemail.ui.html.FileCidResolver
import com.qtekfun.ultimatemail.ui.html.LinkConfirmationDialog
import com.qtekfun.ultimatemail.ui.html.OriginalColorsToggle
import com.qtekfun.ultimatemail.ui.html.PendingLink
import com.qtekfun.ultimatemail.ui.html.RemoteContentBanner
import com.qtekfun.ultimatemail.ui.html.SafeHtmlView
import com.qtekfun.ultimatemail.ui.html.openExternally

private val MinTouchTarget = 48.dp
private const val HALF = 0.5f

/** What the body area of an open message needs to do. */
data class BodyActions(
    val onRetry: () -> Unit,
    val onToggleQuoted: () -> Unit,
    val onAllowRemote: () -> Unit,
    val onToggleOriginalColors: () -> Unit
)

/**
 * The body of an open message: loading, the reason it cannot be read, or the text (HTML in the
 * locked-down web view, plain text with confirmed links). Every link, in HTML or text, is
 * confirmed with the same dialog before it is opened.
 */
@Composable
fun MessageBodyContent(message: MessageView, actions: BodyActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pendingLink by remember { mutableStateOf<PendingLink?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        when (val body = message.body) {
            null -> Unit

            BodyView.Loading -> LoadingBody()

            is BodyView.Failed -> FailedBody(body, actions.onRetry)

            is BodyView.Ready -> ReadyBody(
                message = message,
                body = body,
                actions = actions,
                onLink = { target, deceptive -> pendingLink = PendingLink(target, deceptive) }
            )
        }
    }
    pendingLink?.let { link ->
        LinkConfirmationDialog(
            link = link,
            onConfirm = {
                openExternally(context, link.target)
                pendingLink = null
            },
            onDismiss = { pendingLink = null }
        )
    }
}

@Composable
private fun LoadingBody() {
    val description = stringResource(R.string.body_loading)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedBody(body: BodyView.Failed, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (body.reason == BodyFailure.OFFLINE) {
            Text(
                text = stringResource(R.string.body_offline_title),
                style = MaterialTheme.typography.titleSmall
            )
        }
        Text(
            text = stringResource(body.reason.message()),
            style = MaterialTheme.typography.bodyMedium
        )
        // What the device kept of the message: the preview that came with its headers.
        if (body.preview.isNotBlank()) {
            Text(
                text = body.preview.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (body.reason != BodyFailure.GONE) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.body_retry))
            }
        }
    }
}

private fun BodyFailure.message(): Int = when (this) {
    BodyFailure.OFFLINE -> R.string.body_offline_body
    BodyFailure.SIGN_IN -> R.string.body_sign_in
    BodyFailure.GONE -> R.string.body_gone
    BodyFailure.OTHER -> R.string.body_error
}

@Composable
private fun ReadyBody(
    message: MessageView,
    body: BodyView.Ready,
    actions: BodyActions,
    onLink: (target: String, deceptive: Boolean) -> Unit
) {
    body.remoteBanner?.let { RemoteContentBanner(it, onShow = actions.onAllowRemote) }
    when (val rendered = body.rendered) {
        is RenderedBody.Html -> {
            // A new web view when images of the message arrive, since it reads them once.
            key(message.cidFiles.keys) {
                val resolver = remember(message.cidFiles) { FileCidResolver(message.cidFiles) }
                val appDark = MaterialTheme.colorScheme.background.luminance() < HALF
                val mode = MailDarkMode.decide(
                    appDark = appDark,
                    viewOriginal = body.originalColors,
                    declaresDark = remember(rendered.sanitized) {
                        MailDarkMode.declaresDarkScheme(rendered.sanitized.html)
                    },
                    canDarken = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                )
                SafeHtmlView(
                    content = rendered.sanitized,
                    allowRemoteContent = body.remoteAllowed,
                    onLinkClicked = onLink,
                    modifier = Modifier.fillMaxWidth(),
                    colors = mode,
                    cidResolver = resolver
                )
                if (appDark && MailDarkMode.canToggleOriginal(mode, body.originalColors)) {
                    OriginalColorsToggle(body.originalColors, actions.onToggleOriginalColors)
                }
            }
        }

        is RenderedBody.Text -> PlainTextBody(
            runs = rendered.runs,
            onLink = { target -> onLink(target, false) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        RenderedBody.Empty -> Text(
            text = stringResource(R.string.body_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
    if (body.body.hasQuote) {
        TextButton(
            onClick = actions.onToggleQuoted,
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                stringResource(
                    if (body.quotedShown) R.string.body_hide_quoted else R.string.body_show_quoted
                )
            )
        }
    }
}

/** Plain text with its links; a tap on one goes to [onLink], never straight to the browser. */
@Composable
fun PlainTextBody(runs: List<TextRun>, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onLink)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(runs, linkColor) {
        buildAnnotatedString {
            runs.forEach { run ->
                val target = run.target
                if (target == null) {
                    append(run.text)
                } else {
                    val link = LinkAnnotation.Clickable(
                        tag = target,
                        styles = TextLinkStyles(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        )
                    ) { latest(target) }
                    withLink(link) { append(run.text) }
                }
            }
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}
