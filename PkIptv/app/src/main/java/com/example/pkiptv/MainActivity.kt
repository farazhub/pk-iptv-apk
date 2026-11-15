package com.example.pkiptv

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.URL
import kotlin.concurrent.thread

data class Channel(val name: String, val url: String)

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private var channels = listOf<Channel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
            }
        })

        val list = findViewById<ListView>(R.id.channelList)
        thread {
            try {
                val text = URL("https://iptv-org.github.io/iptv/countries/pk.m3u").readText()
                channels = parseM3u(text)
            } catch (e: Exception) { channels = emptyList() }
            runOnUiThread {
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    channels.map { it.name })
                if (channels.isEmpty())
                    Toast.makeText(this, "Could not load playlist", Toast.LENGTH_LONG).show()
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> play(channels[pos]) }
    }

    private fun play(c: Channel) {
        player.setMediaItem(MediaItem.fromUri(c.url))
        player.prepare()
        player.play()
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        for (line in text.lines().map { it.trim() }) {
            when {
                line.startsWith("#EXTINF") -> name = line.substringAfterLast(",").trim()
                line.isNotEmpty() && !line.startsWith("#") && name != null -> {
                    out.add(Channel(name!!, line)); name = null
                }
            }
        }
        return out
    }

    override fun onDestroy() { super.onDestroy(); player.release() }
}
