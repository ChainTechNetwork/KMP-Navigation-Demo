package com.example.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.WebKit.*
import platform.darwin.NSObject

/**
 * A wrapper around the Android View WebView to provide a basic WebView composable.
 *
 * If you require more customisation you are most likely better rolling your own and using this
 * wrapper as an example.
 *
 * The WebView attempts to set the layoutParams based on the Compose modifier passed in. If it
 * is incorrectly sizing, use the layoutParams composable function instead.
 *
 * @param state The webview state holder where the Uri to load is defined.
 * @param modifier A compose modifier
 * @param captureBackPresses Set to true to have this Composable capture back presses and navigate
 * the WebView back.
 * @param navigator An optional navigator object that can be used to control the WebView's
 * navigation from outside the composable.
 * @param onCreated Called when the WebView is first created, this can be used to set additional
 * settings on the WebView. WebChromeClient and WebViewClient should not be set here as they will be
 * subsequently overwritten after this lambda is called.
 * @param onDispose Called when the WebView is destroyed. Provides a bundle which can be saved
 * if you need to save and restore state in this WebView.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun WebView(
    state: WebViewState,
    modifier: Modifier,
    captureBackPresses: Boolean,
    navigator: WebViewNavigator,
    onCreated: () -> Unit,
    onDispose: () -> Unit,
) {
    val webView = state.webView

    webView?.let { wv ->
        LaunchedEffect(wv, navigator) {
            navigator.handleNavigationEvents(wv)
        }

        LaunchedEffect(wv, state) {
            snapshotFlow { state.content }.collect { content ->
                when (content) {
                    is WebContent.Url -> {
                        val url = NSURL(string = content.url)
                        val urlRequest = NSMutableURLRequest()
                        urlRequest.setURL(url)
                        content.additionalHttpHeaders.forEach { (key, value) ->
                            urlRequest.setValue(value = value, forHTTPHeaderField = key)
                        }
                        wv.loadRequest(urlRequest)
                        wv.allowsBackForwardNavigationGestures = true
                    }

                    is WebContent.Data -> {
                        if (content.baseUrl == null) return@collect
                        val data = NSString.create(string = content.data)
                            .dataUsingEncoding(NSUTF8StringEncoding) ?: return@collect
                        val baseUrl = NSURL(string = content.baseUrl)

                        wv.loadData(
                            data, content.mimeType ?: "text/html", content.encoding, baseUrl
                        )
                    }

                    is WebContent.NavigatorOnly -> {
                        // NO-OP
                    }

                    else -> {}
                }
            }
        }
    }

    UIKitView(
        factory = {
            println("onCreated")
            WKWebView().apply {
                onCreated()
                setUserInteractionEnabled(captureBackPresses)
                state.webView = this
                navigationDelegate = state
            }
        },
        onRelease = {
            println("onRelease")
            onDispose()
            state.webView = null
        },
        modifier = modifier,
    )
}

/**
 * A state holder to hold the state for the WebView. In most cases this will be remembered
 * using the rememberWebViewState(uri) function.
 */
@Stable
actual class WebViewState actual constructor(
    webContent: WebContent
) : NSObject(), WKNavigationDelegateProtocol {
    actual var lastLoadedUrl: String? by mutableStateOf(null)
        internal set

    /**
     *  The content being loaded by the WebView
     */
    actual var content: WebContent by mutableStateOf(webContent)

    /**
     * Whether the WebView is currently [LoadingState.Loading] data in its main frame (along with
     * progress) or the data loading has [LoadingState.Finished]. See [LoadingState]
     */
    actual var loadingState: LoadingState by mutableStateOf(LoadingState.Initializing)
        internal set

    /**
     * Whether the webview is currently loading data in its main frame
     */
    actual val isLoading: Boolean
        get() = loadingState !is LoadingState.Finished

    /**
     * The title received from the loaded content of the current page
     */
    actual var pageTitle: String? by mutableStateOf(null)
        internal set

    // We need access to this in the state saver. An internal DisposableEffect or AndroidView
    // onDestroy is called after the state saver and so can't be used.
    internal var webView by mutableStateOf<WKWebView?>(null)

    @Suppress("CONFLICTING_OVERLOADS")
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        loadingState = LoadingState.Finished
    }

    @Suppress("CONFLICTING_OVERLOADS")
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        loadingState = LoadingState.Loading(webView.estimatedProgress.toFloat())
    }
}

