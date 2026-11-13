package com.deskmail.ui.mail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.deskmail.R
import com.deskmail.deskMail
import com.deskmail.mail.MailAttachment
import com.deskmail.mail.MailContent
import com.deskmail.settings.WebLayer
import desktopkit.theme.DeskTheme
import desktopkit.theme.GlassMode
import desktopkit.theme.LocalGlassMode
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToInt

/**
 * A message's body in a WebView, as tall as the message: the reader scrolls it together with the
 * header, under the phone's bars.
 *
 * The WebView runs no scripts, reads no files or content providers and loads nothing from the
 * network, until the reader asks to see the message's images ([showImages]): then pictures are
 * fetched over https, and nothing else. A tapped link is handed to [onLink] and never followed
 * inside the view. The page is light or dark with the kit's theme, not the system's: the view is
 * created with a context whose theme says which, and created anew when the theme changes. With
 * [darken] off, a dark theme shows the message on white, as its sender made it.
 *
 * Under the kit's blurred glass the view draws into a hardware layer of its own ([webLayerFor]
 * says why), unless the message is too tall for one: [onTall] is told when that is so and when it
 * no longer is, and the app turns the glass off for that message ([shellGlass]). [layer] lets an
 * experiment say otherwise.
 *
 * Where the device has no usable WebView (it is being updated, or disabled), the message's plain
 * text is shown instead.
 *
 * The pictures the message shows in its own place ("cid:…", [MailContent.Ready.pictures]) are the
 * message's own parts: [picture] has one on the device (fetching it from the server if it must)
 * when the page asks for it. It is called on a thread of the WebView's that may wait.
 */
@Composable
fun WebMessageBody(
    content: MailContent.Ready,
    modifier: Modifier = Modifier,
    showImages: Boolean = false,
    darken: Boolean = true,
    layer: WebLayer = WebLayer.Default,
    onLink: (String) -> Unit = {},
    onTall: (Boolean) -> Unit = {},
    picture: (MailAttachment) -> File? = { null },
) {
    val context = LocalContext.current
    val dark = DeskTheme.colors.isDark
    val latestOnLink by rememberUpdatedState(onLink)
    val latestPictures by rememberUpdatedState(content.pictures)
    val latestPicture by rememberUpdatedState(picture)
    val web = remember(context, dark) {
        val themed = ContextThemeWrapper(context, if (dark) R.style.DeskMailWebDark else R.style.DeskMailWebLight)
        runCatching {
            createMessageWebView(themed, onLink = { url -> latestOnLink(url) }) { name ->
                val part = pictureNamed(latestPictures, name)
                part?.let { latestPicture(it) }?.let { file -> part to file }
            }
        }
            .onSuccess {
                logWebViewOnce(context)
                // the device's WebView is going now: nothing is left to warm up
                context.deskMail.webWarmUp.made()
            }
            .onFailure { context.deskMail.log.warn("reader", "no usable WebView: showing the plain text", it) }
            .getOrNull()
    }
    if (web == null) {
        PlainMessageBody(content, modifier)
        return
    }
    // what the shell's bars are made of right now: blurred glass draws what is under it again
    val blurred = LocalGlassMode.current == GlassMode.Blur
    // the view's size as it is laid out; the page's height is known once it has loaded
    var size by remember(web) { mutableStateOf(IntSize.Zero) }
    val tall = !LayerLimits.fits(size.width, size.height)
    val layerType = webLayerFor(layer, blurred, tall)
    val latestLayer by rememberUpdatedState(layer)
    val latestBlurred by rememberUpdatedState(blurred)
    DisposableEffect(web) {
        val listener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val laidOut = IntSize(right - left, bottom - top)
            // A layer the page has outgrown is given up before it is drawn once: the composition
            // would only get to it a frame later, with a layer of that size already made.
            val fitting = webLayerFor(latestLayer, latestBlurred, tall = !LayerLimits.fits(laidOut.width, laidOut.height))
            if (view.layerType != fitting) view.setLayerType(fitting, null)
            size = laidOut
        }
        web.addOnLayoutChangeListener(listener)
        onDispose {
            web.removeOnLayoutChangeListener(listener)
            web.destroy()
        }
    }
    val latestOnTall by rememberUpdatedState(onTall)
    LaunchedEffect(tall) { latestOnTall(tall) }
    // What the view draws into, in the log once its size has settled: the first thing to know when
    // a message flickers or a part of it stays empty.
    val log = context.deskMail.log
    LaunchedEffect(web, layerType, blurred, size) {
        if (size.height == 0) return@LaunchedEffect
        delay(SETTLED_MS)
        log.info("reader", "WebView ${size.width} x ${size.height} px: ${layerName(layerType)}, glass ${if (blurred) "blurred" else "not blurred"}" + if (tall) ", too tall for a layer" else "")
    }
    val document = remember(content.html, content.text, showImages) { MessageDocument.build(content.html, content.text, remoteImages = showImages) }
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
                if (view.layerType != layerType) view.setLayerType(layerType, null)
                // The network stays shut until the reader asks for the pictures. Then the page's own
                // policy lets pictures in over https and still nothing else (MessageDocument).
                view.settings.blockNetworkLoads = !showImages
                view.settings.blockNetworkImage = !showImages
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/**
 * What a message's WebView draws into ([View.LAYER_TYPE_NONE] and its siblings).
 *
 * The kit's glass blurs what is under a bar by drawing the screen's content again, smaller, once
 * for each glass surface, in every frame. A WebView that draws straight into the window is drawn
 * again with it, each time for another area and at another size, and it cannot serve several of
 * those at once: it makes each of its frames for the one area it last heard of. Scrolled under
 * the bars, a message came and went tile by tile, many times a second (Galaxy S24,
 * 0.1.0-alpha.1, seen frame by frame in a screen recording; docs/KIT-ISTEKLERI.md, 20).
 *
 * In a hardware layer of its own the WebView draws once, for the whole of itself, and the window
 * and every blur take the finished picture from that layer. So that is what it draws into while
 * the glass blurs ([blurred]). Without blur a layer would only cost memory: then there is none.
 * A message too tall for a layer ([tall], [LayerLimits]) has none either, and no glass
 * ([shellGlass]).
 */
