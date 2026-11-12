package com.deskmail.ui.mail

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.deskmail.R
import com.deskmail.mail.MailMessage
import desktopkit.theme.DeskTheme
import kotlin.math.roundToInt

/**
 * A message's body in a WebView, as tall as the message: the reader scrolls it together with the
 * header, under the phone's bars.
 *
 * The WebView runs no scripts, reads no files or content providers and loads nothing from the
 * network. A tapped link is handed to [onLink] and never followed inside the view. The page is
 * light or dark with the kit's theme, not the system's: the view is created with a context whose
 * theme says which, and created anew when the theme changes.
 *
 * Where the device has no usable WebView (it is being updated, or disabled), the message's plain
 * text is shown instead.
 */
@Composable
fun WebMessageBody(message: MailMessage, modifier: Modifier = Modifier, onLink: (String) -> Unit = {}) {
    val context = LocalContext.current
    val dark = DeskTheme.colors.isDark
    val latestOnLink by rememberUpdatedState(onLink)
    val web = remember(context, dark) {
        val themed = ContextThemeWrapper(context, if (dark) R.style.DeskMailWebDark else R.style.DeskMailWebLight)
        runCatching { createMessageWebView(themed) { url -> latestOnLink(url) } }.getOrNull()
    }
    if (web == null) {
        PlainMessageBody(message, modifier)
        return
    }
    DisposableEffect(web) {
        onDispose { web.destroy() }
    }
    val document = remember(message.html, message.text) { MessageDocument.build(message.html, message.text) }
    // the system's font size setting reaches the page too
    val textZoom = (LocalConfiguration.current.fontScale * 100).roundToInt()
    // one node per view: a view made for another theme takes the old one's place
    key(web) {
        AndroidView(
            factory = { web },
            modifier = modifier,
            update = { view ->
                view.settings.textZoom = textZoom
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** A message's plain text: where there is no WebView to draw the HTML (and in the screenshot tests). */
@Composable
fun PlainMessageBody(message: MailMessage, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    SelectionContainer(modifier) {
        Text(
            message.text,
            style = DeskTheme.type.body,
            color = DeskTheme.colors.text,
            modifier = Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, bottom = dimens.gapXl),
        )
    }
}

/**
 * A WebView for mail: everything a message does not need is off. [context]'s theme decides whether
 * the page is light or dark (android:isLightTheme).
 */
internal fun createMessageWebView(context: Context, onLink: (String) -> Unit): WebView = WebView(context).apply {
    // as tall as its content: the reader scrolls, not the view
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    // the kit's background shows through wherever the message paints none
    setBackgroundColor(Color.TRANSPARENT)
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        domStorageEnabled = false
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // no lookups at Google for the pages of a mail client that loads nothing
        safeBrowsingEnabled = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        setSupportMultipleWindows(false)
        // on a dark theme the view darkens a page that brings no dark style of its own
        isAlgorithmicDarkeningAllowed = true
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onLink(request.url.toString())
            return true
        }
    }
}
