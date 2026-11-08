package ca.devdcs.backup.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.DftbDatabase
import ca.devdcs.backup.data.db.DftbMigrations
import ca.devdcs.backup.data.db.dao.CheckpointDao
import ca.devdcs.backup.data.db.dao.DestinationDao
import ca.devdcs.backup.data.db.dao.HashRecordDao
import ca.devdcs.backup.data.db.dao.HistoryDao
import ca.devdcs.backup.data.db.dao.ProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Builds the Room database once per process and exposes each DAO for injection. Migrations are
 * explicit ([DftbMigrations.all]); there is intentionally no destructive fallback, because this
 * database *is* the user's backup catalogue and must never be silently dropped.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DftbDatabase =
        Room.databaseBuilder(context, DftbDatabase::class.java, DftbDatabase.NAME)
            .addMigrations(*DftbMigrations.all)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(LoggingCallback)
            .build()

    @Provides
    fun provideProfileDao(database: DftbDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideDestinationDao(database: DftbDatabase): DestinationDao = database.destinationDao()

    @Provides
    fun provideHistoryDao(database: DftbDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideCheckpointDao(database: DftbDatabase): CheckpointDao = database.checkpointDao()

    @Provides
    fun provideHashRecordDao(database: DftbDatabase): HashRecordDao = database.hashRecordDao()

    /** Lifecycle breadcrumbs in the diagnostics log; useful when a user reports a lost catalogue. */
    private object LoggingCallback : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) {
            DftbLog.i(TAG, "Database ${DftbDatabase.NAME} created at schema v${DftbDatabase.VERSION}")
        }

        override fun onOpen(connection: SQLiteConnection) {
            DftbLog.d(TAG, "Database ${DftbDatabase.NAME} opened")
        }
    }

    private const val TAG = "Database"
}
