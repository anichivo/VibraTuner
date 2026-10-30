package com.personal.vibratuner.data.local.db

import android.content.Context

/**
 * База данных приложения для треков, будильников и микрозвуков.
 */
abstract class AppDatabase {
    abstract fun trackDao(): TrackDao
    abstract fun alarmDao(): AlarmDao
    abstract fun microsoundDao(): MicrosoundDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SQLiteAppDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
