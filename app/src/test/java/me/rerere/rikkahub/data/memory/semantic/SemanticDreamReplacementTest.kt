package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDreamReplacementTest {
    private val now = 10_000L

    private fun source(id: String, valid: Boolean = true, createdAt: Long = 1000L) =
        DreamSource("window", "node:$id", "message:$id", 0, dreamHash(id), "user", createdAt, 0, 10, valid)

    private fun memory(
        id: String, status: String, origin: String = "dream", sources: List<DreamSource> = listOf(source(id)),
        extra: Map<String, JsonElement> = emptyMap(),
    ) = SemanticMemoryEntity(
        id = id, assistantId = "assistant", conversationId = "window", content = "事实：$id",
        status = status, createdAt = 1000L, updatedAt = 2000L, expiresAt = 100_000L,
        contentVersion = 7, embeddedVersion = 7,
        sourceJson = Json.encodeToString(sources),
        metadataJson = JsonObject(mapOf("origin" to JsonPrimitive(origin)) + extra).toString(),
    )

    private fun pair(
        oldStatus: String = "active", newStatus: String = "active",
        oldOrigin: String = "dream", newOrigin: String = "dream",
        oldSources: List<DreamSource> = listOf(source("old")),
        newSources: List<DreamSource> = listOf(source("new", valid = newStatus != "dormant")),
    ) = listOf(
        memory("old", oldStatus, oldOrigin, oldSources, mapOf("supersededBy" to JsonPrimitive("new"))),
        memory("new", newStatus, newOrigin, newSources, mapOf(
            "replaces" to JsonPrimitive("old"), "sourceWithdrawn" to JsonPrimitive(newStatus == "dormant"),
        )),
    )

    private fun metadata(memory: SemanticMemoryEntity, key: String, value: JsonElement) =
        memory.copy(metadataJson = JsonObject(dreamMetadata(memory) + (key to value)).toString())

    private fun apply(memories: List<SemanticMemoryEntity>, at: Long = now): List<SemanticMemoryEntity> {
        val changes = planDreamReplacementRecovery(memories, at).associateBy { it.after.id }
        return memories.map { changes[it.id]?.after ?: it }
    }

    private fun noChanges(memories: List<SemanticMemoryEntity>) {
        assertTrue(planDreamReplacementRecovery(memories, now).isEmpty())
    }

    @Test fun withdrawalAndRestorationRoundTripKeepsOnlySuccessorActive() {
        val initial = pair("superseded")
        noChanges(initial)
        val withdrawn = initial.map {
            withdrawDreamSources(it, setOf(source("new").evidenceId), now)
        }
        assertEquals(listOf("superseded", "dormant"), withdrawn.map { it.status })
        val recovered = apply(withdrawn)
        assertEquals(listOf("active", "dormant"), recovered.map { it.status })
        val restored = recovered.map {
            restoreDreamSources(it, setOf(source("new").evidenceId), now + 1)
        }
        assertEquals(listOf("active", "active"), restored.map { it.status })
        val final = apply(restored, now + 2)
        assertEquals(listOf("superseded", "active"), final.map { it.status })
        assertEquals(1, final.count { it.status == "active" })
        assertEquals(initial[0].metadataJson, final[0].metadataJson)
        assertEquals(initial[0].expiresAt, final[0].expiresAt)
        assertEquals(initial[0].content, final[0].content)
    }

    @Test fun stateChangesAreMinimalVersionedAndIdempotentInBothDirections() {
        for (input in listOf(pair(), pair("superseded", "dormant", newSources = listOf(source("new", false))))) {
            val mutation = planDreamReplacementRecovery(input, now).single()
            val old = input[0]
            val expectedStatus = if (old.status == "active") "superseded" else "active"
            assertEquals(old, mutation.before)
            assertEquals(old.copy(
                status = expectedStatus, updatedAt = now, contentVersion = 8, embeddedVersion = 8,
            ), mutation.after)
            val applied = apply(input)
            noChanges(applied)
            assertEquals(input[1], applied[1])
        }
    }

    @Test fun staleOrMissingEmbeddingVersionIsPreserved() {
        for (version in listOf<Int?>(null, 3, 9)) {
            val input = pair()
            val result = planDreamReplacementRecovery(listOf(input[0].copy(embeddedVersion = version), input[1]), now).single()
            assertEquals(8, result.after.contentVersion)
            assertEquals(version, result.after.embeddedVersion)
        }
    }

    @Test fun dreamNeedsAnyValidSupportForBothFacts() {
        val partial = listOf(source("one", false), source("two"))
        assertEquals("superseded", planDreamReplacementRecovery(pair(oldSources = partial, newSources = partial), now).single().after.status)
        noChanges(pair(oldSources = listOf(source("old", false))))
        noChanges(pair(newSources = listOf(source("new", false))))
        noChanges(pair("superseded", "dormant", oldSources = listOf(source("old", false))))
    }

    @Test fun consolidationNeedsAllSourcesBeforeSupersedingOrRecovering() {
        val partial = listOf(source("one", false), source("two"))
        noChanges(pair(oldOrigin = "consolidation", oldSources = partial))
        noChanges(pair(newOrigin = "consolidation", newSources = partial))
        noChanges(pair("superseded", "dormant", oldOrigin = "consolidation", oldSources = partial))
        val initial = pair("superseded", newOrigin = "consolidation", newSources = listOf(source("one"), source("two")))
        val withdrawn = initial.map { withdrawDreamSources(it, setOf(source("one").evidenceId), now) }
        assertEquals("dormant", withdrawn[1].status)
        val recovered = apply(withdrawn)
        assertEquals(listOf("active", "dormant"), recovered.map { it.status })
        val restored = recovered.map { restoreDreamSources(it, setOf(source("one").evidenceId), now + 1) }
        assertEquals(listOf("superseded", "active"), apply(restored).map { it.status })
    }

    @Test fun historicalWithdrawalFlagCannotRecoverPredecessorWhileDormantSuccessorIsSupported() {
        noChanges(pair("superseded", "dormant", newSources = listOf(source("new"))))
        noChanges(pair("superseded", "dormant", newSources = listOf(source("one", false), source("two"))))
        noChanges(pair("superseded", "dormant", newOrigin = "consolidation",
            newSources = listOf(source("one"), source("two"))))
    }

    @Test fun manualFlagsProtectBothEndpointsInBothDirections() {
        for (flag in listOf("manualEdited", "manualLifecycle", "manualRestored", "manualSupported")) {
            for (input in listOf(pair(), pair("superseded", "dormant"))) {
                for (index in input.indices) {
                    noChanges(input.mapIndexed { i, memory ->
                        if (i == index) metadata(memory, flag, JsonPrimitive(true)) else memory
                    })
                }
            }
        }
        assertEquals(1, planDreamReplacementRecovery(pair().map {
            metadata(it, "manualEdited", JsonPrimitive(false))
        }, now).size)
    }

    @Test fun ttlBoundaryIsRespectedForBothActiveFactsAndRecovery() {
        for (expiry in listOf(now - 1, now)) {
            val input = pair()
            noChanges(listOf(input[0].copy(expiresAt = expiry), input[1]))
            noChanges(listOf(input[0], input[1].copy(expiresAt = expiry)))
            val recovery = pair("superseded", "dormant")
            noChanges(listOf(recovery[0].copy(expiresAt = expiry), recovery[1]))
        }
        assertEquals(1, planDreamReplacementRecovery(pair().map { it.copy(expiresAt = now + 1) }, now).size)
        assertEquals(1, planDreamReplacementRecovery(pair().map { it.copy(expiresAt = null) }, now).size)
        val recovery = pair("superseded", "dormant")
        assertEquals("active", planDreamReplacementRecovery(listOf(recovery[0], recovery[1].copy(expiresAt = now - 1)), now).single().after.status)
    }

    @Test fun coreBypassesExpiryButNeverExplicitLifecycleStatus() {
        val input = pair().map { it.copy(isCore = true, expiresAt = now - 1) }
        assertEquals("superseded", planDreamReplacementRecovery(input, now).single().after.status)
        val recovery = pair("superseded", "dormant").map { it.copy(isCore = true, expiresAt = now - 1) }
        assertEquals("active", planDreamReplacementRecovery(recovery, now).single().after.status)
        for (status in listOf("expired", "archived", "forgotten", "dormant", "unknown")) {
            noChanges(listOf(input[0].copy(status = status), input[1]))
        }
        for (status in listOf("expired", "archived", "forgotten", "superseded", "unknown")) {
            noChanges(listOf(input[0], input[1].copy(status = status)))
        }
    }

    @Test fun assistantAndWindowIsolationAppliesInBothDirections() {
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            noChanges(listOf(input[0], input[1].copy(assistantId = "other")))
            noChanges(listOf(input[0], input[1].copy(conversationId = "other")))
            noChanges(input.map { it.copy(conversationId = "") })
            noChanges(input.map { it.copy(assistantId = "") })
        }
    }

    @Test fun bothDirectLinksMustAgreeAndWithdrawalMustBeExplicit() {
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            noChanges(listOf(input[0]))
            noChanges(listOf(input[1]))
            noChanges(listOf(metadata(input[0], "supersededBy", JsonPrimitive("missing")), input[1]))
            noChanges(listOf(input[0], metadata(input[1], "replaces", JsonPrimitive("other"))))
            noChanges(listOf(input[0].copy(metadataJson = """{"origin":"dream"}"""), input[1]))
            noChanges(listOf(input[0], input[1].copy(metadataJson = """{"origin":"dream","sourceWithdrawn":true}""")))
        }
        val recovery = pair("superseded", "dormant")
        noChanges(listOf(recovery[0], metadata(recovery[1], "sourceWithdrawn", JsonPrimitive(false))))
        noChanges(listOf(recovery[0], recovery[1].copy(metadataJson = """{"origin":"dream","replaces":"old"}""")))
        noChanges(pair("active", "dormant"))
        noChanges(pair("superseded", "active"))
    }

    @Test fun unknownOriginsAreNotAutomaticallyChanged() {
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            for (origin in listOf("import", "manual", "")) {
                noChanges(listOf(metadata(input[0], "origin", JsonPrimitive(origin)), input[1]))
                noChanges(listOf(input[0], metadata(input[1], "origin", JsonPrimitive(origin))))
            }
        }
    }

    @Test fun malformedMetadataIsSkippedWithoutThrowing() {
        val corrupt = listOf("bad", "[]", "null", """{"origin":[]}""",
            """{"origin":"dream","supersededBy":[]}""",
            """{"origin":"dream","supersededBy":"new","manualEdited":"false"}""",
            """{"origin":"dream","replaces":"old","sourceWithdrawn":"true"}""")
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            for (json in corrupt) {
                noChanges(listOf(input[0].copy(metadataJson = json), input[1]))
                noChanges(listOf(input[0], input[1].copy(metadataJson = json)))
            }
        }
    }

    @Test fun unverifiableOrMalformedSourcesProtectEitherEndpoint() {
        val goodSource = Json.encodeToString(source("valid"))
        val corrupt = listOf("bad", "{}", "null", "[]", "[{}]", """[{"kind":"import"}]""", "[$goodSource,{}]")
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            for (json in corrupt) {
                noChanges(listOf(input[0].copy(sourceJson = json), input[1]))
                noChanges(listOf(input[0], input[1].copy(sourceJson = json)))
            }
        }
    }

    @Test fun malformedSourceFieldsAndForeignSourceWindowAreSkipped() {
        val invalid = listOf(
            source("bad").copy(conversationId = "foreign"),
            source("bad").copy(nodeId = ""),
            source("bad").copy(messageId = ""),
            source("bad").copy(contentHash = ""),
            source("bad").copy(position = -1),
            source("bad").copy(start = -1),
            source("bad").copy(start = 10, end = 9),
            source("bad").copy(start = 10, end = 10),
            source("bad").copy(role = "system"),
        )
        for (input in listOf(pair(), pair("superseded", "dormant"))) {
            for (bad in invalid) {
                val json = Json.encodeToString(listOf(bad))
                noChanges(listOf(input[0].copy(sourceJson = json), input[1]))
                noChanges(listOf(input[0], input[1].copy(sourceJson = json)))
            }
        }
    }

    @Test fun sourceTimestampsDoNotChooseTheCurrentFact() {
        val input = pair(oldSources = listOf(source("old", createdAt = 9000L)),
            newSources = listOf(source("new", createdAt = 100L)))
        assertEquals("superseded", planDreamReplacementRecovery(input, now).single().after.status)
        assertEquals(apply(input).associate { it.id to it.status }, apply(input.reversed()).associate { it.id to it.status })
    }

    @Test fun selfLoopAndTwoWayCycleCannotDeactivateAllActiveFacts() {
        val self = memory("self", "active", extra = mapOf(
            "supersededBy" to JsonPrimitive("self"), "replaces" to JsonPrimitive("self"),
        ))
        noChanges(listOf(self))
        val input = pair()
        val cycle = listOf(
            metadata(input[0], "replaces", JsonPrimitive("new")),
            metadata(input[1], "supersededBy", JsonPrimitive("old")),
        )
        noChanges(cycle)
        assertEquals(2, apply(cycle).count { it.status == "active" })
        noChanges(listOf(cycle[0].copy(status = "superseded"), metadata(cycle[1].copy(status = "dormant",
            sourceJson = Json.encodeToString(listOf(source("new", false)))),
            "sourceWithdrawn", JsonPrimitive(true))))
    }

    @Test fun cycleFurtherAlongSupersededByIsSkippedWhileIndependentEdgeStillWorks() {
        val input = pair()
        val chain = listOf(
            input[0], metadata(input[1], "supersededBy", JsonPrimitive("third")),
            memory("third", "active", extra = mapOf(
                "supersededBy" to JsonPrimitive("fourth"), "replaces" to JsonPrimitive("new"),
            )),
            memory("fourth", "active", extra = mapOf(
                "supersededBy" to JsonPrimitive("third"), "replaces" to JsonPrimitive("third"),
            )),
        )
        noChanges(chain)
        val independent = listOf(
            memory("independent-old", "active", extra = mapOf("supersededBy" to JsonPrimitive("independent-new"))),
            memory("independent-new", "active", extra = mapOf("replaces" to JsonPrimitive("independent-old"))),
        )
        assertEquals("independent-old", planDreamReplacementRecovery(chain + independent, now).single().after.id)
    }

    @Test fun duplicateIdsAreAmbiguousAndSkipped() {
        val input = pair()
        noChanges(input + input[1].copy(content = "重复 ID 的另一条事实"))
        noChanges(input + input[0].copy(content = "重复 ID 的旧事实"))
    }
}
