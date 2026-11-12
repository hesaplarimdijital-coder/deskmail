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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.deskmail.R
import com.deskmail.deskMail
import com.deskmail.mail.MailMessage
import com.deskmail.settings.WebLayer
import desktopkit.theme.DeskTheme
import kotlin.math.roundToInt

/**
 * A message's body in a WebView, as tall as the message: the reader scrolls it together with the
 * header, under the phone's bars.
 *
 * The WebView runs no scripts, reads no files or content providers and loads nothing from the
 * network. A tapped link is handed to [onLink] and never followed inside the view. The page is
 * light or dark with the kit's theme, not the system's: the view is created with a context whose
 * theme says which, and created anew when the theme changes. With [darken] off, a dark theme shows
 * the message on white, as its sender made it. [layer] is an experiment: what the view draws into.
 *
 * Where the device has no usable WebView (it is being updated, or disabled), the message's plain
 * text is shown instead.
 */
@Composable
fun WebMessageBody(
    message: MailMessage,
    modifier: Modifier = Modifier,
    darken: Boolean = true,
    layer: WebLayer = WebLayer.Default,
    onLink: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val dark = DeskTheme.colors.isDark
    val latestOnLink by rememberUpdatedState(onLink)
    val web = remember(context, dark) {
        val themed = ContextThemeWrapper(context, if (dark) R.style.DeskMailWebDark else R.style.DeskMailWebLight)
        runCatching { createMessageWebView(themed) { url -> latestOnLink(url) } }
            .onSuccess { logWebViewOnce(context) }
            .onFailure { context.deskMail.log.warn("reader", "no usable WebView: showing the plain text", it) }
            .getOrNull()
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
                // on a dark theme the view darkens a page that brings no dark style of its own
                view.settings.isAlgorithmicDarkeningAllowed = darken
                // the kit's background shows through wherever the message paints none; a message kept
                // light on a dark theme gets the white page it was written for
                view.setBackgroundColor(if (dark && !darken) Color.WHITE else Color.TRANSPARENT)
                val layerType = when (layer) {
                    WebLayer.Default -> View.LAYER_TYPE_NONE
                    WebLayer.Hardware -> View.LAYER_TYPE_HARDWARE
                    WebLayer.Software -> View.LAYER_TYPE_SOFTWARE
                }
                if (view.layerType != layerType) view.setLayerType(layerType, null)
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** Notes in the log, once per run, which WebView draws the mail: the first thing to know when a message looks wrong. */
private fun logWebViewOnce(context: Context) {
    if (webViewLogged) return
    webViewLogged = true
    val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
    context.deskMail.log.info("reader", "WebView: " + (provider?.let { it.packageName + " " + it.versionName } ?: "unknown"))
}

private var webViewLogged = false

/**
 * A message's plain text: where there is no WebView to draw the HTML (and in the screenshot tests).
 * Its lines end where a line still reads well ([PLAIN_TEXT_EMS]), however wide the reader is.
 */
@Composable
fun PlainMessageBody(message: MailMessage, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val style = DeskTheme.type.body
    val widest = with(LocalDensity.current) { (style.fontSize * PLAIN_TEXT_EMS).toDp() }
    SelectionContainer(modifier) {
        Text(
            message.text,
            style = style,
            color = DeskTheme.colors.text,
            modifier = Modifier
                .padding(start = dimens.pagePadding, end = dimens.pagePadding, bottom = dimens.gapXl)
                .widthIn(max = widest),
        )
    }
}

/** The longest line of a plain-text message, in ems: some eighty letters. The WebView's page has the same limit. */
internal const val PLAIN_TEXT_EMS = 42

/**
 * A WebView for mail: everything a message does not need is off. [context]'s theme decides whether
 * the page is light or dark (android:isLightTheme).
 */
internal fun createMessageWebView(context: Context, onLink: (String) -> Unit): WebView = WebView(context).apply {
    // as tall as its content: the reader scrolls, not the view
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onLink(request.url.toString())
            return true
        }
    }
}
