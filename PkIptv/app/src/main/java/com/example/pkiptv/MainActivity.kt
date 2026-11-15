package com.example.pkiptv

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
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
    private lateinit var status: TextView
    private var channels = listOf<Channel>()

    private val defaultUa =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val playlistUrls = listOf(
        "https://iptv-org.github.io/iptv/countries/pk.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/pk.m3u"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                val detail = if (http != null) "HTTP ${http.responseCode}" else error.errorCodeName
                showStatus("Can't play this channel ($detail). Try another one.")
            }
        })

        val list = findViewById<ListView>(R.id.channelList)
        showStatus("Loading channel list...")
        thread {
            var loaded = emptyList<Channel>()
            for (u in playlistUrls) {
                try {
                    loaded = parseM3u(URL(u).readText())
                    if (loaded.isNotEmpty()) break
                } catch (e: Exception) { /* try next */ }
            }
            channels = loaded
            runOnUiThread {
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    channels.map { it.name })
                if (channels.isEmpty()) showStatus("Could not load the channel list. Check the TV's internet.")
                else status.visibility = View.GONE
                list.requestFocus()
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> play(channels[pos]) }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun play(c: Channel) {
        showStatus("Loading ${c.name}...")
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent(c.userAgent ?: defaultUa)
        c.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(http)
            .createMediaSource(MediaItem.fromUri(c.url))
        player.setMediaSource(source)
        player.prepare()
        player.play()
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

    override fun onDestroy() { super.onDestroy(); player.release() }
}
