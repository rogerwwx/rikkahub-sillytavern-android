package me.rerere.rikkahub.data.service

import android.content.Context
import androidx.work.*
import me.rerere.rikkahub.data.memory.semantic.SemanticDreamScheduler
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** Android 可延后执行；来源、错误和重试上限由持久任务维护。 */
class SemanticDreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val assistantId = inputData.getString("assistantId") ?: return Result.failure()
        val hash = inputData.getString("configurationHash") ?: return Result.failure()
        val periodic = inputData.getBoolean("periodic", false)
        val manual = inputData.getBoolean("manual", false)
        val conversationId = inputData.getString("conversationId")
        val scheduler = GlobalContext.get().get<SemanticDreamScheduler>()
        val status = scheduler.runScheduled(assistantId, hash, periodic, manual, conversationId)
        // PeriodicWorkRequest 的周期不能当作失败重试；后续片段另建有界一次任务。
        if (periodic && status == "pending") {
            val state = scheduler.load(assistantId, conversationId)
            schedule(applicationContext, assistantId, hash, state?.dueAt?.minus(System.currentTimeMillis()) ?: 30000,
                conversationId = conversationId)
            return Result.success()
        }
        return if (status == "pending") Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context, assistantId: String, hash: String, delayMillis: Long, manual: Boolean = false,
            conversationId: String? = null) {
            val request = OneTimeWorkRequestBuilder<SemanticDreamWorker>()
                .setInputData(workDataOf("assistantId" to assistantId, "configurationHash" to hash,
                    "manual" to manual, "conversationId" to conversationId))
                .addTag("semantic-dream:$assistantId")
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(oneTimeName(assistantId, conversationId), ExistingWorkPolicy.REPLACE, request)
        }

        fun scheduleManual(context: Context, assistantId: String, conversationId: String? = null) {
            GlobalContext.get().get<SemanticDreamScheduler>().manual(assistantId, conversationId)
        }

        fun periodic(context: Context, assistantId: String, hash: String, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<SemanticDreamWorker>(intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setInputData(workDataOf("assistantId" to assistantId, "configurationHash" to hash, "periodic" to true))
                .addTag("semantic-dream:$assistantId")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("semantic-dream-review-$assistantId", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun oneTimeName(assistantId: String, conversationId: String?) =
            "semantic-dream-$assistantId:${conversationId ?: "all"}"

        fun cancelOneTime(context: Context, assistantId: String, conversationId: String? = null) {
            WorkManager.getInstance(context).cancelUniqueWork(oneTimeName(assistantId, conversationId))
        }
        fun cancel(context: Context, assistantId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag("semantic-dream:$assistantId")
        }
        fun cancelPeriodic(context: Context, assistantId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("semantic-dream-review-$assistantId")
        }
    }
}
