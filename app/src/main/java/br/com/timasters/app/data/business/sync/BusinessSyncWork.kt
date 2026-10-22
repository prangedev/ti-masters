package br.com.timasters.app.data.business.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [BusinessSyncer] through WorkManager, which waits for a network connection, survives the
 * app being closed and retries with backoff after failures.
 */
@Singleton
class BusinessSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * Called after every local change. The short delay groups quick consecutive edits into one
     * upload (each new request replaces the pending one).
     */
    fun requestSync(delaySeconds: Long = EDIT_DEBOUNCE_SECONDS) {
        val request = OneTimeWorkRequestBuilder<BusinessSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun syncNow() = requestSync(delaySeconds = 0)

    private companion object {
        const val WORK_NAME = "business-sync"
        const val EDIT_DEBOUNCE_SECONDS = 2L
    }
}

@HiltWorker
class BusinessSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: BusinessSyncer
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncer.sync()) {
        null -> Result.success()
        // Retrying cannot fix a missing plan; the next edit or app start tries again.
        SyncError.PLAN_REQUIRED -> Result.success()
        SyncError.OFFLINE, SyncError.UNKNOWN ->
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
