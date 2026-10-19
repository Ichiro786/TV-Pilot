package dev.syed.tvpilot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.syed.tvpilot.MainActivity
import dev.syed.tvpilot.R
import dev.syed.tvpilot.data.SettingsStore
import dev.syed.tvpilot.tv.BtRemote
import dev.syed.tvpilot.tv.TvController
import dev.syed.tvpilot.tv.TvKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the widgets show. The app pushes this whenever the mic or the TV changes. */
enum class WidgetMode { IDLE, LISTENING, THINKING }

object TvWidgets {
    const val ACTION_LISTEN = "dev.syed.tvpilot.action.LISTEN"
    private const val ACTION_KEY = "dev.syed.tvpilot.action.WIDGET_KEY"
    private const val EXTRA_KEY = "key"
    private const val PREFS = "tvp_widget"

    private var lastMode = WidgetMode.IDLE
    private var lastStatus: String? = null

    fun push(ctx: Context, mode: WidgetMode, status: String?) {
        if (mode == lastMode && status == lastStatus) return
        lastMode = mode; lastStatus = status
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status", status).apply()
        updateAll(ctx, mode, status)
    }

    fun updateAll(ctx: Context, mode: WidgetMode = lastMode, status: String? = null) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val st = status ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", null)
        mgr.getAppWidgetIds(ComponentName(ctx, MicWidget::class.java)).forEach { mgr.updateAppWidget(it, micViews(ctx, mode)) }
        mgr.getAppWidgetIds(ComponentName(ctx, WideWidget::class.java)).forEach { mgr.updateAppWidget(it, wideViews(ctx, mode, st)) }
    }

    private fun listenIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun keyIntent(ctx: Context, key: TvKey): PendingIntent {
        val i = Intent(ctx, WideWidget::class.java).setAction(ACTION_KEY).putExtra(EXTRA_KEY, key.name)
        return PendingIntent.getBroadcast(ctx, 100 + key.ordinal, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun RemoteViews.mic(mode: WidgetMode) {
        setViewVisibility(R.id.w_idle, if (mode == WidgetMode.IDLE) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.w_rec, if (mode == WidgetMode.LISTENING) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.w_think, if (mode == WidgetMode.THINKING) View.VISIBLE else View.GONE)
    }

    fun micViews(ctx: Context, mode: WidgetMode) = RemoteViews(ctx.packageName, R.layout.tvp_widget_mic).apply {
        mic(mode)
        setOnClickPendingIntent(R.id.w_root, listenIntent(ctx))
    }

    fun wideViews(ctx: Context, mode: WidgetMode, status: String?) = RemoteViews(ctx.packageName, R.layout.tvp_widget_wide).apply {
        mic(mode)
        setTextViewText(R.id.w_title, when (mode) {
            WidgetMode.IDLE -> "Tap to talk"
            WidgetMode.LISTENING -> "Listening…"
            WidgetMode.THINKING -> "On it…"
        })
        setTextViewText(R.id.w_status, when (mode) {
            WidgetMode.LISTENING -> "Hearing you"
            else -> status ?: "Ready"
        })
        setInt(R.id.w_dot, "setColorFilter", when (mode) {
            WidgetMode.LISTENING -> 0xFFFF5C5C.toInt()
            WidgetMode.THINKING -> 0xFFFFC34D.toInt()
            WidgetMode.IDLE -> 0xFF50DC82.toInt()
        })
        setOnClickPendingIntent(R.id.w_mic, listenIntent(ctx))
        setOnClickPendingIntent(R.id.w_root, listenIntent(ctx))
        setOnClickPendingIntent(R.id.w_k_play, keyIntent(ctx, TvKey.PLAY_PAUSE))
        setOnClickPendingIntent(R.id.w_k_vdown, keyIntent(ctx, TvKey.VOLUME_DOWN))
        setOnClickPendingIntent(R.id.w_k_vup, keyIntent(ctx, TvKey.VOLUME_UP))
        setOnClickPendingIntent(R.id.w_k_home, keyIntent(ctx, TvKey.HOME))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tv: TvController? = null

    /** Quick buttons work without opening the app: Bluetooth if paired, otherwise ADB. */
    internal fun handleKey(ctx: Context, intent: Intent, done: () -> Unit) {
        val key = runCatching { TvKey.valueOf(intent.getStringExtra(EXTRA_KEY) ?: "") }.getOrNull() ?: return done()
        val app = ctx.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(9000) {
                    val s = SettingsStore(app).settings.first()
                    if (s.btRemote && BtRemote.isConnected && BtRemote.send(key, 1)) return@withTimeoutOrNull
                    val c = tv ?: TvController(app).also { tv = it }
                    if (s.tvHost.isBlank()) return@withTimeoutOrNull
                    runCatching { c.key(key) }.onFailure {
                        c.connect(s.tvHost, s.tvPort).onSuccess { runCatching { c.key(key) } }
                    }
                }
            } finally { done() }
        }
    }
}

class MicWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, TvWidgets.micViews(ctx, WidgetMode.IDLE)) }
    }
}

class WideWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val st = ctx.getSharedPreferences("tvp_widget", Context.MODE_PRIVATE).getString("status", null)
        ids.forEach { mgr.updateAppWidget(it, TvWidgets.wideViews(ctx, WidgetMode.IDLE, st)) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "dev.syed.tvpilot.action.WIDGET_KEY") {
            val pending = goAsync()
            TvWidgets.handleKey(ctx, intent) { pending.finish() }
            return
        }
        super.onReceive(ctx, intent)
    }
}