// Use Dispatchers.Main to ensure that the webview methods are called on UI thread
@OptIn(BetaInteropApi::class)
internal suspend fun WebViewNavigator.handleNavigationEvents(
    webView: WKWebView
): Nothing = withContext(Dispatchers.Main) {
    navigationEvents.collect { event ->
        when (event) {
            is WebViewNavigator.NavigationEvent.Back -> webView.goBack()
            is WebViewNavigator.NavigationEvent.Forward -> webView.goForward()
            is WebViewNavigator.NavigationEvent.Reload -> webView.reload()
            is WebViewNavigator.NavigationEvent.StopLoading -> webView.stopLoading()
            is WebViewNavigator.NavigationEvent.LoadHtml -> {
                val data =
                    NSString.create(string = event.html).dataUsingEncoding(NSUTF8StringEncoding)
                        ?: return@collect
                val baseUrl = if (event.baseUrl != null) NSURL(string = event.baseUrl)
                else return@collect

                webView.loadData(
                    data, event.mimeType ?: "text/html", event.encoding ?: "utf-8", baseUrl
                )
            }

            is WebViewNavigator.NavigationEvent.LoadUrl -> {
                loadUrl(event.url, event.additionalHttpHeaders)
            }
        }
    }
}

actual val WebStateSaver: Saver<WebViewState, Any> = run {
    val pageTitleKey = "pagetitle"
    val lastLoadedUrlKey = "lastloaded"

    mapSaver(save = {
        mapOf(
            pageTitleKey to it.pageTitle,
            lastLoadedUrlKey to it.lastLoadedUrl,
        )
    }, restore = {
        WebViewState(WebContent.NavigatorOnly).apply {
            this.pageTitle = it[pageTitleKey] as String?
            this.lastLoadedUrl = it[lastLoadedUrlKey] as String?
        }
    })
}

@OptIn(ExperimentalForeignApi::class)
@Composable
fun IOSWebView(
) {
    val url = "https://www.remove.bg/upload"
    val webView = WKWebView()

    UIKitView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            webView
        },
        update = {
            it.loadRequest(
                request = NSURLRequest(
                    uRL = NSURL(
                        string = url
                    )
                )
            )
        }
    )

//    val webView = remember {
//        val config = WKWebViewConfiguration().apply {
//            allowsInlineMediaPlayback = true
//            preferences.also { preferences ->
//                // Allow file access from file URLs
//                preferences.javaScriptEnabled = true
//                preferences.setValue("TRUE", forKey = "allowFileAccessFromFileURLs")
//            }
//        }
//        val webView = WKWebView(
//            frame = CGRectZero.readValue(),
//            configuration = config
//        )
//        webView
//    }
//
//    DisposableEffect(webView) {
//        val loadRequest = NSURLRequest(
//            uRL = NSURL(string = "https://www.remove.bg/upload"),
//        )
//        webView.loadRequest(loadRequest)
//
//        onDispose {
//            // Dispose of the WebView when the Composable is removed
//            webView.navigationDelegate = null
//            webView.stopLoading()
//        }
//    }
//
//    // Return the UIKitView with the WebView as its content
//    UIKitView(
//        factory = {
//            println("onCreated")
//            webView
//        },
//        modifier = Modifier.fillMaxSize(),
//        onRelease = {
//            println("onRelease")
//        }
//    )
}

