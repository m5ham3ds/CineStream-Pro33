package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.LibraryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items")
    fun getAllItems(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId LIMIT 1")
    suspend fun getItemById(libraryId: String): LibraryItem?

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE libraryId = :libraryId)")
    fun isItemInLibrary(libraryId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND contentType = :contentType)")
    fun isItemInLibrary(tmdbId: String, contentType: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND (contentType = 'tv' OR contentType = 'anime'))")
    fun isSeriesInLibrary(tmdbId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId)")
    fun isItemInLibraryByTmdbId(tmdbId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: LibraryItem)

    @Delete
    suspend fun deleteItem(item: LibraryItem)

    @Query("DELETE FROM library_items WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("DELETE FROM library_items")
    suspend fun clearAll()
}
