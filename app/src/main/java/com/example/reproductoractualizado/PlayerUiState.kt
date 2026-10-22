package com.example.reproductoractualizado

data class PlayerUiState(
    val songs: List<Song> = emptyList(),
    val loaded: Boolean = false,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val loopOne: Boolean = false,
    val shuffle: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)





