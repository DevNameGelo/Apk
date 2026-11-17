package com.musicrage.app.playback

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.musicrage.app.online.OnlinePlayer
import com.musicrage.app.online.OnlineService

/**
 * Decides which of the two players owns the ONE media notification.
 *
 * Before this existed, local playback (PlaybackService, media3) and online playback (OnlineService) each
 * ran their own media session and notification, so both showed at once and both claimed the headset
 * buttons — the "duplicate player" on the notification shade.
 *
 * The local service is the default owner. Starting an online video claims [Owner.ONLINE]: the local
 * service pauses and stays silent (its notification is removed and not re-posted). Starting local
 * playback claims [Owner.LOCAL] and the online service hides its notification and stops itself.
 */
object PlaybackSupervisor {
    enum class Owner { LOCAL, ONLINE }

    @Volatile
    private var owner = Owner.LOCAL

    private val handler = Handler(Looper.getMainLooper())

    fun current(): Owner = owner

    /** Claims notification ownership for [newOwner] and tells the other player to stand down. */
    fun claim(context: Context, newOwner: Owner) {
        if (owner == newOwner) return
        owner = newOwner
        when (newOwner) {
            Owner.ONLINE -> runCatching {
                // Pause the local player first so it stops before its notification goes away.
                handler.post { OnlinePlayer.pauseLocalForTakeover() }
                context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_SYNC_ONLINE))
            }
            Owner.LOCAL -> runCatching {
                // The online player fully stands down: paused AND cleared, so the local player's bar,
                // notification and controls are the only ones the user sees from now on.
                handler.post { OnlinePlayer.stop() }
                context.startService(Intent(context, OnlineService::class.java).setAction(OnlineService.ACTION_STAND_DOWN))
            }
        }
    }
}
