package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.LibraryItem
import com.example.data.model.DownloadItem
import com.example.data.model.HistoryItem
import com.example.data.model.WatchedEpisode
import com.example.data.model.NotificationItem
import com.example.data.model.SupportMessage

@Database(
    entities = [
        LibraryItem::class,
        DownloadItem::class,
        HistoryItem::class,
        WatchedEpisode::class,
        NotificationItem::class,
        SupportMessage::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun supportDao(): SupportDao
    abstract fun libraryDao(): LibraryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun watchedEpisodeDao(): WatchedEpisodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create new table matching canonical LibraryItem schema
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `library_items_new` (
                        `libraryId` TEXT NOT NULL,
                        `tmdbId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `posterUrl` TEXT NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `isMovie` INTEGER NOT NULL,
                        PRIMARY KEY(`libraryId`)
                    )
                    """.trimIndent()
                )

                // 2. Safely migrate legacy data:
                //    - isMovie == 1 -> contentType = 'movie', libraryId = 'movie_' || id
                //    - isMovie == 0 -> contentType = 'tv', libraryId = 'tv_' || id (NEVER assumed anime)
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `library_items_new` (`libraryId`, `tmdbId`, `title`, `posterUrl`, `contentType`, `isMovie`)
                    SELECT
                        CASE WHEN `isMovie` = 1 THEN 'movie_' || `id` ELSE 'tv_' || `id` END,
                        `id`,
                        `title`,
                        `posterUrl`,
                        CASE WHEN `isMovie` = 1 THEN 'movie' ELSE 'tv' END,
                        `isMovie`
                    FROM `library_items`
                    """.trimIndent()
                )

                // 3. Drop legacy table and rename new table
                db.execSQL("DROP TABLE `library_items`")
                db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cinestream-db"
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
