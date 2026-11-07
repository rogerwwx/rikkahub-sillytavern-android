package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

/** 一次整理独占的内存快照；工具仍复用正式库的路径、版本和大小检查。 */
class MemoryDraft internal constructor(
    internal val owner: MemoryRepository,
    internal val memoryId: String,
    internal val initial: List<MemoryFileEntity>,
) {
    internal val dao = MemoryDraftDAO(memoryId, initial)
    val repository = MemoryRepository(dao)
    internal var committed = false
}

/** 不注册进 Room；整理失败或取消时随草稿丢弃，不产生磁盘写入。 */
internal class MemoryDraftDAO(
    private val memoryId: String,
    initial: List<MemoryFileEntity>,
) : MemoryDAO {
    private val files = MutableStateFlow(initial)

    private fun requireStore(assistantId: String) {
        check(assistantId == memoryId) { "草稿不能访问其他记忆库" }
    }

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> {
        requireStore(assistantId)
        return files.map { it.sortedBy(MemoryFileEntity::path) }
    }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> {
        requireStore(assistantId)
        return files.value.sortedBy(MemoryFileEntity::path)
    }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? {
        requireStore(assistantId)
        return files.value.find { it.path == path }
    }

    override suspend fun upsertFile(file: MemoryFileEntity) = upsertFiles(listOf(file))

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        files.forEach { requireStore(it.assistantId) }
        val paths = files.map { it.path }.toSet()
        this.files.value = this.files.value.filterNot { it.path in paths } + files
    }

    override suspend fun deleteFile(assistantId: String, path: String) {
        requireStore(assistantId)
        files.value = files.value.filterNot { it.path == path }
    }

    override suspend fun deleteFilesOfAssistant(assistantId: String) {
        requireStore(assistantId)
        files.value = emptyList()
    }
}
