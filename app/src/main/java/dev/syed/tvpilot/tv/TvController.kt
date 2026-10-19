package dev.syed.tvpilot.tv

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/** Remote keys the app (and the model) may press. Values are Android keycodes. */
enum class TvKey(val code: Int, val label: String) {
    PLAY_PAUSE(85, "Play / pause"),
    BACK(4, "Back"),
    HOME(3, "Home"),
    UP(19, "Up"),
    DOWN(20, "Down"),
    LEFT(21, "Left"),
    RIGHT(22, "Right"),
    OK(23, "OK"),
    NEXT(87, "Next"),
    PREVIOUS(88, "Previous"),
    FAST_FORWARD(90, "Fast forward"),
    REWIND(89, "Rewind"),
    VOLUME_UP(24, "Volume up"),
    VOLUME_DOWN(25, "Volume down"),
    MUTE(164, "Mute"),
    SLEEP(223, "Turn off"),
    WAKE(224, "Turn on"),
}

/** A TV app the phone knows how to talk about. */
data class TvApp(
    val packageName: String,
    val label: String,
    val aliases: List<String>,
    /** Search deep link with %s for the URL-encoded query, or null to use Google TV search. */
    val searchUrl: String? = null,
    /** Explicit activity for apps whose manifest doesn't claim the links (TizenTube Cobalt). */
    val component: String? = null,
)

object AppCatalog {
    val known = listOf(
        // TizenTube Cobalt: ad-free YouTube. Cobalt only loads real https pages, sent straight to its activity.
        TvApp("io.gh.reisxd.tizentube.cobalt", "TizenTube", listOf("tizentube", "tizen tube", "youtube", "yt"),
            "https://www.youtube.com/results?search_query=%s", component = "io.gh.reisxd.tizentube.cobalt/dev.cobalt.app.MainActivity"),
        TvApp("com.google.android.youtube.tv", "YouTube", listOf("youtube", "yt"), "https://www.youtube.com/results?search_query=%s"),
        TvApp("com.google.android.youtube.tvmusic", "YouTube Music", listOf("youtube music", "yt music")),
        TvApp("com.netflix.ninja", "Netflix", listOf("netflix"), "https://www.netflix.com/search?q=%s"),
        TvApp("com.amazon.amazonvideo.livingroom", "Prime Video", listOf("prime video", "prime", "amazon")),
        TvApp("in.startv.hotstar", "JioHotstar", listOf("jiohotstar", "jio hotstar", "hotstar")),
        TvApp("in.startv.hotstartv", "JioHotstar", listOf("jiohotstar", "jio hotstar", "hotstar")),
        TvApp("com.spotify.tv.android", "Spotify", listOf("spotify"), "spotify:search:%s"),
        TvApp("com.crunchyroll.crunchyroid", "Crunchyroll", listOf("crunchyroll", "crunchy"), "https://www.crunchyroll.com/search?q=%s"),
        TvApp("com.sonyliv", "SonyLIV", listOf("sonyliv", "sony liv")),
        TvApp("com.graymatrix.did", "ZEE5", listOf("zee5", "zee 5")),
        TvApp("com.plexapp.android", "Plex", listOf("plex")),
        TvApp("org.videolan.vlc", "VLC", listOf("vlc")),
        TvApp("org.xbmc.kodi", "Kodi", listOf("kodi")),
        TvApp("com.stremio.one", "Stremio", listOf("stremio")),
        TvApp("com.google.android.apps.tv.launcherx", "Google TV home", listOf("google tv", "home screen")),
    )

    fun forPackages(installed: Set<String>): List<TvApp> {
        var knownHere = known.filter { it.packageName in installed }.distinctBy { it.label }
        // With TizenTube installed, "YouTube" means TizenTube; the official app answers only to "official YouTube".
        if ("io.gh.reisxd.tizentube.cobalt" in installed) knownHere = knownHere.map {
            if (it.packageName == "com.google.android.youtube.tv") it.copy(label = "YouTube (official)", aliases = listOf("official youtube", "youtube official", "google youtube")) else it
        }
        val others = installed
            .filter { pkg -> known.none { it.packageName == pkg } }
            .filterNot { it.startsWith("com.android.") || it.startsWith("android") || it.contains(".overlay") }
            .map { pkg ->
                // com.nuvio.tv -> "Nuvio": skip generic last parts like tv, app, android.
                val parts = pkg.split('.')
                val generic = setOf("tv", "app", "android", "androidtv", "atv", "leanback", "mobile", "client", "player", "one", "ninja")
                val raw = parts.lastOrNull { it.lowercase() !in generic && it !in setOf("com", "org", "net", "in", "io", "dev") } ?: parts.last()
                val name = raw.replaceFirstChar { it.uppercase() }
                TvApp(pkg, name, listOf(name.lowercase()))
            }
        return knownHere + others.sortedBy { it.label }
    }
}

sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val host: String) : Connection
    data class Failed(val reason: String) : Connection
}

/**
 * Talks to the TV over network ADB, straight from the phone.
 * The first connect makes the TV show "Allow USB debugging?" for this phone's key. Tick "Always allow".
 */
class TvController(private val context: Context) {
    private val mutex = Mutex()
    private var dadb: Dadb? = null
    private var host = ""
    private var port = 5555

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                runCatching { dadb?.close() }
                dadb = null
                val d = Dadb.create(host, port, keyPair, connectTimeout = 6000, socketTimeout = 0)
                d.shell("echo ok") // waits here until you allow the phone on the TV
                dadb = d
                this@TvController.host = host
                this@TvController.port = port
            }
        }
    }

    fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
    }

    /** Runs a shell command on the TV, reconnecting once if the link dropped. */
    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val first = runCatching { requireNotNull(dadb) { "Not connected to the TV" }.shell(cmd) }
            val res = first.getOrElse {
                if (host.isEmpty()) throw it
                runCatching { dadb?.close() }
                dadb = Dadb.create(host, port, keyPair, connectTimeout = 6000, socketTimeout = 0)
                dadb!!.shell(cmd)
            }
            res.allOutput.trim()
        }
    }

    /** Only apps with a TV launcher icon, so TCL's dozens of system packages never reach the model. */
    suspend fun installedPackages(): Set<String> {
        val out = shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
        val pkgs = out.lines().map { it.trim() }.filter { it.contains('/') }.map { it.substringBefore('/') }.toSet()
        if (pkgs.isNotEmpty()) return pkgs
        return shell("pm list packages -3").lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Waits until [pkg] is in front, then a moment more for its first screen to draw. */
    suspend fun waitForApp(pkg: String, timeoutMs: Long = 8000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (foregroundPackage() == pkg) break
            delay(350)
        }
        delay(1500)
    }

    suspend fun foregroundPackage(): String? {
        val out = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2")
        return Regex("""([a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+)/""").find(out)?.groupValues?.get(1)
    }

    suspend fun launch(pkg: String): String {
        AppCatalog.known.firstOrNull { it.packageName == pkg }?.component?.let { return shell("am start -n ${q(it)}") }
        return shell("monkey -p ${q(pkg)} -c android.intent.category.LEANBACK_LAUNCHER 1")
    }

    /** Deep-link search for apps that support one. Never uses Google's global search (it pops up the Assistant). */
    suspend fun deepSearch(app: TvApp, query: String, autoPlayFirst: Boolean) {
        val tpl = requireNotNull(app.searchUrl)
        val enc = URLEncoder.encode(query, "UTF-8") // spaces become +
        val url = tpl.replace("%s", if (tpl.startsWith("spotify:")) enc.replace("+", "%20") else enc)
        val wasOpen = foregroundPackage() == app.packageName
        val target = app.component?.let { "-n ${q(it)}" } ?: q(app.packageName)
        shell("am start -a android.intent.action.VIEW -d ${q(url)} $target")
        if (autoPlayFirst && app.label.let { it == "YouTube" || it == "TizenTube" || it.startsWith("YouTube") }) {
            delay(if (wasOpen) 3500 else 6500) // Cobalt takes a while on a cold start
            key(TvKey.OK)
        }
    }

    /** Faster path for remote keys (Bluetooth). Returns true when it handled the press. */
    @Volatile var fastKeys: (suspend (TvKey, Int) -> Boolean)? = null

    suspend fun key(k: TvKey, times: Int = 1) {
        if (runCatching { fastKeys?.invoke(k, times) }.getOrNull() == true) return
        repeat(times.coerceIn(1, 25)) { shell("input keyevent ${k.code}") }
    }

    /** Result of looking at the screen: what was tapped, plus the words we could read (for error messages). */
    data class Pick(val tapped: String?, val seen: List<String>, val how: String)

    /** uiautomator dump; returns "" when the screen never went idle (animated backdrops do that). */
    /** The TV's own Bluetooth address, for pairing the phone as a remote. */
    suspend fun bluetoothAddress(): String? {
        val mac = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        mac.find(shell("settings get secure bluetooth_address"))?.value?.let { if (it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00") return it.uppercase() }
        val dump = shell("dumpsys bluetooth_manager | grep -m3 -iE '^ *(address|mAddress|Address):'")
        return mac.find(dump)?.value?.uppercase()?.takeIf { it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00" }
    }

    private suspend fun dumpUi(): String {
        val xml = shell("uiautomator dump --compressed /sdcard/tvpilot.xml >/dev/null 2>&1 && cat /sdcard/tvpilot.xml; rm -f /sdcard/tvpilot.xml")
        return if (xml.contains("<hierarchy")) xml else ""
    }

    /** Screenshot read with on-device text recognition, for apps whose tiles uiautomator can't see. */
    private suspend fun ocrScreen(): List<ScreenReader.Item> = withContext(Dispatchers.IO) {
        shell("screencap -p /sdcard/tvpilot.png")
        val local = File(context.cacheDir, "tv.png")
        mutex.withLock { requireNotNull(dadb).pull(local, "/sdcard/tvpilot.png") }
        shell("rm -f /sdcard/tvpilot.png")
        val bmp = BitmapFactory.decodeFile(local.path) ?: return@withContext emptyList()
        // input tap uses the logical display size, which can differ from the framebuffer on 4K sets.
        val size = Regex("""(\d+)x(\d+)""").findAll(shell("wm size")).lastOrNull()?.groupValues
        val sx = (size?.get(1)?.toDouble() ?: bmp.width.toDouble()) / bmp.width
        val sy = (size?.get(2)?.toDouble() ?: bmp.height.toDouble()) / bmp.height
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            text.textBlocks.flatMap { b -> b.lines.map { it } + listOf(b) }.mapNotNull { el ->
                val r = (el as? com.google.mlkit.vision.text.Text.Line)?.boundingBox
                    ?: (el as? com.google.mlkit.vision.text.Text.TextBlock)?.boundingBox ?: return@mapNotNull null
                val t = (el as? com.google.mlkit.vision.text.Text.Line)?.text ?: (el as com.google.mlkit.vision.text.Text.TextBlock).text.replace('\n', ' ')
                ScreenReader.Item(t, (r.centerX() * sx).toInt(), (r.centerY() * sy).toInt())
            }
        } finally {
            recognizer.close()
            bmp.recycle()
        }
    }

    /** What's readable on screen right now: accessibility tree first, screenshot text as a fallback. */
    suspend fun readScreen(): Pair<List<ScreenReader.Item>, String> {
        val ui = ScreenReader.items(dumpUi())
        if (ui.size >= 3) return ui to "screen tree"
        val ocr = runCatching { ocrScreen() }.getOrDefault(emptyList())
        return (if (ocr.isNotEmpty()) ocr else ui) to "screenshot"
    }

    /**
     * Finds the tile whose title best matches and selects it. TV apps are built for the D-pad,
     * so after the tap we also press OK, which opens it if the tap only moved focus.
     */
    suspend fun pickOnScreen(title: String, attempts: Int = 3, pressOk: Boolean = true, avoidY: Int? = null): Pick {
        var seen = emptyList<String>()
        var how = ""
        repeat(attempts) { attempt ->
            val (items, source) = readScreen()
            how = source
            seen = items.map { it.text }
            val best = ScreenReader.bestMatch(items.filter { avoidY == null || kotlin.math.abs(it.y - avoidY) > 70 }, title)
            if (best != null) {
                shell("input tap ${best.x} ${best.y}")
                if (pressOk) { delay(700); key(TvKey.OK) }
                return Pick(best.text, seen, source)
            }
            if (attempt < attempts - 1) delay(1200) // rows may still be loading
        }
        return Pick(null, seen, how)
    }

    /** Taps a button by its label (Search, Resume, Play...). Returns true when one was found. */
    suspend fun tapLabel(vararg labels: String): Int? {
        val (items, _) = readScreen()
        val hit = labels.firstNotNullOfOrNull { l -> items.firstOrNull { ScreenReader.norm(it.text) == l } }
            ?: labels.firstNotNullOfOrNull { l -> items.firstOrNull { ScreenReader.norm(it.text).startsWith(l) && it.text.length < 24 } }
            ?: return null
        shell("input tap ${hit.x} ${hit.y}")
        return hit.y
    }

    /** Opens the app's own search box, types the title and submits. Returns the box's height on screen, or null. */
    suspend fun searchInsideApp(query: String): Int? {
        val y = tapLabel("search", "search movies", "search movies and shows", "find") ?: return null
        delay(1200)
        typeText(query)
        delay(300)
        shell("input keyevent 66") // Enter
        delay(2500)
        return y
    }

    /** A downscaled JPEG of the TV screen plus the size `input tap` expects, for the vision model. */
    class Shot(val jpeg: ByteArray, val width: Int, val height: Int)

    private var displaySize: Pair<Int, Int>? = null

    suspend fun screenshot(maxWidth: Int = 1024): Shot = withContext(Dispatchers.IO) {
        shell("screencap -p /sdcard/tvpilot.png")
        val local = File(context.cacheDir, "shot.png")
        mutex.withLock { requireNotNull(dadb) { "Not connected to the TV" }.pull(local, "/sdcard/tvpilot.png") }
        val full = BitmapFactory.decodeFile(local.path) ?: error("Couldn't take a screenshot of the TV")
        val (w, h) = displaySize ?: run {
            val m = Regex("""(\d+)x(\d+)""").findAll(shell("wm size")).lastOrNull()?.groupValues
            ((m?.get(1)?.toInt() ?: full.width) to (m?.get(2)?.toInt() ?: full.height)).also { displaySize = it }
        }
        val scale = minOf(1f, maxWidth.toFloat() / full.width)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
        if (small !== full) small.recycle()
        full.recycle()
        Shot(out.toByteArray(), w, h)
    }

    suspend fun tap(x: Int, y: Int) { shell("input tap $x $y") }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 350) { shell("input swipe $x1 $y1 $x2 $y2 $ms") }

    /** Opens a link inside a specific app (TizenTube channel or video pages, etc). */
    suspend fun openUrl(app: TvApp, url: String) {
        val target = app.component?.let { "-n ${q(it)}" } ?: q(app.packageName)
        shell("am start -a android.intent.action.VIEW -d ${q(url)} $target")
    }

    suspend fun typeText(text: String) {
        val safe = text.take(100).replace(" ", "%s")
        shell("input text ${q(safe)}")
    }

    /** Single-quotes a value for the TV's shell. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}


/** Finds on-screen items by their text. */
object ScreenReader {
    data class Item(val text: String, val x: Int, val y: Int)
    data class Hit(val text: String, val x: Int, val y: Int, val score: Double)

    private val nodeRe = Regex("""<node\b[^>]*>""")
    private fun attr(node: String, name: String) =
        Regex("""\b$name="([^"]*)"""").find(node)?.groupValues?.get(1)?.let(::unescape) ?: ""

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", " ")

    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun items(xml: String): List<Item> = nodeRe.findAll(xml).mapNotNull { m ->
        val node = m.value
        val label = attr(node, "text").ifBlank { attr(node, "content-desc") }.trim()
        if (label.isEmpty() || attr(node, "class").contains("EditText")) return@mapNotNull null
        val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(attr(node, "bounds"))?.groupValues ?: return@mapNotNull null
        val (x1, y1, x2, y2) = b.drop(1).map { it.toInt() }
        if (x2 <= x1 || y2 <= y1) null else Item(label, (x1 + x2) / 2, (y1 + y2) / 2)
    }.toList()

    fun bestMatch(items: List<Item>, title: String): Hit? {
        val want = norm(title)
        if (want.isEmpty()) return null
        val wantWords = want.split(' ').filter { it.length > 1 }.toSet()
        return items.mapNotNull { item ->
            val have = norm(item.text)
            if (have.isEmpty()) return@mapNotNull null
            val haveWords = have.split(' ').toSet()
            val score = when {
                have == want -> 1.0
                // "Stranger Things S4 E7", "Continue Stranger Things"...
                have.contains(want) -> 0.9 - (have.length - want.length) / 400.0
                want.contains(have) && have.length >= 5 -> 0.7
                else -> wantWords.count { it in haveWords }.toDouble() / wantWords.size.coerceAtLeast(1) * 0.65
            }
            if (score < 0.5) null else Hit(item.text, item.x, item.y, score)
        }.maxByOrNull { it.score }
    }
}
