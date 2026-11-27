// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.utils.Log

/**
 * Keeps the car from starting music: some car radios send a play key when the phone connects to them and when a call
 * ends (to resume their Bluetooth source), and the system gives it to the app that played last, which starts (Spotify,
 * YouTube). For a few seconds after a Bluetooth audio device connects or a call ends, this plays silence (without
 * taking the audio focus, so nothing else stops) with a media session of its own: the system gives the media keys to
 * the app that played last, now this one, whose session takes them and does nothing. Then the session goes, and the
 * keys go back to the app that played before. Nothing is done while something else plays.
 */
@RequiresApi(Build.VERSION_CODES.S)
class CarPlayGuard(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var track: AudioTrack? = null
    /** the Bluetooth audio devices connected, as the callback also reports those connected when it is registered */
    private val connected = mutableSetOf<Int>()
    private var mode = audioManager.mode

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (device in addedDevices.filter { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
                if (connected.add(device.id)) guard()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { connected.remove(it.id) }
        }
    }

    /** a call ended (or a ringing one was not answered): the mode goes back to normal */
    private val modeListener = AudioManager.OnModeChangedListener { newMode ->
        if (newMode == AudioManager.MODE_NORMAL && mode != AudioManager.MODE_NORMAL) guard()
        mode = newMode
    }

    private val release = Runnable {
        track?.run {
            try { stop() } catch (_: IllegalStateException) { }
            release()
        }
        track = null
        session?.release()
        session = null
    }

    fun start() {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }.forEach { connected.add(it.id) }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnModeChangedListener(modeListener)
        handler.removeCallbacks(release)
        release.run()
    }

    /** takes the media keys for [GUARD_MS] from now, unless something else plays */
    private fun guard() {
        if (session == null && audioManager.isMusicActive) return
        handler.removeCallbacks(release)
        if (session == null) {
            try {
                session = newSession()
                track = silence().apply { play() }
            } catch (e: Exception) {
                Log.w(TAG, "can't take the media keys", e)
                release.run()
                return
            }
        }
        handler.postDelayed(release, GUARD_MS)
    }

    private fun newSession() = MediaSession(context, TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                Log.i(TAG, "media key kept from the other players")
                return true
            }
        }, handler)
        setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
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
        private const val TAG = "car-play-guard"
        private const val GUARD_MS = 8000L
        private const val SAMPLE_RATE = 44100
    }
}
