package com.idanck.shidur.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idanck.shidur.data.youtube.YtVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * YouTube's own player, the IFrame API in a WebView (ADR-061). The Android Player API is gone, and
 * YouTube's terms allow its embedded player only: played inside it, with YouTube's own controls and
 * branding, at least 200×200, nothing of ours drawn over it, one player playing per screen and
 * never in the background.
 *
 * The WebView takes no focus and no keys. The remote stays with Compose, which drives the player
 * through the API ([toggle], [seekBy]), so the D-pad never gets lost inside a web page.
 *
 * The page is loaded from `https://com.idanck.shidur/`, so the player's requests carry the app's id
 * as their Referer, as YouTube asks of an app that embeds it; without one it refuses to play
 * ("error 153").
 */
class YouTubePlayerController(context: Context) {

    enum class Phase { IDLE, LOADING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }

    data class State(
        val video: YtVideo? = null,
        val phase: Phase = Phase.IDLE,
        /** The player's error code (2, 5, 100, 101, 150, …) when [phase] is [Phase.ERROR]. */
        val error: Int? = null,
        val position: Int = 0,
        val duration: Int = 0,
    ) {
        /** The owner allows no embedding (101, 150), or the player refused this app (152, 153). */
        val notEmbeddable: Boolean get() = error == 101 || error == 150 || error == 152 || error == 153

        /** Removed, private, or a live stream that has ended and gone. */
        val unavailable: Boolean get() = error == 100

        /** A live stream that has come to its end while it played. */
        val liveEnded: Boolean get() = phase == Phase.ENDED && video?.live == true
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Commands given before the page has loaded the API, run once it has. */
    private var pageReady = false
    private val pending = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        setBackgroundColor(AndroidColor.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                pending.forEach { js -> view.evaluateJavascript(js, null) }
                pending.clear()
            }
        }
        addJavascriptInterface(Bridge(), "Shidur")
        loadDataWithBaseURL(BASE_URL, PAGE, "text/html", "utf-8", null)
    }

    /** Plays [video] from [startAt] seconds (ignored for a live stream). */
    fun load(video: YtVideo, startAt: Int = 0) {
        _state.value = State(video = video, phase = Phase.LOADING)
        val start = if (video.live) 0 else startAt.coerceAtLeast(0)
        run("load(${jsString(video.id)}, $start)")
    }

    fun play() = run("cmd('playVideo')")

    fun pause() = run("cmd('pauseVideo')")

    fun toggle() {
        when (_state.value.phase) {
            Phase.PLAYING, Phase.BUFFERING, Phase.LOADING -> pause()
            Phase.ENDED -> run("seekTo(0)")
            else -> play()
        }
    }

    /** Jumps [seconds] forward or back; not on a live stream, whose timeline is YouTube's to keep. */
    fun seekBy(seconds: Int) {
        if (_state.value.video?.live == true) return
        run("seekBy($seconds)")
    }

    /** Stops and empties the player. */
    fun stop() {
        run("cmd('stopVideo')")
        _state.value = State()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface("Shidur")
            webView.destroy()
        }
        _state.value = State()
        playingNow.value = false
    }

    /** The page's JavaScript lifecycle with the activity's: no timers or playback while it is stopped. */
    fun onStop() {
        pause()
        webView.onPause()
    }

    fun onStart() = webView.onResume()

    private fun run(js: String) {
        main.post {
            if (pageReady) webView.evaluateJavascript(js, null) else pending += js
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onState(code: Int) {
            main.post {
                val phase = when (code) {
                    1 -> Phase.PLAYING
                    2 -> Phase.PAUSED
                    3 -> Phase.BUFFERING
                    0 -> Phase.ENDED
                    -1, 5 -> if (_state.value.phase == Phase.ERROR) Phase.ERROR else Phase.LOADING
                    else -> _state.value.phase
                }
                _state.value = _state.value.copy(phase = phase, error = if (phase == Phase.ERROR) _state.value.error else null)
                playingNow.value = phase == Phase.PLAYING || phase == Phase.BUFFERING
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            main.post {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = code)
                playingNow.value = false
            }
        }

        @JavascriptInterface
        fun onTime(position: Double, duration: Double) {
            main.post {
                _state.value = _state.value.copy(position = position.toInt(), duration = duration.toInt())
            }
        }
    }

    companion object {
        /** The app's id as an origin: the Referer YouTube asks an embedding app for. */
        private const val BASE_URL = "https://com.idanck.shidur/"
        private const val ORIGIN = "https://com.idanck.shidur"

        /**
         * Whether a YouTube player is playing anywhere in the app, for the screensaver, which never
         * comes up over one (ADR-040's still screens do not include a video being watched).
         */
        val playingNow = MutableStateFlow(false)

        private fun jsString(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

        private val PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;left:0;top:0;width:100%;height:100%}</style>
            </head><body><div id="p"></div>
            <script>
            var player=null, apiReady=false, wanted=null;
            function onYouTubeIframeAPIReady(){apiReady=true; if(wanted){var w=wanted; wanted=null; load(w.id,w.start);}}
            function load(id,start){
              if(!apiReady){wanted={id:id,start:start};return;}
              if(player&&player.loadVideoById){player.loadVideoById({videoId:id,startSeconds:start});return;}
              player=new YT.Player('p',{width:'100%',height:'100%',videoId:id,
                playerVars:{autoplay:1,controls:1,playsinline:1,rel:0,fs:0,iv_load_policy:3,start:start,origin:'$ORIGIN',enablejsapi:1},
                events:{
                  onReady:function(e){e.target.playVideo();},
                  onStateChange:function(e){Shidur.onState(e.data);},
                  onError:function(e){Shidur.onError(e.data);}
                }});
            }
            function cmd(name){if(player&&player[name])player[name]();}
            function seekTo(s){if(player&&player.seekTo){player.seekTo(s,true);player.playVideo();}}
            function seekBy(s){if(player&&player.getCurrentTime){player.seekTo(Math.max(0,player.getCurrentTime()+s),true);}}
            setInterval(function(){if(player&&player.getCurrentTime){Shidur.onTime(player.getCurrentTime(),player.getDuration()||0);}},5000);
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
            </body></html>
        """.trimIndent()
    }
}

/**
 * Where [controller]'s player is drawn. Only one of these may be composed at a time: the player is
 * one WebView, and it is moved here if another place had it.
 *
 * The player is paused when the activity stops: YouTube's terms allow no playing in the background.
 */
@Composable
fun YouTubePlayerView(
    controller: YouTubePlayerController,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onStop()
                Lifecycle.Event.ON_START -> controller.onStart()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = {
            val view = controller.webView
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
        modifier = modifier,
    )
}
