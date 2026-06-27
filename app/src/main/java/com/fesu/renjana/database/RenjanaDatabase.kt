package com.fesu.renjana.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for Renjana
 */
@Database(
    entities = [InstanceEntity::class, GoogleAccountEntity::class, InstanceAppEntity::class],
    version = 6,
    exportSchema = false
)
abstract class RenjanaDatabase : RoomDatabase() {
    
    abstract fun instanceDao(): InstanceDao
    abstract fun googleAccountDao(): GoogleAccountDao
    abstract fun instanceAppDao(): InstanceAppDao

    companion object {
        @Volatile
        private var INSTANCE: RenjanaDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS instance_apps (
                        instanceId TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL DEFAULT '',
                        versionName TEXT NOT NULL DEFAULT '',
                        versionCode INTEGER NOT NULL DEFAULT 0,
                        apkPath TEXT NOT NULL DEFAULT '',
                        iconPath TEXT,
                        addedAt INTEGER NOT NULL DEFAULT 0,
                        lastLaunched INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (instanceId, packageName),
                        FOREIGN KEY (instanceId) REFERENCES instances(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_instance_apps_instanceId ON instance_apps(instanceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_instance_apps_packageName ON instance_apps(packageName)")
                // Migrate existing 1:1 data to new join table
                db.execSQL("""
                    INSERT OR IGNORE INTO instance_apps (instanceId, packageName, appName, versionName, versionCode, apkPath, iconPath, addedAt)
                    SELECT id, packageName, appName, versionName, versionCode, apkPath, iconPath, createdAt
                    FROM instances
                    WHERE packageName IS NOT NULL AND packageName != ''
                """.trimIndent())
                // Add containerName column to instances (default empty, backward-compat)
                db.execSQL("ALTER TABLE instances ADD COLUMN containerName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): RenjanaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RenjanaDatabase::class.java,
                    "renjana_database"
                )
                .addMigrations(MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
