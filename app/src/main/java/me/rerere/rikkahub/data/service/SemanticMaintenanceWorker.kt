package me.rerere.rikkahub.data.service

import android.content.Context
import androidx.work.*
import me.rerere.rikkahub.data.memory.semantic.SemanticMaintenanceEngine
import me.rerere.rikkahub.data.memory.semantic.SemanticMaintenanceManager
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryRepository
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** WorkManager 自身恢复排程；到运行窗口前主动续排，取消保留已落库向量。 */
class SemanticMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("jobId") ?: return Result.failure()
        val manager = SemanticMaintenanceManager(GlobalContext.get().get<SemanticMemoryRepository>())
        val started = System.nanoTime()
        SemanticMaintenanceEngine(manager).run(id) {
            System.nanoTime() - started > TimeUnit.MINUTES.toNanos(8)
        }
        val job = manager.load(id) ?: return Result.success()
        return if (job.status == "pending") Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<SemanticMaintenanceWorker>()
                .setInputData(workDataOf("jobId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("semantic-rebuild-$id", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("semantic-rebuild-$id")
        }
    }
}
