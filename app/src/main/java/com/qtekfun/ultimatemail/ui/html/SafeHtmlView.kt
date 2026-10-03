// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatemail.domain.html.EmailDocument
import com.qtekfun.ultimatemail.domain.html.MailColorMode
import com.qtekfun.ultimatemail.domain.html.ReaderHeight
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
    colors: MailColorMode = MailColorMode.ORIGINAL,
    cidResolver: CidResolver = CidResolver { null }
) {
    val page = remember(content, allowRemoteContent, colors) {
        EmailDocument.wrap(content, allowRemoteContent, colors)
    }
    // The web view decides at creation whether its theme is dark, which is what lets it darken
    // the page, so a change of dark and light starts a new one.
    key(colors.algorithmicDarkening) {
        AndroidView(
            // The view is as tall as the message, so the thread around it does the scrolling:
            // drags that start on it scroll the thread (AndroidView forwards nested scrolls), and
            // it never takes focus (which would make the thread scroll to it, past the header).
            // It is clipped to its bounds: otherwise its surface painted over the header of the
            // message (seen on a device when a message was opened a second time).
            modifier = modifier
                .fillMaxWidth()
                .clipToBounds()
                .focusProperties { canFocus = false },
            factory = { context -> lockedDownWebView(context, colors.algorithmicDarkening) },
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
}

/** A web view as tall as its content, never zero and never beyond what layout can handle. */
private class MailWebView(context: Context) : WebView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, ReaderHeight.clamp(measuredHeight))
    }
}

@Suppress("SetJavaScriptEnabled")
private fun lockedDownWebView(context: Context, dark: Boolean): WebView {
    // Chromium darkens a page only when the theme of the view's context is dark.
    val theme = if (dark) {
        android.R.style.Theme_Material_NoActionBar
    } else {
        android.R.style.Theme_Material_Light_NoActionBar
    }
    return MailWebView(ContextThemeWrapper(context, theme)).apply {
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
            // The viewport meta of the page is honoured, and a layout wider than the screen is
            // scaled down to fit instead of panned; zoom stays off so the height is stable.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = dark
            }
        }
        webViewClient = SafeWebViewClient()
        isHapticFeedbackEnabled = false
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
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
            RequestPolicy.isOwnDocument(url, request.isForMainFrame) -> null
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