fun webLayerFor(setting: WebLayer, blurred: Boolean, tall: Boolean): Int = when (setting) {
    WebLayer.Default -> if (blurred && !tall) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
    WebLayer.None -> View.LAYER_TYPE_NONE
    WebLayer.Hardware -> View.LAYER_TYPE_HARDWARE
    WebLayer.Software -> View.LAYER_TYPE_SOFTWARE
}

/** A size that has not changed for this long is the page's own, not a step on the way to it. */
private const val SETTLED_MS = 500L

private fun layerName(type: Int) = when (type) {
    View.LAYER_TYPE_HARDWARE -> "hardware layer"
    View.LAYER_TYPE_SOFTWARE -> "software layer"
    else -> "no layer"
}

/**
 * How large a view may be and still draw into a hardware layer of its own.
 *
 * A layer is one texture: no side of it can be longer than the device's graphics allow
 * ([maxSide]; a view that is, is silently drawn without its layer), and it takes four bytes for
 * every pixel of the view, the part off the screen included. A message a few screens long is
 * some tens of megabytes; [MAX_BYTES] is where that stops being worth the glass over it.
 */
object LayerLimits {
    /** What a layer may take: a message some six screens long, on the phone and in the tablet's reading pane alike. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** The longest side a texture can safely be taken to have before the device has said ([note]): its first hardware canvas knows. */
    const val SAFE_SIDE = 8192

    /** The longest side of a texture on this device. */
    var maxSide by mutableIntStateOf(SAFE_SIDE)
        private set

    /** Told by a view that is being drawn what its canvas says the largest bitmap is. */
    fun note(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        val side = minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
        // the device's word is taken, unless it is absurd
        if (side in MIN_SIDE..MAX_SIDE && side != maxSide) maxSide = side
    }

    /** Whether a view of [width] by [height] pixels can have a layer of its own. A view not laid out yet fits. */
    fun fits(width: Int, height: Int, maxSide: Int = this.maxSide): Boolean =
        width <= maxSide && height <= maxSide && width.toLong() * height * BYTES_PER_PIXEL <= MAX_BYTES

