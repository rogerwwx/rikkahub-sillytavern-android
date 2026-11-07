package me.rerere.rikkahub.data.memory.semantic

import org.junit.Test

class BackgroundJevTest {
    @Test fun settingsRoundTrip() = SemanticDreamSettingsSmoke.main(emptyArray())
    @Test fun typedProtocolValidation() = JevProtocolSmoke.main(emptyArray())
    @Test fun persistentRetryPolicy() = SemanticDreamScheduleSmoke.main(emptyArray())
    @Test fun completeTurnCompressionPlan() = CompressionPlanSmoke.main(emptyArray())
    @Test fun boundedMemoryInjection() = SemanticMemoryInjectionSmoke.main(emptyArray())
    @Test fun completeCrossWindowLease() = CrossWindowCompressionSmoke.main(emptyArray())
}
