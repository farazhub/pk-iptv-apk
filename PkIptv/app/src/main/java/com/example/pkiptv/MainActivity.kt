package com.example.pkiptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

data class Channel(
    val name: String,
    val url: String,
    val referrer: String? = null,
    val userAgent: String? = null
)

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var list: ListView
    private lateinit var panel: View
    private lateinit var splash: View
    private lateinit var emptyHint: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var headerCount: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var statusBox: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusTech: TextView
    private lateinit var adapter: ChannelAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val hideNowPlaying = Runnable { nowPlaying.visibility = View.GONE }

    private var channels = listOf<Channel>()
    private var playingIndex = -1
    private var fullscreen = false
    private var playlistLoaded = false
    private var splashMinElapsed = false
    private var attempt = 0
    private var currentChannel: Channel? = null

    private val vlcUa = "VLC/3.0.20 LibVLC/3.0.20"

    private val defaultUa =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val playlistUrls = listOf(
        "https://iptv-org.github.io/iptv/countries/pk.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/pk.m3u"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trustAllCertificates()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        list = findViewById(R.id.channelList)
        panel = findViewById(R.id.panel)
        splash = findViewById(R.id.splash)
        emptyHint = findViewById(R.id.emptyHint)
        nowPlaying = findViewById(R.id.nowPlaying)
        headerCount = findViewById(R.id.headerCount)
        spinner = findViewById(R.id.spinner)
        statusBox = findViewById(R.id.statusBox)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusTech = findViewById(R.id.statusTech)

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> spinner.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        spinner.visibility = View.GONE
                        statusBox.visibility = View.GONE
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                spinner.visibility = View.GONE
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                val ch = currentChannel
                if (ch != null && isRetryable(error)) {
                    val total = identities(ch).size
                    if (attempt + 1 < total) {
                        attempt++
                        spinner.visibility = View.VISIBLE
                        showStatus(
                            "Trying another way to connect...",
                            "Attempt ${attempt + 1} of $total for ${ch.name}.",
                            false, null
                        )
                        startPlayback(ch)
                        return
                    }
                }
                val (title, detail) = describe(error)
                val hint = if (fullscreen) "Press Up/Down to try another channel, or Left to open the list."
                else "Pick another channel from the list."
                showStatus(title, "$detail $hint", true,
                    "Technical info: ${error.errorCodeName} (tried ${attempt + 1} connection method(s))")
            }
        })

        adapter = ChannelAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(pos) }

        handler.postDelayed({ splashMinElapsed = true; maybeHideSplash() }, 2500)
        handler.postDelayed({ splashMinElapsed = true; playlistLoaded = true; maybeHideSplash() }, 10000)
        loadPlaylist()
    }

    // ---------- playlist ----------

    private fun loadPlaylist() {
        playlistLoaded = false
        showStatus("Loading channels...", "Fetching the Pakistani channel list.", false, null)
        spinner.visibility = View.VISIBLE
        thread {
            var loaded = emptyList<Channel>()
            var networkFailed = false
            for (u in playlistUrls) {
                try {
                    val conn = URL(u).openConnection()
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    val text = conn.getInputStream().bufferedReader().use { it.readText() }
                    loaded = parseM3u(text)
                    if (loaded.isNotEmpty()) { networkFailed = false; break }
                } catch (e: Exception) {
                    networkFailed = true
                }
            }
            runOnUiThread {
                channels = loaded
                adapter.notifyDataSetChanged()
                spinner.visibility = View.GONE
                if (loaded.isEmpty()) {
                    headerCount.text = "No channels"
                    showStatus(
                        "Couldn't load the channel list",
                        if (networkFailed)
                            "The TV couldn't reach the playlist server. Check your internet connection (Settings > Network), then press OK to try again."
                        else
                            "The playlist came back empty. Press OK to try again.",
                        true, null
                    )
                } else {
                    headerCount.text = "${loaded.size} channels"
                    statusBox.visibility = View.GONE
                    list.requestFocus()
                }
                playlistLoaded = true
                maybeHideSplash()
            }
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var referrer: String? = null
        var ua: String? = null
        for (line in text.lines().map { it.trim() }) {
            when {
                line.startsWith("#EXTINF") -> name = line.substringAfterLast(",").trim()
                line.startsWith("#EXTVLCOPT:http-referrer=") ->
                    referrer = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=") ->
                    ua = line.substringAfter("=").trim()
                line.isNotEmpty() && !line.startsWith("#") && name != null -> {
                    out.add(Channel(name!!, line, referrer, ua))
                    name = null; referrer = null; ua = null
                }
            }
        }
        return out
    }

    // ---------- playback ----------

    private fun play(index: Int) {
        if (index < 0 || index >= channels.size) return
        val c = channels[index]
        playingIndex = index
        adapter.notifyDataSetChanged()
        emptyHint.visibility = View.GONE
        showStatus("Connecting to ${c.name}...", "Please wait a moment.", false, null)
        spinner.visibility = View.VISIBLE
        showNowPlaying(c.name)

        currentChannel = c
        attempt = 0
        startPlayback(c)
    }

    // Different servers accept different "identities". Try the most compatible first.
    private fun identities(c: Channel): List<Pair<String, String?>> {
        val origin = try {
            val u = URL(c.url); "${u.protocol}://${u.host}/"
        } catch (e: Exception) { null }
        val out = mutableListOf<Pair<String, String?>>()
        if (c.userAgent != null || c.referrer != null) out.add(Pair(c.userAgent ?: vlcUa, c.referrer))
        out.add(Pair(vlcUa, null))
        out.add(Pair(defaultUa, origin))
        out.add(Pair(defaultUa, null))
        out.add(Pair("okhttp/4.12.0", null))
        return out
    }

    private fun isRetryable(e: PlaybackException): Boolean =
        e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    private fun startPlayback(c: Channel) {
        val ids = identities(c)
        val (ua, ref) = ids[attempt.coerceAtMost(ids.size - 1)]
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent(ua)
        ref?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(c.url))
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    private fun stepChannel(delta: Int) {
        if (channels.isEmpty()) return
        val next = if (playingIndex < 0) 0 else (playingIndex + delta + channels.size) % channels.size
        list.setSelection(next)
        play(next)
    }

    private fun showNowPlaying(name: String) {
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.text = "▶  $name"
        nowPlaying.visibility = View.VISIBLE
        handler.postDelayed(hideNowPlaying, 4000)
    }

    // ---------- friendly error messages ----------

    private fun describe(e: PlaybackException): Pair<String, String> {
        var ssl = false
        var dns = false
        var timeout = false
        var refused = false
        var http: HttpDataSource.InvalidResponseCodeException? = null
        var t: Throwable? = e.cause
        var depth = 0
        while (t != null && depth < 10) {
            when (t) {
                is HttpDataSource.InvalidResponseCodeException -> if (http == null) http = t
                is javax.net.ssl.SSLException -> ssl = true
                is java.net.UnknownHostException -> dns = true
                is java.net.SocketTimeoutException -> timeout = true
                is java.net.ConnectException -> refused = true
            }
            t = t.cause
            depth++
        }

        http?.let {
            val code = it.responseCode
            return when {
                code == 401 || code == 403 -> Pair(
                    "Access denied (HTTP $code)",
                    "The channel's server refused the request. It may be blocked in your region or require a login."
                )
                code == 404 || code == 410 -> Pair(
                    "Channel not found (HTTP $code)",
                    "This stream address no longer exists. The channel was probably taken offline or moved."
                )
                code == 429 -> Pair(
                    "Too many requests (HTTP 429)",
                    "The server is limiting connections. Wait a minute and try again."
                )
                code >= 500 -> Pair(
                    "Server problem (HTTP $code)",
                    "The channel's server is having trouble right now. Try again in a few minutes."
                )
                else -> Pair(
                    "Unexpected server reply (HTTP $code)",
                    "The server answered, but not with a playable stream."
                )
            }
        }
        if (ssl) return Pair(
            "Secure connection failed",
            "The TV couldn't establish a secure connection to this channel's server. Check the TV's date and time, or the channel may be misconfigured."
        )
        if (dns) return Pair(
            "Server not found",
            "The TV couldn't look up this channel's server. Check your internet or DNS settings, or the channel may have been removed."
        )
        if (timeout || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return Pair(
            "Server not responding",
            "The channel's server took too long to answer. It is probably offline or overloaded."
        )
        if (refused || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) return Pair(
            "Couldn't connect",
            "The connection was refused or dropped. The channel may be offline, or your internet connection may be down."
        )
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> Pair(
                "Insecure link blocked",
                "This channel uses an unencrypted link that the TV blocked."
            )
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> Pair(
                "Not a video stream",
                "The address returned a web page instead of video. The channel link is most likely dead."
            )
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Pair(
                "Stream not found",
                "The stream file doesn't exist on the server any more."
            )
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> Pair(
                "Broken stream",
                "The channel is sending damaged or invalid data. It may come back later."
            )
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> Pair(
                "Unsupported stream type",
                "This channel uses a stream format the player can't read."
            )
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> Pair(
                "Video format not supported",
                "This TV's decoder couldn't play this channel's video format."
            )
            PlaybackException.ERROR_CODE_TIMEOUT -> Pair(
                "Playback timed out",
                "The channel stopped responding. Try again or pick another channel."
            )
            else -> Pair(
                "Couldn't play this channel",
                "Something went wrong while starting playback. The channel may be offline."
            )
        }
    }

    private fun showStatus(title: String, detail: String, isError: Boolean, tech: String?) {
        statusTitle.text = title
        statusTitle.setTextColor(if (isError) 0xFFF87171.toInt() else 0xFFFFFFFF.toInt())
        statusDetail.text = detail
        if (tech == null) {
            statusTech.visibility = View.GONE
        } else {
            statusTech.text = tech
            statusTech.visibility = View.VISIBLE
        }
        statusBox.visibility = View.VISIBLE
    }

    // ---------- splash ----------

    private fun maybeHideSplash() {
        if (!(splashMinElapsed && playlistLoaded)) return
        if (splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(450).withEndAction {
            splash.visibility = View.GONE
            list.requestFocus()
        }.start()
    }

    // ---------- remote control ----------

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        panel.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) list.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (splash.visibility == View.VISIBLE) return true
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (channels.isEmpty() && playlistLoaded) { loadPlaylist(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!fullscreen && playingIndex >= 0) { setFullscreen(true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU ->
                    if (fullscreen) { setFullscreen(false); return true }
                KeyEvent.KEYCODE_BACK ->
                    if (fullscreen) { setFullscreen(false); return true }
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (fullscreen) { stepChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (fullscreen) { stepChannel(1); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- list adapter ----------

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val playing = position == playingIndex
            v.findViewById<TextView>(R.id.num).text = String.format(Locale.US, "%02d", position + 1)
            val name = v.findViewById<TextView>(R.id.name)
            name.text = channels[position].name
            name.setTextColor(if (playing) 0xFF60A5FA.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.marker).visibility =
                if (playing) View.VISIBLE else View.INVISIBLE
            return v
        }
    }

    // Many free IPTV hosts use expired or self-signed certificates. VLC lets you click
    // through the warning; Android refuses silently, so accept them here.
    private fun trustAllCertificates() {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        player.release()
    }
}