    private const val MIN_SIDE = 2048
    private const val MAX_SIDE = 32768
    private const val BYTES_PER_PIXEL = 4
}

/**
 * Says, for as long as it is composed, that this window draws a message with a WebView, and
 * whether that message is too [tall] for a layer of its own ([MailUiState.webBodies],
 * [MailUiState.tallWebBodies]). The app puts it beside every [WebMessageBody].
 */
@Composable
fun WebBodyPresence(ui: MailUiState, tall: Boolean = false) {
    DisposableEffect(ui, tall) {
        ui.webBodyShown(tall)
        onDispose { ui.webBodyGone(tall) }
    }
}

/**
 * Whether the shell's bars, sheets and menus are the kit's blurred glass.
 *
 * Where the user wants glass ([wanted]) it is there, over a message too ([overMessages]): the
 * message's WebView draws into a layer of its own then, which the glass can blur
 * ([webLayerFor]). Only a message too tall for such a layer takes the glass away, for as long as
 * it is on screen ([tallBodies]).
 *
 * With [overMessages] off the glass is off for every message, as in 0.1.0-alpha.2: from the tap
 * that opens one ([messageOpen]), not a frame into the slide that brings its page in, until its
 * WebView has left the screen ([webBodies]), not while its page is still sliding out.
 */
fun shellGlass(wanted: Boolean, overMessages: Boolean, messageOpen: Boolean, webBodies: Int, tallBodies: Int): Boolean = when {
    !wanted -> false
    overMessages -> tallBodies == 0
    else -> !messageOpen && webBodies == 0
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
fun PlainMessageBody(content: MailContent.Ready, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val style = DeskTheme.type.body
    val widest = with(LocalDensity.current) { (style.fontSize * PLAIN_TEXT_EMS).toDp() }
    // A box of the reader's width around the text: handed that width directly, the text would be
    // held to it as its least width, and the limit below would count for nothing.
    Box(modifier) {
        SelectionContainer {
            Text(
                content.text,
                style = style,
                color = DeskTheme.colors.text,
                modifier = Modifier
                    .padding(start = dimens.pagePadding, end = dimens.pagePadding, bottom = dimens.gapXl)
                    .widthIn(max = widest),
            )
        }
    }
}

/** The longest line of a plain-text message, in ems: some eighty letters. The WebView's page has the same limit. */
internal const val PLAIN_TEXT_EMS = 42

/**
 * A WebView for mail: everything a message does not need is off. [context]'s theme decides whether
 * the page is light or dark (android:isLightTheme).
 */
internal fun createMessageWebView(
    context: Context,
    onLink: (String) -> Unit,
    picture: (String) -> Pair<MailAttachment, File>? = { null },
): WebView = MessageWebView(context).apply {
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

        // A picture of the message's own ("cid:…") is handed over from the device; nothing else is
        // answered here, and the page's policy and the blocked network keep everything else out.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (!url.scheme.equals("cid", ignoreCase = true)) return null
            val found = try {
                picture(Uri.decode(url.schemeSpecificPart.orEmpty()))
            } catch (e: Exception) {
                null
            }
            if (found == null) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            val (part, file) = found
            return WebResourceResponse(part.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }, null, FileInputStream(file))
        }
    }
}

/**
 * The picture a message's HTML asks for by [name] ("cid:" and what follows it, decoded): the
 * part with that Content-ID, angle brackets or not, with the case the sender's program wrote or
 * another (some write the reference and the header differently).
 */
internal fun pictureNamed(pictures: Map<String, MailAttachment>, name: String): MailAttachment? {
    val id = name.trim().removePrefix("<").removeSuffix(">")
    if (id.isEmpty()) return null
    return pictures[id] ?: pictures.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
}

/** The WebView a message is shown in. Being drawn, it tells [LayerLimits] what the device's graphics allow. */
private class MessageWebView(context: Context) : WebView(context) {
    override fun onDraw(canvas: Canvas) {
        LayerLimits.note(canvas)
        super.onDraw(canvas)
    }
}
