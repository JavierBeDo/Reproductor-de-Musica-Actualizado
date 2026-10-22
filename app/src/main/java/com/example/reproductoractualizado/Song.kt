package com.example.reproductoractualizado

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri
)

class SongRepository (private val context: Context){

    private val excludedFolders = setOf("whatsapp", "telegram")

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            excludedFolders.forEach {
                append(" AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?")
            }
        }

        val selectionArgs = excludedFolders.map { "%$it%" }.toTypedArray()

        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC, ${MediaStore.Audio.Media._ID} DESC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()){
                val id = c.getLong(idCol)
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Desconocida",
                    artist = c.getString(artistCol) ?: "Artista Desconocido",
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        songs
    }
}
