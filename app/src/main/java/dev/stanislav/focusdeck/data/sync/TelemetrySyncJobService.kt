package dev.stanislav.focusdeck.data.sync

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Public JobScheduler lifecycle; no foreground/always-on daemon or hidden boot connection. */
class TelemetrySyncJobService:JobService() {
    private val executor=Executors.newSingleThreadExecutor { task -> Thread(task,"FocusDeckTelemetryRetry").apply { isDaemon=true } }
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean {
        stopped.set(false)
        executor.execute {
            val success=TelemetrySyncClient(applicationContext,restoreSchedule=false).retryBlocking()
            if(!stopped.get())jobFinished(params,!success)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { stopped.set(true);return true }
    override fun onDestroy(){stopped.set(true);executor.shutdownNow();super.onDestroy()}
}
