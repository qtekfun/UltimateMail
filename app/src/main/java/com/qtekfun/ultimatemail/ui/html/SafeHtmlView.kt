// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatemail.domain.html.EmailDocument
import com.qtekfun.ultimatemail.domain.html.RequestPolicy
import com.qtekfun.ultimatemail.domain.html.SanitizedHtml
import java.io.ByteArrayInputStream

/**
 * Shows an already sanitized message in a locked-down WebView (SPEC RF-04).
 *
 * The WebView itself is hardened even though the HTML has been sanitized, so a gap in one layer
 * is not enough: JavaScript, file and content access, DOM storage, geolocation, mixed content and
 * multiple windows are off; the page carries a Content-Security-Policy; every request goes
 * through [RequestPolicy]; and with [allowRemoteContent] false the network is switched off for
 * the view altogether. Taps on links never navigate inside the view: they are handed to
 * [onLinkClicked] (target, and whether its text pretends to be another address) so the caller
 * can ask the reader to confirm before opening them with an intent.
 */
@Composable
fun SafeHtmlView(
    content: SanitizedHtml,
    allowRemoteContent: Boolean,
    onLinkClicked: (target: String, deceptive: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    cidResolver: CidResolver = CidResolver { null }
) {
    val page = remember(content, allowRemoteContent) {
        EmailDocument.wrap(content, allowRemoteContent)
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> lockedDownWebView(context) },
        update = { webView ->
            val client = webView.webViewClient as SafeWebViewClient
            client.allowRemoteContent = allowRemoteContent
            client.content = content
            client.onLinkClicked = onLinkClicked
            client.cidResolver = cidResolver
            // Without permission the view has no network at all, whatever the page asks for.
            webView.settings.blockNetworkLoads = !allowRemoteContent
            if (webView.tag != page) {
                webView.tag = page
                webView.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

private fun lockedDownWebView(context: Context): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        blockNetworkLoads = true
    }
    webViewClient = SafeWebViewClient()
    isHapticFeedbackEnabled = false
}

/** Blocks everything [RequestPolicy] does not allow and keeps link taps out of the page. */
private class SafeWebViewClient : WebViewClient() {
    var allowRemoteContent = false
    var content = SanitizedHtml("", 0, emptyList())
    var onLinkClicked: (String, Boolean) -> Unit = { _, _ -> }
    var cidResolver = CidResolver { null }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        return when {
            url.startsWith("cid:", ignoreCase = true) -> cidResolver.resolve(url) ?: blocked()
            RequestPolicy.allowsRequest(url, allowRemoteContent) -> null
            else -> blocked()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        RequestPolicy.externalLink(request.url.toString())?.let { target ->
            onLinkClicked(target, content.isDeceptiveTarget(target))
        }
        // The view never navigates by itself.
        return true
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail
    ): Boolean = true

    private fun blocked() = WebResourceResponse(
        "text/plain",
        "utf-8",
        FORBIDDEN,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private companion object {
        const val FORBIDDEN = 403
    }
}
