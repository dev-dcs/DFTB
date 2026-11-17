package ca.devdcs.backup.di

import ca.devdcs.backup.core.logging.DftbLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * A process-lifetime scope for work that must outlive any screen (persisting a preference after
 * the ViewModel is gone, re-registering channels, journal flushes). A SupervisorJob so one failed
 * child never cancels the others; failures are logged, never swallowed silently.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            DftbLog.e("AppScope", "Uncaught exception in application scope", throwable)
        }
        return CoroutineScope(SupervisorJob() + dispatcher + handler)
    }
}
