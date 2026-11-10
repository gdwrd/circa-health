package dev.ring.health

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import dev.ring.health.Prefs.holdPlace
import java.io.File

/**
 * Hold my place: Android only lists media sessions for an enabled notification listener. This class overrides no
 * notification callback, so it never reads a notification; it logs what long items play to `filesDir/playback.log`.
 * One line: ts_ms, package, title, duration_ms, playing 0/1, position_ms, speed (tab-separated: titles hold commas).
 */
class PlaybackListener : NotificationListenerService() {
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val last = mutableMapOf<String, Pair<String, Long>>() // package → (playing + title, ts) of its last line
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, PlaybackListener::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsChanged, me)
        watch(manager.getActiveSessions(me))
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        watch(emptyList())
    }

    private fun watch(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        controllers = list
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = log(c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = log(c)
            }
            c.registerCallback(cb)
            callbacks[c] = cb
            log(c)
        }
    }

    private fun log(c: MediaController) {
        if (!holdPlace) return
        val meta = c.metadata ?: return
        val state = c.playbackState ?: return
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration < 600_000) return // under 10 min: a music track, not a podcast or audiobook
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().replace(Regex("[\t\r\n]"), " ")
        val playing = state.state == PlaybackState.STATE_PLAYING
        val now = System.currentTimeMillis()
        // At once when playing or the title changes, else at most one line a minute per package.
        val key = "$playing\t$title"
        last[c.packageName]?.let { (k, ts) -> if (k == key && now - ts < 60_000) return }
        last[c.packageName] = key to now
        val moved = if (playing) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        val position = (state.position + moved).coerceIn(0L, duration)
        runCatching {
            val file = File(filesDir, "playback.log")
            if (file.length() > 256 * 1024) file.readLines().let { l -> file.writeText(l.drop(l.size / 2).joinToString("") { "$it\n" }) }
            file.appendText("$now\t${c.packageName}\t$title\t$duration\t${if (playing) 1 else 0}\t$position\t${state.playbackSpeed}\n")
        }.onFailure { Log.w("Playback", "Log write failed", it) }
    }

    companion object {
        /** The active media sessions while the listener is connected. */
        @Volatile var controllers: List<MediaController> = emptyList()
    }
}
