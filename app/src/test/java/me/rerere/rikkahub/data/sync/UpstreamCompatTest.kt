package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpstreamCompatTest {
    @Test fun semanticSettingsAreRemovedWithoutChangingAssistantIdentityOrOtherFields() {
        val json = Json
        val input = """{"launchCount":7,"assistants":[{"id":"fixture-assistant","name":"fixture",
            "semanticMemory":{"enabled":true,"embeddingModelId":"fixture-model"},
            "streamOutput":true,"compressionMemoryStream":false,"enableMemory":false}]}"""
        val original = json.parseToJsonElement(input).jsonObject
        val result = json.parseToJsonElement(UpstreamCompat.settingsJson(input, json)).jsonObject
        val assistant = result.getValue("assistants").jsonArray.single().jsonObject
        assertFalse("semanticMemory" in assistant)
        assertEquals(original.getValue("launchCount"), result.getValue("launchCount"))
        assertEquals(original.getValue("assistants").jsonArray.single().jsonObject - "semanticMemory", assistant)
    }

    @Test fun archivesWithoutSemanticSettingsKeepExistingAssistantPayload() {
        val json = Json
        val input = """{"assistants":[{"id":"fixture-assistant","name":"fixture","streamOutput":true}]}"""
        assertEquals(json.parseToJsonElement(input), json.parseToJsonElement(UpstreamCompat.settingsJson(input, json)))
    }
}
