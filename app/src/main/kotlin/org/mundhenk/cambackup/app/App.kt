package org.mundhenk.cambackup.app

import android.app.Application
import androidx.work.Configuration

/**
 * WorkManager is initialised on demand (the default initializer is removed from the manifest)
 * so the instrumented tests can swap in their synchronous test instance.
 */
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
