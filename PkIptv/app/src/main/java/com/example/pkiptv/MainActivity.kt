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
    val userAgent: String? = null,
    val country: String = "",
    val cats: Set<String> = emptySet(),
    @Volatile var state: Int = 0   // 0 = not checked, 1 = working, 2 = offline
)

class CategoryDef(
    val title: String,
    val isHeader: Boolean = false,
    val match: (Channel) -> Boolean = { false }
)

class Meta(val country: String, val cats: Set<String>, val bad: Boolean)

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var list: ListView
    private lateinit var panel: View
    private lateinit var splash: View
    private lateinit var emptyHint: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerCount: TextView
    private lateinit var footerHint: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var statusBox: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusTech: TextView
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val hideNowPlaying = Runnable { nowPlaying.visibility = View.GONE }

    private var master = listOf<Channel>()        // every channel in the world list
    private var all = listOf<Channel>()           // channels of the open category
    private var channels = listOf<Channel>()      // what the channel list shows now
    private var catRows = listOf<CategoryDef>()
    private val catCounts = HashMap<CategoryDef, Int>()
    private var currentCat: CategoryDef? = null
    private var lastCatPos = 1
    private var inCategories = true

    private var playingIndex = -1
    private var playingChannel: Channel? = null
    private var fullscreen = false
    private var playlistLoaded = false
    private var splashMinElapsed = false
    private var attempt = 0
    private var currentChannel: Channel? = null

    private var showOffline = false
    private var checkDone = false
    private var checkGen = 0
    private var executor: java.util.concurrent.ExecutorService? = null

    private val vlcUa = "VLC/3.0.20 LibVLC/3.0.20"
    private val defaultUa =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val indexUrl = "https://iptv-org.github.io/iptv/index.m3u"
    private val csvUrl = "https://raw.githubusercontent.com/iptv-org/database/master/data/channels.csv"
    private val pkUrl = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/pk.m3u"

    private val hollywood = setOf("us", "uk", "ca", "au")
    private val attrRe = Regex("([\\w-]+)=\"([^\"]*)\"")

    private fun has(c: Channel, vararg k: String): Boolean {
        for (x in k) if (x in c.cats) return true
        return false
    }

    private val categoryDefs = listOf(
        CategoryDef("PAKISTAN", true),
        CategoryDef("All Pakistani channels") { it.country == "pk" },
        CategoryDef("Pakistan: News") { it.country == "pk" && has(it, "news") },
        CategoryDef("Pakistan: Sports") { it.country == "pk" && has(it, "sports") },
        CategoryDef("Pakistan: Entertainment & Drama") {
            it.country == "pk" && has(it, "entertainment", "series", "comedy", "family", "classic")
        },
        CategoryDef("Pakistan: Movies & Music") { it.country == "pk" && has(it, "movies", "music") },
        CategoryDef("Pakistan: Religious") { it.country == "pk" && has(it, "religious") },
        CategoryDef("Pakistan: Kids") { it.country == "pk" && has(it, "kids", "animation") },
        CategoryDef("WORLD", true),
        CategoryDef("Hollywood movies (USA / UK)") { has(it, "movies") && it.country in hollywood },
        CategoryDef("Movies: all countries") { has(it, "movies") },
        CategoryDef("Sports: all countries") { has(it, "sports") },
        CategoryDef("News: all countries") { has(it, "news") },
        CategoryDef("Entertainment") { has(it, "entertainment") },
        CategoryDef("Series & drama") { has(it, "series") },
        CategoryDef("Comedy") { has(it, "comedy") },
        CategoryDef("Music") { has(it, "music") },
        CategoryDef("Kids & animation") { has(it, "kids", "animation") },
        CategoryDef("Documentary") { has(it, "documentary") },
        CategoryDef("Lifestyle, cooking & travel") { has(it, "lifestyle", "cooking", "travel") },
        CategoryDef("Business") { has(it, "business") },
        CategoryDef("Religious") { has(it, "religious") },
        CategoryDef("Classic & family") { has(it, "classic", "family") },
        CategoryDef("Science, education & culture") { has(it, "science", "education", "culture") },
        CategoryDef("Outdoor & auto") { has(it, "outdoor", "auto") },
        CategoryDef("Weather") { has(it, "weather") },
        CategoryDef("General") { has(it, "general") },
        CategoryDef("EVERYTHING", true),
        CategoryDef("All channels (Pakistan first)") { true }
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
        headerTitle = findViewById(R.id.headerTitle)
        headerCount = findViewById(R.id.headerCount)
        footerHint = findViewById(R.id.footerHint)
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

        channelAdapter = ChannelAdapter()
        categoryAdapter = CategoryAdapter()
        list.adapter = categoryAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (inCategories) {
                if (pos in catRows.indices && !catRows[pos].isHeader) {
                    lastCatPos = pos
                    openCategory(catRows[pos])
                }
            } else {
                play(pos)
            }
        }

        handler.postDelayed({ splashMinElapsed = true; maybeHideSplash() }, 2500)
        handler.postDelayed({ splashMinElapsed = true; playlistLoaded = true; maybeHideSplash() }, 12000)
        loadData(false)
    }

    // ---------- loading the world channel list ----------

    private fun fetch(name: String, urls: List<String>, force: Boolean): String? {
        val f = java.io.File(cacheDir, name)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 12L * 3600 * 1000
        if (fresh && !force) {
            try { return f.readText() } catch (e: Exception) { }
        }
        for (u in urls) {
            try {
                val conn = URL(u).openConnection()
                conn.connectTimeout = 10000
                conn.readTimeout = 40000
                val text = conn.getInputStream().bufferedReader().use { it.readText() }
                if (text.isNotBlank()) {
                    try { f.writeText(text) } catch (e: Exception) { }
                    return text
                }
            } catch (e: Exception) { }
        }
        if (f.exists()) {
            try { return f.readText() } catch (e: Exception) { }
        }
        return null
    }

    private fun loadData(force: Boolean) {
        playlistLoaded = false
        spinner.visibility = View.VISIBLE
        showStatus(
            "Loading channels...",
            "Downloading the world channel list. The first start can take a minute.",
            false, null
        )
        thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            val fIdx = pool.submit(java.util.concurrent.Callable { fetch("index.m3u", listOf(indexUrl), force) })
            val fCsv = pool.submit(java.util.concurrent.Callable { fetch("channels.csv", listOf(csvUrl), force) })
            val fPk = pool.submit(java.util.concurrent.Callable { fetch("pk.m3u", listOf(pkUrl), force) })
            val idxText = try { fIdx.get() } catch (e: Exception) { null }
            val csvText = try { fCsv.get() } catch (e: Exception) { null }
            val pkText = try { fPk.get() } catch (e: Exception) { null }
            pool.shutdown()

            val meta = if (csvText != null) parseMeta(csvText) else emptyMap()
            val seen = HashSet<String>()
            val out = ArrayList<Channel>()
            if (pkText != null) parseStreams(pkText, meta, seen, out)
            if (idxText != null) parseStreams(idxText, meta, seen, out)
            val sorted = out.sortedWith(
                compareBy<Channel>({ rank(it) }, { it.country }, { it.name.lowercase(Locale.ROOT) })
            )
            val networkFailed = idxText == null && pkText == null

            runOnUiThread {
                master = sorted
                spinner.visibility = View.GONE
                if (master.isEmpty()) {
                    showStatus(
                        "Couldn't load the channel list",
                        if (networkFailed)
                            "The TV couldn't reach the playlist servers. Check your internet connection (Settings > Network), then press OK to try again."
                        else
                            "The playlist came back empty. Press OK to try again.",
                        true, null
                    )
                    headerCount.text = "No channels"
                } else {
                    statusBox.visibility = View.GONE
                    buildCategories()
                    showCategories()
                }
                playlistLoaded = true
                maybeHideSplash()
            }
        }
    }

    private fun rank(c: Channel): Int = when (c.country) {
        "pk" -> 0
        "us" -> 1
        "uk" -> 2
        "ca" -> 3
        "au" -> 4
        "in" -> 5
        else -> 6
    }

    private fun normCountry(s: String): String {
        val x = s.trim().lowercase(Locale.ROOT)
        return if (x == "gb") "uk" else x
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                } else sb.append(ch)
            } else {
                when (ch) {
                    '"' -> inQ = true
                    ',' -> { row.add(sb.toString()); sb.setLength(0) }
                    '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                    '\r' -> {}
                    else -> sb.append(ch)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }

    private fun parseMeta(text: String): Map<String, Meta> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyMap()
        val h = rows[0]
        val iId = h.indexOf("id")
        val iCountry = h.indexOf("country")
        val iCats = h.indexOf("categories")
        val iNsfw = h.indexOf("is_nsfw")
        val iClosed = h.indexOf("closed")
        if (iId < 0 || iCountry < 0 || iCats < 0) return emptyMap()
        val map = HashMap<String, Meta>(rows.size * 2)
        for (k in 1 until rows.size) {
            val r = rows[k]
            if (r.size <= iCats || r.size <= iCountry) continue
            val cats = r[iCats].split(";").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            val nsfw = iNsfw >= 0 && iNsfw < r.size && r[iNsfw].equals("TRUE", true)
            val closed = iClosed >= 0 && iClosed < r.size && r[iClosed].isNotBlank()
            map[r[iId]] = Meta(normCountry(r[iCountry]), cats, nsfw || closed || "xxx" in cats)
        }
        return map
    }

    private fun parseStreams(text: String, meta: Map<String, Meta>, seen: HashSet<String>, out: MutableList<Channel>) {
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var referrer: String? = null
        var ua: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",").trim()
                    val m = HashMap<String, String>()
                    for (r in attrRe.findAll(line)) m[r.groupValues[1]] = r.groupValues[2]
                    attrs = m
                    referrer = null
                    ua = null
                }
                line.startsWith("#EXTVLCOPT:http-referrer=") -> referrer = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> ua = line.substringAfter("=").trim()
                line.isNotEmpty() && !line.startsWith("#") && name != null -> {
                    if ((line.startsWith("http://") || line.startsWith("https://")) && seen.add(line)) {
                        val id = (attrs["tvg-id"] ?: "").substringBefore("@")
                        val md = meta[id]
                        val group = attrs["group-title"] ?: ""
                        val skip = (md != null && md.bad) || group.contains("xxx", true)
                        if (!skip) {
                            val country = normCountry(md?.country ?: id.substringAfterLast('.', ""))
                            val cats = if (md != null && md.cats.isNotEmpty()) md.cats
                            else group.split(";").map { it.trim().lowercase(Locale.ROOT) }
                                .filter { it.isNotEmpty() && it != "undefined" }.toSet()
                            out.add(Channel(name!!, line, referrer, ua, country, cats))
                        }
                    }
                    name = null
                    attrs = emptyMap()
                    referrer = null
                    ua = null
                }
            }
        }
    }

    // ---------- categories / channel list ----------

    private fun buildCategories() {
        val rows = ArrayList<CategoryDef>()
        var pending: CategoryDef? = null
        catCounts.clear()
        for (d in categoryDefs) {
            if (d.isHeader) { pending = d; continue }
            val n = master.count(d.match)
            if (n > 0) {
                val p = pending
                if (p != null) { rows.add(p); pending = null }
                rows.add(d)
                catCounts[d] = n
            }
        }
        catRows = rows
        categoryAdapter.notifyDataSetChanged()
    }

    private fun showCategories() {
        inCategories = true
        checkGen++
        executor?.shutdownNow()
        headerTitle.text = "PK IPTV"
        headerCount.text = "Choose a category  -  ${master.size} channels"
        footerHint.text = "OK: open   Menu: refresh list"
        list.adapter = categoryAdapter
        categoryAdapter.notifyDataSetChanged()
        val pos = if (lastCatPos in catRows.indices) lastCatPos else 1
        list.setSelection(pos)
        list.requestFocus()
    }

    private fun openCategory(def: CategoryDef) {
        inCategories = false
        currentCat = def
        all = master.filter(def.match)
        checkDone = false
        headerTitle.text = def.title
        footerHint.text = "OK: play   Right: fullscreen   Left: categories   Menu: show offline"
        list.adapter = channelAdapter
        applyFilter()
        list.setSelection(0)
        list.requestFocus()
        startHealthCheck()
    }

    private fun applyFilter() {
        val keep = playingChannel
        var shown = if (showOffline || !checkDone) all else all.filter { it.state != 2 }
        if (shown.isEmpty()) shown = all          // never leave the list empty
        channels = shown
        playingIndex = if (keep == null) -1 else channels.indexOfFirst { it === keep }
        channelAdapter.notifyDataSetChanged()
        updateHeader()
    }

    private fun updateHeader() {
        val ok = all.count { it.state == 1 }
        val bad = all.count { it.state == 2 }
        val unknown = all.size - ok - bad
        headerCount.text = when {
            all.isEmpty() -> "No channels"
            !checkDone -> "Checking ${ok + bad}/${all.size}...  $ok working"
            showOffline -> "${all.size} channels  -  $ok working"
            unknown > 0 -> "$ok working  -  $bad offline hidden  -  $unknown unchecked"
            else -> "$ok working  -  $bad offline hidden"
        }
    }

    // ---------- channel health check ----------

    private fun refreshAfterCheck() {
        val old = channels
        val firstPos = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        applyFilter()
        var anchor = -1
        var i = firstPos
        var usedFirst = true
        while (i < old.size && anchor < 0) {
            val target = old[i]
            val idx = channels.indexOfFirst { it === target }
            if (idx >= 0) anchor = idx else usedFirst = false
            i++
        }
        if (anchor >= 0) list.setSelectionFromTop(anchor, if (usedFirst) top else 0)
    }

    private fun startHealthCheck() {
        val gen = ++checkGen
        executor?.shutdownNow()
        val todo = all.filter { it.state == 0 }.take(1500)
        if (todo.isEmpty()) {
            checkDone = true
            applyFilter()
            return
        }
        checkDone = false
        updateHeader()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        executor = pool
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val total = todo.size
        for (c in todo) {
            pool.execute {
                if (gen == checkGen) {
                    val ok = try { probe(c) } catch (e: Exception) { false }
                    c.state = if (ok) 1 else 2
                    val n = done.incrementAndGet()
                    if (n % 5 == 0 || n == total) {
                        runOnUiThread {
                            if (gen == checkGen) {
                                if (n == total) { checkDone = true; refreshAfterCheck() }
                                else { channelAdapter.notifyDataSetChanged(); updateHeader() }
                            }
                        }
                    }
                }
            }
        }
        pool.shutdown()
    }

    // Quick test: does the server answer with something that looks like a stream?
    private fun probe(c: Channel): Boolean {
        for ((ua, ref) in identities(c).take(2)) {
            try {
                val conn = URL(c.url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", ua)
                ref?.let { conn.setRequestProperty("Referer", it) }
                val code = conn.responseCode
                if (code in 300..399) { conn.disconnect(); return true }   // redirect: the player follows it
                if (code in 200..299) {
                    val buf = ByteArray(2048)
                    val n = conn.inputStream.read(buf)
                    conn.disconnect()
                    if (n <= 0) continue
                    if (c.url.contains(".m3u8")) {
                        if (String(buf, 0, n).contains("#EXTM3U")) return true
                    } else return true
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) { /* try next identity */ }
        }
        return false
    }

    // ---------- playback ----------

    private fun play(index: Int) {
        if (index < 0 || index >= channels.size) return
        val c = channels[index]
        playingIndex = index
        playingChannel = c
        channelAdapter.notifyDataSetChanged()
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
        if (channels.isEmpty() || inCategories && playingChannel == null) return
        val next = if (playingIndex < 0) 0 else (playingIndex + delta + channels.size) % channels.size
        if (!inCategories) list.setSelection(next)
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
                    if (master.isEmpty() && playlistLoaded) { loadData(true); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!fullscreen && !inCategories && playingIndex >= 0) { setFullscreen(true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (fullscreen) { setFullscreen(false); return true }
                    else if (!inCategories) { showCategories(); return true }
                KeyEvent.KEYCODE_MENU -> {
                    if (fullscreen) setFullscreen(false)
                    else if (inCategories) loadData(true)
                    else if (checkDone && all.isNotEmpty()) { showOffline = !showOffline; refreshAfterCheck() }
                    return true
                }
                KeyEvent.KEYCODE_BACK ->
                    if (fullscreen) { setFullscreen(false); return true }
                    else if (!inCategories) { showCategories(); return true }
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (fullscreen) { stepChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (fullscreen) { stepChannel(1); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- list adapters ----------

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val c = channels[position]
            val playing = position == playingIndex
            val num = v.findViewById<TextView>(R.id.num)
            num.visibility = View.VISIBLE
            num.text = if (c.country.isEmpty()) "--" else c.country.uppercase(Locale.ROOT)
            val name = v.findViewById<TextView>(R.id.name)
            name.text = c.name
            name.setTextColor(if (playing) 0xFF60A5FA.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.marker).visibility =
                if (playing) View.VISIBLE else View.INVISIBLE
            val dot = v.findViewById<TextView>(R.id.dot)
            dot.text = "●"
            dot.setTextColor(
                when (c.state) {
                    1 -> 0xFF22C55E.toInt()
                    2 -> 0xFFEF4444.toInt()
                    else -> 0xFF6B7280.toInt()
                }
            )
            return v
        }
    }

    private inner class CategoryAdapter : BaseAdapter() {
        override fun getCount() = catRows.size
        override fun getItem(position: Int) = catRows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = !catRows[position].isHeader
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (catRows[position].isHeader) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = catRows[position]
            if (d.isHeader) {
                val h = convertView ?: layoutInflater.inflate(R.layout.item_header, parent, false)
                (h as TextView).text = d.title
                return h
            }
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            v.findViewById<TextView>(R.id.marker).visibility = View.GONE
            v.findViewById<TextView>(R.id.num).visibility = View.GONE
            val name = v.findViewById<TextView>(R.id.name)
            name.text = d.title
            name.setTextColor(0xFFFFFFFF.toInt())
            val count = v.findViewById<TextView>(R.id.dot)
            count.text = String.format(Locale.US, "%,d", catCounts[d] ?: 0)
            count.setTextColor(0xFF9CA3AF.toInt())
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
        executor?.shutdownNow()
        super.onDestroy()
        player.release()
    }
}
