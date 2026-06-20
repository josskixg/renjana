package com.fesu.renjana.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Renjana
 */
@Database(
    entities = [InstanceEntity::class, GoogleAccountEntity::class],
    version = 4,
    exportSchema = false
)
abstract class RenjanaDatabase : RoomDatabase() {
    
    abstract fun instanceDao(): InstanceDao
    abstract fun googleAccountDao(): GoogleAccountDao

    companion object {
        @Volatile
        private var INSTANCE: RenjanaDatabase? = null

        fun getInstance(context: Context): RenjanaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RenjanaDatabase::class.java,
                    "renjana_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
