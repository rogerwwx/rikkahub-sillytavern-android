package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

@Composable
internal fun SemanticMemorySection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardGroup(modifier = Modifier.fillMaxWidth()) {
        formItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            leadingContent = { CardGroupIcon(icon) },
            content = content,
        )
    }
}

internal fun semanticRerankDiagnosticText(raw: String): String = try {
    val details = Json.parseToJsonElement(raw).jsonObject
    val items = details["items"]?.jsonArray.orEmpty()
    val selected = details["selected"]?.jsonPrimitive?.intOrNull
        ?: items.count { it.jsonObject["kept"]?.jsonPrimitive?.booleanOrNull == true }
    val scored = details["scored"]?.jsonPrimitive?.intOrNull ?: 0
    val elapsed = details["elapsedMillis"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val fallback = details["fallback"]?.jsonPrimitive?.contentOrNull
    "候选 ${items.size} 条 · 已评分 $scored 条 · 选用 $selected 条 · ${"%.0f".format(elapsed)} 毫秒" +
        (fallback?.let { "\n已使用原召回结果：$it" } ?: "")
} catch (_: Exception) { "重排记录尚未完成" }

@Composable
internal fun SemanticIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    var draft by remember(value, range) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            CardGroupValue(draft.roundToInt().toString())
        }
        Slider(
            value = draft,
            onValueChange = { draft = it.roundToInt().toFloat() },
            onValueChangeFinished = { onChange(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

@Composable
internal fun SemanticDecimalSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    enabled: Boolean = true,
    onChange: (Double) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            CardGroupValue("%.2f".format(draft))
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange((draft * 100).roundToInt() / 100.0) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
        )
    }
}
