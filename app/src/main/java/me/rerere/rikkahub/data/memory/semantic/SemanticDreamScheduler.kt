package me.rerere.rikkahub.data.memory.semantic

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.MemoryDreamKeepAlive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.service.SemanticDreamWorker
import kotlin.uuid.Uuid

/** WorkManager 负责持久排程；手动和自动始终调用同一 SemanticDreamManager。 */
class SemanticDreamScheduler(
    private val scope: CoroutineScope,
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val manager: SemanticDreamManager,
    private val repository: SemanticMemoryRepository,
    private val keepAlive: MemoryDreamKeepAlive = MemoryDreamKeepAlive { AutoCloseable {} },
) {
    init {
        scope.launch {
            var previous = emptyMap<String, String>()
            settingsStore.settingsFlow.map { settings -> settings.assistants.associate { assistant ->
                assistant.id.toString() to configurationHash(settings, assistant)
            } }.distinctUntilChanged().collect { hashes ->
                val settings = settingsStore.awaitLoaded()
                (previous.keys - hashes.keys).forEach { id -> cancel(id, cancelPeriodic = true) }
                settings.assistants.forEach { assistant ->
                    val id = assistant.id.toString()
                    val changed = previous[id] != null && previous[id] != hashes[id]
                    if (changed) invalidateConfiguration(id)
                    if (assistant.semanticMemory.enabled && assistant.semanticMemory.dream.automatic) {
                        SemanticDreamWorker.periodic(context, id, hashes.getValue(id), assistant.semanticMemory.dream.reviewIntervalHours)
                        if (changed) enqueue(id, hashes.getValue(id), "configuration:${hashes[id]}", 0, manual = true)
                    } else if (!assistant.semanticMemory.enabled) SemanticDreamWorker.cancel(context, id)
                    else SemanticDreamWorker.cancelPeriodic(context, id)
                }
                previous = hashes
            }
        }
    }

    fun onTurnFinished(conversation: Conversation) = scope.launch {
        val pending = manager.pendingOf(conversation) ?: return@launch
        val (assistant, hash) = current(pending.assistantId) ?: return@launch
        val config = assistant.semanticMemory.dream.normalized()
        val key = conversation.id.toString() + ":" + conversation.messageNodes.lastOrNull()?.currentMessage?.id
        enqueue(pending.assistantId, hash, key,
            if (pending.turns >= config.minCompletedTurns) 0 else config.idleMinutes * 60_000L,
            conversationId = pending.conversationId)
    }

    fun onAppHidden() = scope.launch { acceleratePending() }
    fun onConversationLeft(conversationId: Uuid) = scope.launch {
        settingsStore.awaitLoaded().assistants.forEach { assistant ->
            states(assistant.id.toString()).filter { it.conversationId == conversationId.toString() }.forEach { accelerate(it) }
        }
    }

    suspend fun resumePending() {
        manager.pendingAssistants().forEach { id ->
            val (_, hash) = current(id) ?: return@forEach
            val states = states(id)
            if (states.isEmpty()) enqueue(id, hash, "startup", 0)
            // 失败状态也要重启：窗口里还有待处理历史时会继续自动重试。
            else states.filter { it.status in setOf("pending", "running", "failed") }.forEach { state ->
                val recovered = state.copy(status = "pending")
                put(recovered)
                SemanticDreamWorker.schedule(context, id, hash, recovered.dueAt - System.currentTimeMillis(),
                    conversationId = recovered.conversationId)
            }
        }
    }

    /** 应用级作用域让用户离开设置页后继续运行；失败草稿和窗口由 Manager 恢复。 */
    fun manual(assistantId: String, conversationId: String? = null, range: SemanticDreamRange? = null) = scope.launch {
        SemanticDreamWorker.cancelOneTime(context, assistantId, conversationId)
        val (_, hash) = current(assistantId, requireAutomatic = false) ?: return@launch
        val key = "manual:${System.currentTimeMillis()}"
        enqueue(assistantId, hash, key, 0, manual = true, dispatch = false, conversationId = conversationId, range = range)
        SemanticDreamWorker.schedule(context, assistantId, hash, 0, manual = true, conversationId = conversationId)
    }

    suspend fun cancel(assistantId: String, cancelPeriodic: Boolean = false, conversationId: String? = null) {
        if (cancelPeriodic) SemanticDreamWorker.cancel(context, assistantId)
        else if (conversationId != null) SemanticDreamWorker.cancelOneTime(context, assistantId, conversationId)
        else states(assistantId).forEach { SemanticDreamWorker.cancelOneTime(context, assistantId, it.conversationId) }
        manager.cancel(assistantId, conversationId)
        states(assistantId).filter { conversationId == null || it.conversationId == conversationId }
            .forEach { put(it.copy(status = "cancelled", error = "已取消；来源窗口保留")) }
    }

    suspend fun load(assistantId: String, conversationId: String? = null): SemanticDreamScheduleState? =
        repository.dao.getJob(id(assistantId, conversationId))?.let {
        Json.decodeFromString<SemanticDreamScheduleState>(it.payloadJson)
    }

    private suspend fun states(assistantId: String): List<SemanticDreamScheduleState> = repository.dao.jobs(assistantId)
        .filter { it.kind == KIND }.map { Json.decodeFromString<SemanticDreamScheduleState>(it.payloadJson) }

    /** 设置观察可能稍晚到达，只撤销旧配置的任务，保留保存设置后新建的队列。 */
    private suspend fun invalidateConfiguration(assistantId: String) = repository.database.withTransaction {
        val settings = settingsStore.awaitLoaded()
        val hash = settings.assistants.find { it.id.toString() == assistantId }?.let { configurationHash(settings, it) }
        states(assistantId).filter {
            it.configurationHash != hash && it.status in setOf("pending", "running")
        }.forEach {
            SemanticDreamWorker.cancelOneTime(context, assistantId, it.conversationId)
            put(it.copy(status = "cancelled", error = "排队时的模式、模型或自动开关已改变"))
        }
    }

    /** 旧队列不能用已失效的模式、模型或自动开关运行。 */
    suspend fun runScheduled(assistantId: String, hash: String, periodic: Boolean, manual: Boolean = false,
        conversationId: String? = null): String {
        val effective = current(assistantId, requireAutomatic = !manual)
        if (effective == null || hash != effective.second) {
            repository.database.withTransaction {
                load(assistantId, conversationId)?.takeIf { it.configurationHash == hash && it.status in setOf("pending", "running") }?.let {
                    put(it.copy(status = "cancelled", error = "排队时的模式、模型或自动开关已改变"))
                }
            }
            return "cancelled"
        }
        val assistant = effective.first
        val config = assistant.semanticMemory.dream.normalized()
        if (periodic) {
            val interval = config.reviewIntervalHours * 3_600_000L
            val key = "periodic:${System.currentTimeMillis() / interval}"
            enqueue(assistantId, hash, key, 0, dispatch = false, conversationId = conversationId)
        }
        val state = load(assistantId, conversationId) ?: return "completed"
        if (state.configurationHash != hash || state.status !in setOf("pending", "running")) return state.status
        if (state.dueAt > System.currentTimeMillis()) return "pending"
        put(state.copy(status = "running"))
        val hold = keepAlive.acquire()
        try {
            val result = if (state.manualRange != null) manager.runRange(assistantId, checkNotNull(conversationId), state.manualRange)
                else manager.runAssistant(assistantId, automatic = !manual, conversationId = conversationId)
            return repository.database.withTransaction {
                val latest = load(assistantId, conversationId) ?: return@withTransaction "cancelled"
                if (latest.configurationHash != hash || latest.status == "cancelled") return@withTransaction "cancelled"
                if (latest.turnKey != state.turnKey) {
                    put(latest.copy(status = "pending"))
                    return@withTransaction "pending"
                }
                val next = latest.afterRun(result.status, System.currentTimeMillis(), result.error)
                put(next)
                next.status
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val latest = load(assistantId, conversationId)
                if (latest?.status == "running" && latest.configurationHash == hash) put(latest.copy(status = "pending"))
            }
            throw cancel
        } catch (_: Exception) {
            val latest = load(assistantId, conversationId) ?: return "failed"
            if (latest.configurationHash != hash || latest.status == "cancelled") return "cancelled"
            val next = latest.afterRun("failed", System.currentTimeMillis(), "做梦运行失败，稍后自动重试")
            put(next)
            return next.status
        } finally { hold.close() }
    }

    private suspend fun enqueue(assistantId: String, hash: String, turnKey: String, delayMillis: Long,
        manual: Boolean = false, dispatch: Boolean = true, conversationId: String? = null, range: SemanticDreamRange? = null) {
        repository.database.withTransaction {
            if (current(assistantId, requireAutomatic = false)?.second != hash) return@withTransaction
            val old = load(assistantId, conversationId)
            // 新聊天触发不能替换尚未完成的手动范围及其固定终点。
            if (!manual && old?.manualRange != null && old.status in setOf("pending", "running") &&
                old.configurationHash == hash) return@withTransaction
            val proposed = old?.rescheduled(hash, turnKey, System.currentTimeMillis(), delayMillis, manual)
                ?: SemanticDreamScheduleState(assistantId, hash, turnKey, System.currentTimeMillis() + delayMillis,
                    conversationId = conversationId)
            val next = if (old?.status == "running" && old.configurationHash == hash && !manual)
                proposed.copy(status = "running") else proposed.copy(manualRange = range)
            put(next)
            if (dispatch && next.status == "pending") SemanticDreamWorker.schedule(context, assistantId, hash,
                next.dueAt - System.currentTimeMillis(), conversationId = conversationId)
        }
    }

    private suspend fun acceleratePending() {
        settingsStore.awaitLoaded().assistants.forEach { assistant -> states(assistant.id.toString()).forEach { accelerate(it) } }
    }

    private suspend fun accelerate(state: SemanticDreamScheduleState) {
        // 已到期的任务无需重排；REPLACE 会取消刚开始运行的手动任务。
        if (state.status != "pending" || state.attempts > 0 || state.dueAt <= System.currentTimeMillis()) return
        val (_, hash) = current(state.assistantId) ?: return
        if (state.configurationHash != hash) return
        val accelerated = repository.database.withTransaction {
            val latest = load(state.assistantId, state.conversationId) ?: return@withTransaction false
            if (latest != state) return@withTransaction false
            put(state.copy(dueAt = System.currentTimeMillis()))
            true
        }
        if (accelerated) SemanticDreamWorker.schedule(context, state.assistantId, hash, 0, conversationId = state.conversationId)
    }

    private suspend fun current(assistantId: String, requireAutomatic: Boolean = true): Pair<Assistant, String>? {
        val settings = settingsStore.awaitLoaded()
        val assistant = settings.assistants.find { it.id.toString() == assistantId } ?: return null
        if (!assistant.semanticMemory.enabled || (requireAutomatic && !assistant.semanticMemory.dream.automatic)) return null
        return assistant to configurationHash(settings, assistant)
    }

    private suspend fun put(state: SemanticDreamScheduleState) = repository.dao.putJob(
        SemanticJobEntity(id(state.assistantId, state.conversationId), state.assistantId, KIND, Json.encodeToString(state),
            conversationId = state.conversationId))

    companion object {
        const val KIND = "dream_schedule"
        private fun id(assistantId: String, conversationId: String?) = "dream-schedule:$assistantId:${conversationId ?: "all"}"
        fun configurationHash(settings: Settings, assistant: Assistant): String {
            val memory = assistant.semanticMemory.normalized()
            val model = settings.findModelById(memory.dream.modelId ?: settings.chatModelId)
            val provider = model?.findProvider(settings.providers)?.copyProvider(models = emptyList())
            return semanticContentHash(assistant.compressionMemoryStream.toString() + Json.encodeToString(memory) + model?.let { Json.encodeToString(it.copy(providerOverwrite = null)) } +
                provider?.let { Json.encodeToString(it) })
        }
    }
}
