package com.cratez.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.cratez.app.data.TrackEntity
import com.cratez.core.genre.EdmClassifier
import com.cratez.core.music.MusicKey

/** Email / text / any-app sharing through Android's share system. */
object ShareSender {
    const val EMAIL_LIMIT = 25L * 1024 * 1024

    fun describe(t: TrackEntity, camelot: Boolean = true): String {
        val bits = mutableListOf("${t.displayArtist} – ${t.displayTitle}")
        if (t.bpm > 0) bits += "${EdmClassifier.fmtBpm(t.bpm)} BPM"
        if (t.musicalKey >= 0) bits += if (camelot) MusicKey.camelot(t.musicalKey) else MusicKey.shortName(t.musicalKey)
        if (t.genre.isNotBlank()) bits += t.genre
        return bits.joinToString(" · ")
    }

    private fun message(tracks: List<TrackEntity>) = buildString {
        append(if (tracks.size == 1) "Check out this track 🎧\n\n" else "Here are ${tracks.size} tracks for you 🎧\n\n")
        tracks.forEach { append("▸ ").append(describe(it)).append('\n') }
        append("\nSent with Cratez — open the files in Cratez and they land fully tagged.")
    }

    private fun base(tracks: List<TrackEntity>): Intent {
        val uris = ArrayList(tracks.map { Uri.parse(it.uri) })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, message(tracks))
            putExtra(Intent.EXTRA_SUBJECT, if (tracks.size == 1) "🎧 ${tracks[0].displayArtist} – ${tracks[0].displayTitle}" else "🎧 ${tracks.size} tracks from my crate")
            clipData = ClipData.newRawUri("tracks", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun email(ctx: Context, tracks: List<TrackEntity>) {
        val i = base(tracks).apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
        launch(ctx, i, "Email tracks")
    }

    fun text(ctx: Context, tracks: List<TrackEntity>) {
        val i = base(tracks)
        Telephony.Sms.getDefaultSmsPackage(ctx)?.let { i.setPackage(it) }
        launch(ctx, i, "Text tracks")
    }

    fun anyApp(ctx: Context, tracks: List<TrackEntity>) = launch(ctx, base(tracks), "Share tracks", forceChooser = true)

    private fun launch(ctx: Context, i: Intent, title: String, forceChooser: Boolean = false) {
        val intent = if (forceChooser) Intent.createChooser(i, title) else i
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: Exception) {
            ctx.startActivity(Intent.createChooser(i.apply { selector = null; setPackage(null) }, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
