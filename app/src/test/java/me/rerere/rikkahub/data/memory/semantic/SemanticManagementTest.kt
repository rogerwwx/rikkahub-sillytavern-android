package me.rerere.rikkahub.data.memory.semantic

import org.junit.Test

/** 同一组回放可经 Gradle/JUnit 或无 SDK 的 Kotlin CLI 执行。 */
class SemanticManagementTest {
    @Test fun maintenanceBatchesRecoveryAndSelection() = semanticMaintenanceSmoke()
    @Test fun transferMetadataLifecycleAndValidation() = semanticTransferSmoke()
    @Test fun persistentDiagnosticEventAccounting() = semanticDiagnosticsSmoke()
}
