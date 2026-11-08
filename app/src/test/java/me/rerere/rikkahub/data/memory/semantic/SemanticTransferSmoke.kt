package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*

fun main() = semanticTransferSmoke()

fun semanticTransferSmoke() {
    val records = listOf(
        SemanticTransferRecord("a", "from", "original-window", "李明答应周五归还相机", "planned", .9, .8, true,
            "active", 100, 200, 1000, 3, Json.parseToJsonElement("""{"childIds":["b"],"extra":{"未知":7}}""").jsonObject,
            Json.parseToJsonElement("""[{"messageId":"m1","text":"承诺"}]""").jsonArray),
        SemanticTransferRecord("b", "from", "original-window", "李明是同事", "relational", .7, 1.0, false,
            "archived", 100, 200, null, 2, Json.parseToJsonElement("""{"parentId":"a"}""").jsonObject, JsonArray(emptyList())),
    )
    val encoded = SemanticTransferCodec.encode(records)
    check(SemanticTransferCodec.decodeUtf8(encoded.toByteArray(Charsets.UTF_8)) == encoded)
    check(runCatching { SemanticTransferCodec.decodeUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    check(SemanticTransferCodec.decode(encoded) == records)
    val mapped = SemanticTransferCodec.remap(records, "to", "target-window")
    check(mapped[0].metadata.getValue("childIds").jsonArray.single().jsonPrimitive.content == mapped[1].id)
    check(mapped[1].metadata.getValue("parentId").jsonPrimitive.content == mapped[0].id)
    check(mapped == SemanticTransferCodec.remap(records, "to", "target-window"))
    check(mapped.all { it.conversationId == "target-window" && it.metadata["manualSupported"]?.jsonPrimitive?.boolean == true })
    check(mapped.map { it.sources } == records.map { it.sources })
    check(SemanticTransferCodec.remap(records, "from", "original-window") == records)
    val sameAssistantOtherWindow = SemanticTransferCodec.remap(records, "from", "other-window")
    check(sameAssistantOtherWindow.map { it.id }.none { id -> records.any { it.id == id } })
    check(SemanticTransferCodec.remap(records, "to", "another-target").map { it.id }.none { id -> mapped.any { it.id == id } })
    check(mapped[0].metadata.getValue("extra").jsonObject.getValue("未知").jsonPrimitive.int == 7)
    val old = mapped[0].copy(id = "old-memory")
    val resolved = SemanticTransferCodec.resolveRelationships(mapped, listOf(old))
    check(resolved[1].metadata.getValue("parentId").jsonPrimitive.content == "old-memory")
    check(SemanticTransferCodec.evidenceKey(old) == SemanticTransferCodec.evidenceKey(old.copy(content = "同源不同内容")))
    check(runCatching { SemanticTransferCodec.decode(encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 99")) }.isFailure)
    check(runCatching { SemanticTransferCodec.decode(encoded.replace("李明是同事", "被篡改")) }.isFailure)
    check(runCatching { SemanticTransferCodec.decode("{损坏文件") }.isFailure)
    check(runCatching { SemanticTransferCodec.encode(records + records.first()) }.isFailure)
    println("PASS transfer: metadata/lifecycle roundtrip, stable mapping, damaged/schema/hash validation")
}
