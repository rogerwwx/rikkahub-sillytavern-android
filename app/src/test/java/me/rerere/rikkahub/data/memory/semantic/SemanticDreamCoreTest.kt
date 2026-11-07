package me.rerere.rikkahub.data.memory.semantic

import org.junit.Test

class SemanticDreamCoreTest {
    @Test fun incrementalSourcesAndLifecycle() = SemanticDreamCoreSmoke.main(emptyArray())
    @Test fun atomicUpdatesAndEvidenceProtection() = SemanticDreamCommitSmoke.main(emptyArray())
}
