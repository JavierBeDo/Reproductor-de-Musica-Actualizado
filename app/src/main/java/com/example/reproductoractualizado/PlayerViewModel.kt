package com.example.reproductoractualizado

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SongRepository(app)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var player: Player? = null
    private val controllerFuture: ListenableFuture<MediaController>

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            _state.update {
                it.copy(currentIndex = player?.currentMediaItemIndex ?: -1, positionMs = 0L)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update {
                    it.copy(durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L)
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update {it.copy(shuffle = shuffleModeEnabled)}
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(app, token).buildAsync()
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull()
                ?: return@addListener
            controller.addListener(listener)
            player = controller
            syncWithPlayer()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    _state.update { it.copy(positionMs = p.currentPosition) }
                }
                delay(500.milliseconds)
            }
        }
    }

    fun loadSongs() {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val songs = repository.loadSongs()
            _state.update { it.copy(songs = songs, loaded = true) }
            syncWithPlayer()
        }
    }

    private fun syncWithPlayer() {
        val p = player ?: return
        val s = _state.value
        if (!s.loaded) return

//        if (p.mediaItemCount == 0 && s.songs.isNotEmpty()) {
//            p.setMediaItems(s.songs.map { it.toMediaItem() })
//            p.prepare()
//        }

        val sameList = p.mediaItemCount == s.songs.size &&
                s.songs.indices.all { p.getMediaItemAt(it).mediaId == s.songs[it].id.toString() }

        if (!sameList && s.songs.isNotEmpty()) {
            val items = s.songs.map { it.toMediaItem() }
            val currentId = p.currentMediaItem?.mediaId
            val currentIndex = s.songs.indexOfFirst { it.id.toString() == currentId }

            if (currentIndex >= 0) {
                p.setMediaItems(items, currentIndex, p.currentPosition)
            } else {
                p.setMediaItems(items)
            }
            p.prepare()
        }

        val started = p.isPlaying || p.currentPosition > 0
        _state.update {
            it.copy(
                isPlaying = p.isPlaying,
                loopOne = p.repeatMode == Player.REPEAT_MODE_ONE,
                currentIndex = if (started) p.currentMediaItemIndex else -1,
                shuffle = p.shuffleModeEnabled,
                positionMs = p.currentPosition,
                durationMs = p.duration.coerceAtLeast(0L)
            )
        }

    }

    fun playAt(index: Int) {
        val p = player ?: return
        if (index !in _state.value.songs.indices) return
        p.seekTo(index, 0L)
        p.play()
        _state.update { it.copy(currentIndex = index, positionMs = 0L) }
    }

    fun togglePlayPause() {
        val p = player ?: return
        val s = _state.value
        if (s.songs.isEmpty()) return
        when {
            s.currentIndex == -1 -> playAt(0)
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun next() {
        val p = player ?: return
        val s = _state.value
        if (s.songs.isEmpty()) return
        if (p.shuffleModeEnabled){
            if(p.hasNextMediaItem()) p.seekToNextMediaItem()
            else p.seekTo(p.currentTimeline.getFirstWindowIndex(true), 0L)
            p.play()
        } else {
            playAt((s.currentIndex + 1) % s.songs.size)
        }
    }

    fun previous() {
        val p = player ?: return
        val s = _state.value
        if (s.songs.isEmpty()) return
        if(p.shuffleModeEnabled){
            if(p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            else p.seekTo(p.currentTimeline.getLastWindowIndex(true), 0L)
            p.play()
        } else {
            playAt(if(s.currentIndex > 0) s.currentIndex - 1 else s.songs.size - 1)
        }
    }

    fun toggleLoop() {
        val p = player ?: return
        val loop = !_state.value.loopOne
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        _state.update { it.copy(loopOne = loop) }
    }

    fun toggleShuffle(){
        val p = player ?: return
        val enabled = !p.shuffleModeEnabled
        p.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffle = enabled) }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    override fun onCleared() {
        player?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }

    // El título y el artista son los que se ven en la notificación y en la pantalla de bloqueo
    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()
}