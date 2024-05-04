/*
 * AsteroidOSSync
 * Copyright (c) 2024 AsteroidOS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.asteroidos.sync.dbus

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.*
import com.google.common.collect.Lists
import com.google.common.hash.Hashing
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.asteroidos.sync.media.IMediaService
import org.asteroidos.sync.media.MediaSupervisor
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.Properties.PropertiesChanged
import org.freedesktop.dbus.types.Variant
import org.mpris.MediaPlayer2
import org.mpris.mediaplayer2.Player
import java.nio.charset.Charset
import java.util.Arrays
import java.util.Collections
import java.util.Date

class MediaService(private val mCtx: Context, private val supervisor: MediaSupervisor, private val connectionProvider: IDBusConnectionProvider) : IMediaService, MediaPlayer2, Player {
    private val mNReceiver: NotificationService.NotificationReceiver? = null
    private val hashing = Hashing.goodFastHash(64)

    private val busSuffix = "x" + Hashing.murmur3_32_fixed(42).hashLong(Date().time).toString()

    override fun sync() {
        connectionProvider.acquireDBusConnection { connection: DBusConnection ->
            connection.requestBusName("org.mpris.MediaPlayer2.x$busSuffix")
            connection.exportObject("/org/mpris/MediaPlayer2", this@MediaService)
        }
    }

    override fun unsync() {
        connectionProvider.acquireDBusConnection { connection: DBusConnection ->
            connection.unExportObject("/org/mpris/MediaPlayer2")
            connection.releaseBusName("org.mpris.MediaPlayer2.x$busSuffix")
        }
    }

    override fun onReset() {
        connectionProvider.acquireDBusConnection { connection: DBusConnection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("Metadata", Variant(getMetadata(), "a{sv}")) as Map<String, Variant<*>>?, listOf()))
        }
    }

    fun canQuit(): Boolean {
        return false
    }

    fun isFullscreen(): Boolean {
        return false
    }

    fun setFullscreen(_property: Boolean) {
    }

    fun canSetFullscreen(): Boolean {
        return false
    }

    fun canRaise(): Boolean {
        return false
    }

    fun hasTrackList(): Boolean {
        // TODO: Track List!!! :grin:
        return false
    }

    fun getIdentity(): String {
        return "Android"
    }

    fun getSupportedUriSchemes(): List<String> = listOf()

    fun getSupportedMimeTypes(): List<String> = listOf()

    override fun Raise() {}
    override fun Quit() {}
    fun getPlaybackStatus(): String {
        val controller = supervisor.mediaController ?: return "Stopped"
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isPlaying) return@runBlocking "Playing" else if (controller.playbackState == STATE_READY && !controller.playWhenReady) return@runBlocking "Paused" else return@runBlocking "Stopped"
        }
    }

    fun getLoopStatus(): String {
        val controller = supervisor.mediaController ?: return "None"
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking when (controller.repeatMode) {
                REPEAT_MODE_ALL -> "Playlist"
                REPEAT_MODE_ONE -> "Track"
                REPEAT_MODE_OFF -> "None"
                else -> throw IllegalStateException("Unexpected value: ${controller.repeatMode}")
            }
        }
    }

    fun setLoopStatus(_property: String) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SET_REPEAT_MODE)) {
                controller.repeatMode = when (_property) {
                    "None" -> REPEAT_MODE_OFF
                    "Track" -> REPEAT_MODE_ONE
                    "Playlist" -> REPEAT_MODE_ALL
                    else -> throw IllegalStateException("Unexpected value: $_property")
                }
            }
        }
    }

    fun getRate(): Double {
        val controller = supervisor.mediaController ?: return 1.0
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.playbackParameters.speed.toDouble()
        }
    }

    fun setRate(_property: Double) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SET_SPEED_AND_PITCH)) {
                controller.setPlaybackSpeed(_property.toFloat())
            }
        }
    }

    fun isShuffle(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SET_SHUFFLE_MODE)) {
                return@runBlocking controller.shuffleModeEnabled
            } else {
                return@runBlocking false
            }
        }
    }

    fun setShuffle(_property: Boolean) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SET_SHUFFLE_MODE)) {
                controller.shuffleModeEnabled = _property
            }
        }
    }

    private fun mediaToPath(packageName: String, item: MediaItem?): DBusPath {
        val mediaId = Hashing.combineOrdered(Lists.newArrayList(
                hashing.hashString(item?.mediaMetadata?.title ?: "", Charset.defaultCharset()),
                hashing.hashString(item?.mediaId ?: "", Charset.defaultCharset())))
        return DBusPath("/" + packageName.replace('.', '/') + "/" + mediaId)
    }

    private val currentMediaIdObjectPath get() = mediaToPath(supervisor.mediaController?.connectedToken?.packageName ?: "", supervisor.mediaController?.currentMediaItem)

    fun getMetadata(): Map<String, Variant<*>> {
        val dummy = Collections.singletonMap<String, Variant<*>>("mpris:trackid", Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")))

        val controller = supervisor.mediaController ?: return dummy
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.currentMediaItem != null) {
                return@runBlocking java.util.Map.of(
                        "mpris:trackid", Variant(currentMediaIdObjectPath),
                        "mpris:length", Variant(controller.contentDuration * 1000)
                )
            } else {
                return@runBlocking dummy
            }
        }
    }

    fun getVolume(): Double {
        // TODO:XXX:
        val controller = supervisor.mediaController ?: return 0.0
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.volume.toDouble()
        }
    }

    fun setVolume(_property: Double) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SET_VOLUME)) {
                controller.volume = _property.toFloat()
            }
        }
    }

    fun getPosition(): Long {
        val controller = supervisor.mediaController ?: return 0L
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM)) {
                return@runBlocking controller.currentPosition * 1000L
            } else {
                return@runBlocking 0L
            }
        }
    }

    fun getMinimumRate(): Double {
        return .25
    }

    fun getMaximumRate(): Double {
        return 2.0
    }

    fun canGoNext(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && controller.hasNextMediaItem()
        }
    }

    fun canGoPrevious(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && controller.hasPreviousMediaItem()
        }
    }

    fun canPlay(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.isCommandAvailable(COMMAND_PLAY_PAUSE)
        }
    }

    fun canPause(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.isCommandAvailable(COMMAND_PLAY_PAUSE)
        }
    }

    fun canSeek(): Boolean {
        val controller = supervisor.mediaController ?: return false
        return runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            return@runBlocking controller.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && controller.isCurrentMediaItemSeekable
        }
    }

    fun canControl(): Boolean = supervisor.mediaController != null

    override fun Next() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
                controller.seekToNextMediaItem()
            }
        }
    }

    override fun Previous() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
                controller.seekToPreviousMediaItem()
            }
        }
    }

    override fun Pause() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
                controller.pause()
            }
        }
    }

    override fun PlayPause() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
            }
        }
    }

    override fun Stop() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_STOP)) {
                controller.stop()
            } else {
                controller.pause()
            }
        }
    }

    override fun Play() {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
                controller.play()
            }
        }
    }

    override fun Seek(Offset: Long) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                controller.seekTo(controller.currentPosition + Offset / 1000L)
            }
        }
    }

    override fun SetPosition(TrackId: DBusPath, Position: Long) {
        val controller = supervisor.mediaController ?: return
        runBlocking(Handler(controller.applicationLooper).asCoroutineDispatcher()) {
            if (controller.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                controller.seekTo(Position / 1000L)
            }
        }
    }

    override fun OpenUri(Uri: String) {}

    override fun getObjectPath() = "/org/mpris/MediaPlayer2"

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(p0: String?, p1: String?): A {
        return when (p1) {
            "CanQuit" -> canQuit() as A
            "Fullscreen" -> isFullscreen() as A
            "CanSetFullscreen" -> canSetFullscreen() as A
            "CanRaise" -> canRaise() as A
            "HasTrackList" -> hasTrackList() as A
            "Identity" -> getIdentity() as A
            "SupportedUriSchemes" -> getSupportedUriSchemes() as A
            "SupportedMimeTypes" -> getSupportedMimeTypes() as A
            "PlaybackStatus" -> getPlaybackStatus() as A
            "LoopStatus" -> getLoopStatus() as A
            "Rate" -> getRate() as A
            "Shuffle" -> isShuffle() as A
            "Metadata" -> getMetadata() as A
            "Volume" -> getVolume() as A
            "Position" -> getPosition() as A
            "MinimumRate" -> getMinimumRate() as A
            "MaximumRate" -> getMaximumRate() as A
            "CanGoNext" -> canGoNext() as A
            "CanGoPrevious" -> canGoPrevious() as A
            "CanPlay" -> canPlay() as A
            "CanPause" -> canPause() as A
            "CanSeek" -> canSeek() as A
            "CanControl" -> canControl() as A
            else -> throw DBusException("cannot get $p1")
        }
    }

    override fun <A : Any?> Set(p0: String?, p1: String?, p2: A) {
        when (p1) {
            else -> throw DBusException("cannot set $p1")
        }
    }

    override fun GetAll(p0: String?): MutableMap<String, Variant<*>> {
        return when (p0) {
            "org.mpris.MediaPlayer2" -> mutableMapOf(
                    "CanQuit" to Variant(canQuit(), "b"),
                    "Fullscreen" to Variant(isFullscreen(), "b"),
                    "CanSetFullscreen" to Variant(canSetFullscreen(), "b"),
                    "CanRaise" to Variant(canRaise(), "b"),
                    "HasTrackList" to Variant(hasTrackList(), "b"),
                    "Identity" to Variant(getIdentity(), "s"),
                    "SupportedUriSchemes" to Variant(getSupportedUriSchemes(), "as"),
                    "SupportedMimeTypes" to Variant(getSupportedMimeTypes(), "as"),
            )
            "org.mpris.MediaPlayer2.Player" -> mutableMapOf(
                    "PlaybackStatus" to Variant(getPlaybackStatus(), "s"),
                    "LoopStatus" to Variant(getLoopStatus(), "s"),
                    "Rate" to Variant(getRate(), "d"),
                    "Shuffle" to Variant(isShuffle(), "b"),
                    "Metadata" to Variant(getMetadata(), "a{sv}"),
                    "Volume" to Variant(getVolume(), "d"),
                    "Position" to Variant(getPosition(), "x"),
                    "MinimumRate" to Variant(getMinimumRate(), "d"),
                    "MaximumRate" to Variant(getMaximumRate(), "d"),
                    "CanGoNext" to Variant(canGoNext(), "b"),
                    "CanGoPrevious" to Variant(canGoPrevious(), "b"),
                    "CanPlay" to Variant(canPlay(), "b"),
                    "CanPause" to Variant(canPause(), "b"),
                    "CanSeek" to Variant(canSeek(), "b"),
                    "CanControl" to Variant(canControl(), "b"),
            )
            else -> mutableMapOf()
        }
    }

    override fun isRemote(): Boolean = false

    override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(Player.Seeked(objectPath, newPosition.positionMs / 1000L))
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("PlaybackStatus", Variant(getPlaybackStatus())) as Map<String, Variant<*>>?, listOf()))
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("PlaybackStatus", Variant(getPlaybackStatus())) as Map<String, Variant<*>>?, listOf()))
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("Metadata", Variant(getMetadata(), "a{sv}")) as Map<String, Variant<*>>?, listOf()))
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("PlaybackStatus", Variant(getPlaybackStatus())) as Map<String, Variant<*>>?, listOf()))
        }
    }

    override fun onVolumeChanged(volume: Float) {
        connectionProvider.acquireDBusConnection { connection ->
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", Collections.singletonMap("Volume", Variant(getPlaybackStatus())) as Map<String, Variant<*>>?, listOf()))
        }
    }

    override fun onAvailableCommandsChanged(availableCommands: Commands) {
        connectionProvider.acquireDBusConnection { connection ->
            val map: Map<String, Variant<*>> = java.util.Map.of(
                    "CanGoNext", Variant(canGoNext()),
                    "CanGoPrevious", Variant(canGoPrevious()),
                    "CanPlay", Variant(canPlay()),
                    "CanPause", Variant(canPause()),
                    "CanSeek", Variant(canSeek()),
                    "CanControl", Variant(canControl()),
            )
            connection.sendMessage(PropertiesChanged(objectPath, "org.mpris.MediaPlayer2.Player", map, listOf()))
        }
    }
}