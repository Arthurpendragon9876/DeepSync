package org.koitharu.kotatsu.scrobbling.common.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerSynchronizer
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Automatic tracker synchronisation: every few hours, titles that are on more than one tracker are brought
 * to the furthest progress any of them has. Does nothing unless auto-sync is on and two trackers are signed in.
 */
@HiltWorker
class TrackerSyncWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val settings: AppSettings,
	private val synchronizer: TrackerSynchronizer,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		if (!settings.isTrackerAutoSyncEnabled || !synchronizer.canSync) {
			return Result.success()
		}
		val report = runCatchingCancellable { synchronizer.synchronize() }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
		// Offline or a tracker down: try again soon, but not forever (the next period tries anyway)
		val incomplete = report == null || (report.unreachable.isNotEmpty() && report.updated == 0)
		return if (incomplete && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<TrackerSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
				.setConstraints(constraints(batteryNotLow = true))
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
			workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME).await()
		}

		override suspend fun isScheduled(): Boolean = workManager
			.awaitUniqueWorkInfoByName(PERIODIC_WORK_NAME)
			.any { !it.state.isFinished }

		/** One run right now, e.g. just after a second tracker was signed in. */
		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<TrackerSyncWorker>()
				.setConstraints(constraints(batteryNotLow = false))
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
		}

		private fun constraints(batteryNotLow: Boolean) = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(batteryNotLow)
			.build()
	}

	private companion object {

		const val PERIOD_HOURS = 12L
		const val MAX_ATTEMPTS = 3
		const val PERIODIC_WORK_NAME = "tracker_auto_sync"
		const val IMMEDIATE_WORK_NAME = "tracker_auto_sync_now"
	}
}
