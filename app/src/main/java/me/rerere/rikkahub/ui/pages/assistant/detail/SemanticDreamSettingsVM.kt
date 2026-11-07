package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.memory.semantic.*

class SemanticDreamSettingsVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore,
    private val repository: SemanticMemoryRepository,
    private val scheduler: SemanticDreamScheduler,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly,
        me.rerere.rikkahub.data.datastore.Settings.dummy())
    val schedule = MutableStateFlow<SemanticDreamScheduleState?>(null)
    val jevEvents = MutableStateFlow<List<SemanticDiagnosticEvent>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    schedule.value = scheduler.load(assistantId)
                    jevEvents.value = repository.dao.events(assistantId).filter { it.operation == "jev" }.take(10)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message.value = "暂时无法读取做梦进度" }
                delay(2000)
            }
        }
    }

    fun configure(transform: (SemanticDreamSettings) -> SemanticDreamSettings) = viewModelScope.launch {
        settingsStore.update { state -> state.copy(assistants = state.assistants.map { assistant ->
            if (assistant.id.toString() == assistantId) assistant.copy(semanticMemory = assistant.semanticMemory.copy(
                dream = transform(assistant.semanticMemory.dream).normalized())) else assistant
        }) }
    }

    fun dreamNow() { scheduler.manual(assistantId) }
    fun cancel() = viewModelScope.launch { scheduler.cancel(assistantId) }
}
