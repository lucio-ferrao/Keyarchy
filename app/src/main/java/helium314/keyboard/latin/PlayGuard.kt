// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.utils.Log

/**
 * Keeps Bluetooth devices from starting music: some (car radios, headphones) send a play key when the phone connects
 * to them and when a call ends, and the system gives it to the app that played last (Spotify, YouTube, a video in the
 * browser), which starts. After [IDLE_MS] without media playing, this plays a moment of silence (without taking the
 * audio focus) with a media session of its own, which stays: the system gives the media keys to the app that played
 * last, now this one, whose session takes them and does nothing, until another app plays. So a play key does nothing
 * then, also a wanted one (steering wheel, headphones): to play again, the player is started on the phone.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PlayGuard(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var track: AudioTrack? = null
    /** whether the keys are here, since the silence and until another app plays */
    private var taken = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            // the silence played here counts too, but it takes the keys already
            if (track != null) return
            handler.removeCallbacks(take)
            if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }) taken = false
            else if (!taken) handler.postDelayed(take, IDLE_MS)
        }
    }

    private val take = Runnable {
        try {
            if (session == null) session = newSession()
            track = silence().apply { play() }
            taken = true
            handler.postDelayed(endSilence, SILENCE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "can't take the media keys", e)
            endSilence.run()
        }
    }

    private val endSilence = Runnable {
        track?.run {
            try { stop() } catch (_: IllegalStateException) { }
            release()
        }
        track = null
    }

    fun start() {
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        if (audioManager.activePlaybackConfigurations.none { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA })
            handler.postDelayed(take, IDLE_MS)
    }

    fun stop() {
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacks(take)
        handler.removeCallbacks(endSilence)
        endSilence.run()
        session?.release()
        session = null
    }

    /** paused, so a device shows nothing playing, and taking the play key without playing */
    private fun newSession() = MediaSession(context, TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                Log.i(TAG, "media key kept from the other players")
                return true
            }
        }, handler)
        setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
        isActive = true
    }

    /** a short silence played in a loop, as media, which makes this the app that played last */
    private fun silence(): AudioTrack {
        val frames = SAMPLE_RATE / 10
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build().apply {
                write(ShortArray(frames), 0, frames)
                setLoopPoints(0, frames, -1)
            }
    }

    companion object {
        private const val TAG = "play-guard"
        /** without media playing this long, the keys are taken */
        private const val IDLE_MS = 15 * 60 * 1000L
        private const val SILENCE_MS = 2000L
        private const val SAMPLE_RATE = 44100
    }
}
