@file:OptIn(UnstableApi::class)

package com.cratez.app.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.cratez.app.MainActivity
import com.cratez.app.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Background playback with lock-screen / notification controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioTap)))
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

data class PlayerUi(
    val currentId: Long = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
)

/** App-side remote control for [PlaybackService]. */
class PlayerConnection(private val ctx: Context) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        pending += block
        if (connecting) return
        connecting = true
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            pending.toList().forEach { it(c) }; pending.clear()
            publish()
            scope.launch { while (controller != null) { publish(); delay(250) } }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun connect() = withController { }

    private fun publish() {
        val c = controller ?: return
        _ui.value = PlayerUi(
            currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: -1,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            queueSize = c.mediaItemCount,
        )
    }

    private fun item(t: TrackEntity): MediaItem = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(Uri.parse(t.uri))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.displayTitle).setArtist(t.displayArtist).setAlbumTitle(t.album.ifBlank { null })
                .setGenre(t.genre.ifBlank { null })
                .setArtworkUri(t.artPath?.let { Uri.fromFile(File(it)) }).build(),
        ).build()

    fun playList(tracks: List<TrackEntity>, start: Int) = withController { c ->
        if (tracks.isEmpty()) return@withController
        c.setMediaItems(tracks.map { item(it) }, start.coerceIn(0, tracks.lastIndex), 0)
        c.prepare(); c.play()
    }

    /** Plays a single track starting at [ms] (used to preview a picked section). */
    fun playAt(t: TrackEntity, ms: Long) = withController { c ->
        if (c.currentMediaItem?.mediaId == t.id.toString()) { c.seekTo(ms); c.play(); return@withController }
        c.setMediaItems(listOf(item(t)), 0, ms); c.prepare(); c.play()
    }

    fun toggle() = withController { c -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } }
    fun next() = withController { it.seekToNextMediaItem() }
    fun prev() = withController { c -> if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun shuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun stopAndClear() = withController { it.stop(); it.clearMediaItems() }
}
