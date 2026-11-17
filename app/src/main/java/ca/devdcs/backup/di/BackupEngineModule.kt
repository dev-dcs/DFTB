package ca.devdcs.backup.di

import ca.devdcs.backup.core.pipeline.engine.AesGcmJobKeyPreparer
import ca.devdcs.backup.core.pipeline.engine.AndroidBackupMessages
import ca.devdcs.backup.core.pipeline.engine.AndroidDeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.AppStagingArea
import ca.devdcs.backup.core.pipeline.engine.BackupMessages
import ca.devdcs.backup.core.pipeline.engine.BackupSourceFactory
import ca.devdcs.backup.core.pipeline.engine.DefaultBackupSourceFactory
import ca.devdcs.backup.core.pipeline.engine.DeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.pipeline.engine.ForegroundServiceJobLauncher
import ca.devdcs.backup.core.pipeline.engine.JobKeyPreparer
import ca.devdcs.backup.core.pipeline.engine.JobServiceLauncher
import ca.devdcs.backup.core.pipeline.engine.MaskerProvider
import ca.devdcs.backup.core.pipeline.engine.SivMaskerProvider
import ca.devdcs.backup.core.pipeline.engine.StagingArea
import ca.devdcs.backup.core.pipeline.engine.SystemEngineClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the backup engine's platform seams to their Android implementations, keeping
 * [ca.devdcs.backup.core.pipeline.engine.BackupEngine] itself free of Android types (and testable
 * on the JVM), and its source seam to the real partition/app/file/TWRP factories.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupEngineModule {

    @Binds
    abstract fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    @Binds
    abstract fun bindStagingArea(impl: AppStagingArea): StagingArea

    @Binds
    abstract fun bindJobServiceLauncher(impl: ForegroundServiceJobLauncher): JobServiceLauncher

    @Binds
    abstract fun bindEngineClock(impl: SystemEngineClock): EngineClock

    @Binds
    abstract fun bindBackupMessages(impl: AndroidBackupMessages): BackupMessages

    @Binds
    abstract fun bindBackupSourceFactory(impl: DefaultBackupSourceFactory): BackupSourceFactory

    @Binds
    abstract fun bindJobKeyPreparer(impl: AesGcmJobKeyPreparer): JobKeyPreparer

    @Binds
    abstract fun bindMaskerProvider(impl: SivMaskerProvider): MaskerProvider
}
