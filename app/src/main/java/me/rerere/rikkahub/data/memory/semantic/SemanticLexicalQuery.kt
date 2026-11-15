package me.rerere.rikkahub.data.memory.semantic

/** 词法检索视图：把长 RP 输入收敛到"当前话题"，并复用原生 jieba 词元构造内容词 OR 查询。
 *
 * 与向量输入完全分离：向量、缓存键、诊断 queryHash 仍使用完整 query，只有词法 MATCH 使用本文件的结果。
 * 这里不写任何具体剧情词（戒指/角色名），只认通用功能词与话题切换标记。
 */

/** 明确的话题切换标记；只有在子句边界、且不是历史转述时才成立。 */
private val SEMANTIC_TOPIC_SWITCH_MARKERS = listOf(
    "话说", "对了", "说起来", "说到", "顺便", "另外", "by the way", "btw",
)

/**
 * 通用功能词：只清这些已知词，不再"有多个多字词就删掉全部单字"。
 * 单字内容词（茶、吻、奈、4、3）与剧情时间词（明天/那天）都属于内容，必须保留。
 */
private val SEMANTIC_FUNCTION_TERMS = setOf(
    // 语气、句末、结构助词
    "的", "了", "吧", "呢", "哦", "嗯", "啊", "呀", "嘛", "啦", "喔", "唔", "哈", "呵", "呗", "么",
    "着", "过", "地", "的话", "的哦", "的说",
    // 通用副词、连词、指示与套话
    "就是", "真的", "一定", "应该", "为什么", "什么", "怎么", "明明", "一声", "一个", "一下", "一些",
    "这样", "那样", "这么", "那么", "这些", "那些", "这个", "那个", "有点", "好像", "可能", "觉得",
    "已经", "还是", "然后", "所以", "但是", "而且", "如果", "因为", "于是", "不过", "只是",
    // 话题切换标记本身不是内容
    "话说", "对了", "说起来", "说到", "顺便", "另外", "by the way", "btw",
    // 人称代词（单复数）
    "我", "你", "他", "她", "它", "们", "我们", "你们", "他们", "她们", "咱们", "自己",
    "我的", "你的", "他的", "她的", "那", "这",
    // 高频介词/系词/助动词/连接
    "是", "在", "有", "和", "与", "把", "被", "让", "给", "对", "就", "也", "都", "很", "会", "能", "要",
    // 英文功能词（含常见前缀形态）
    "the", "a", "an", "and", "or", "to", "of", "in", "on", "at", "is", "are", "was", "were", "be",
    "it", "that", "this", "with", "for", "as", "but", "by", "do", "does", "did", "have", "has", "had",
    "i", "me", "my", "you", "your", "he", "she", "they", "we", "us", "our", "his", "her", "their",
)

/** 否定词：不能被当虚词清除，但也不与不相邻词拼成短语。 */
private val SEMANTIC_NEGATIONS = setOf(
    "没", "没有", "不", "不要", "不是", "不会", "不能", "别", "未", "无", "否", "从未", "从不",
    "并不", "毫无", "拒绝", "别想", "not", "no", "never", "without", "none", "cannot",
)

/** 未登录单字连续段的长度范围：少于 3 个更可能只是常用词，超过上限就退化成全文 bigram。 */
private const val SEMANTIC_UNREGISTERED_RUN_MIN = 3
private const val SEMANTIC_UNREGISTERED_RUN_MAX = 6

/**
 * 原生 jieba_query 的一个原子。
 *
 * [parts] 是原子的词段：simple 的 pinyin 扩展会给出 bei+jing* 这类形状，裸原子里的加号表示
 * "同一条目内相邻的词段"，不是数据里的加号。引号内的加号才是字面值。
 * [prefix] 表示 FTS5 前缀语义（原生输出里写在短语外部的星号）。
 * 三者必须分开保存：引号内的 ring* 是字面星号，括号里的 (ring*) 才是前缀，合成一个 String 会混同。
 */
data class SemanticNativeAtom(val parts: List<String>, val prefix: Boolean = false) {
    constructor(text: String, prefix: Boolean = false) : this(listOf(text), prefix)

    /** 用于功能词判断与断言的整词文本（多段拼回，例如 bei+jing → beijing）。 */
    val text: String get() = parts.joinToString("")

    /** 诊断/日志用的可读形式：前缀写成 text 加星号；仅为显示，不参与语法构造。 */
    override fun toString(): String = if (prefix) text + "*" else text
}

/** 词法检索视图的结果。[strategy] 见 [semanticLexicalTerms]。 */
data class SemanticLexicalTerms(val atoms: List<SemanticNativeAtom>, val strategy: String) {
    /** 仅供诊断/断言使用的词文列表；构造 FTS 表达式必须用 semanticLexicalExpression 以保留前缀语义。 */
    val terms: List<String> get() = atoms.map { it.text }
}

/** FTS5 短语：只做字面值的引号包裹与转义，不添加前缀星号。 */
fun semanticFtsPhrase(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""

/**
 * 词法主查询：原子用 OR 连接；原子内部多段用 FTS5 的 + 连接成有序、相邻的短语。
 * 每段各自加引号，前缀星号只加在最后一段的引号外。
 * 加号是短语连接符，不是数据 token；空格连接会退化成隐式 AND，误命中反序或中间插词。
 */
fun semanticLexicalExpression(atoms: List<SemanticNativeAtom>): String =
    atoms.joinToString(" OR ") { atom ->
        atom.parts.mapIndexed { index, part ->
            semanticFtsPhrase(part) + if (atom.prefix && index == atom.parts.lastIndex) "*" else ""
        }.joinToString(" + ")
    }

/**
 * 有明确话题切换时只保留新话题；没有切换就原样保留完整场景。
 * 不按句子长短截断，也不无条件删除动作括注——动作本身可能就是查询目标。
 */
fun semanticLexicalFocus(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return trimmed
    val start = lastTopicSwitchStart(trimmed) ?: return trimmed
    val body = trimmed.substring(start).trimStart { it.isWhitespace() || !it.isLetterOrDigit() }.trim()
    // 切换标记后必须还有实质内容，否则视为语气词而非新话题。
    return if (body.count { it.isLetterOrDigit() } >= 2) body else trimmed
}

/** 转述/回顾的提示词：紧跟其后的引语是"以前说过的话"，不是本轮新话题。 */
private val SEMANTIC_RETELLING_CUES = listOf(
    "说过", "曾说", "提到过", "提起过", "回忆", "那时说", "当时说", "常常说", "总是说", "以前说", "写道",
)

/** 最后一个成立的话题切换标记之后的位置；没有则 null。 */
internal fun lastTopicSwitchStart(text: String): Int? {
    val insideBrackets = bracketMask(text)
    var result: Int? = null
    val lower = text.lowercase()
    SEMANTIC_TOPIC_SWITCH_MARKERS.forEach { marker ->
        var index = lower.indexOf(marker)
        while (index >= 0) {
            val end = index + marker.length
            val boundary = index == 0 || !text[index - 1].isLetterOrDigit()
            // 括注里的叙述不能冒充切换指令；标记本身也不能跨进括注。
            val structural = (index until end).any { insideBrackets[it] }
            // 历史转述里出现的标记不算本轮切换；动作后的直接对白允许切换。
            val retold = SEMANTIC_RETELLING_CUES.any { cue ->
                text.substring((index - 12).coerceAtLeast(0), index).contains(cue)
            }
            if (boundary && !structural && !retold) {
                val body = text.substring(end).trim()
                if (body.count { it.isLetterOrDigit() } >= 2) result = maxOf(result ?: -1, end)
            }
            index = lower.indexOf(marker, index + 1)
        }
    }
    return result?.takeIf { it > 0 }
}

/** 标记动作括注/方括号/书名号的字符位置；引号是台词，不当作结构括注。 */
private fun bracketMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length)
    var depth = 0
    text.forEachIndexed { index, char ->
        when (char) {
            '(', '（', '[', '【', '「', '『', '〔' -> { mask[index] = depth > 0; depth++ }
            ')', '）', ']', '】', '」', '』', '〕' -> { depth = (depth - 1).coerceAtLeast(0); mask[index] = depth > 0 }
            else -> mask[index] = depth > 0
        }
    }
    return mask
}

/**
 * 解析原生 jieba_query 输出，保留原子语义。
 *
 * 规则：
 * - 双引号短语内的字符是字面值，内部的星号也是字面量（"ring*" → text=ring*, prefix=false）。
 * - 短语或裸词后面紧跟的星号是**外部前缀标记**（(ring*)、"203"* → prefix=true，text 不含星号）。
 * - AND/OR/NOT/NEAR 与括号是语法，不进原子；连续两个引号是引号转义。
 * 不对用户原文做字符串替换来注入 FTS 操作符。
 */
fun semanticNativeAtoms(native: String): List<SemanticNativeAtom> {
    val atoms = mutableListOf<SemanticNativeAtom>()
    var index = 0
    fun takePrefixMarker(): Boolean {
        if (index < native.length && native[index] == '*') { index++; return true }
        return false
    }
    while (index < native.length) {
        val char = native[index]
        when {
            char == '"' -> {
                val builder = StringBuilder()
                index++
                while (index < native.length) {
                    if (native[index] == '"') {
                        if (index + 1 < native.length && native[index + 1] == '"') {
                            builder.append('"'); index += 2; continue
                        }
                        index++
                        break
                    }
                    builder.append(native[index])
                    index++
                }
                // 引号内是字面值：里面的加号、星号都属于数据，不拆段也不当分隔符。
                if (builder.isNotEmpty()) atoms += SemanticNativeAtom(builder.toString(), takePrefixMarker())
            }
            char.isWhitespace() || char == '(' || char == ')' -> index++
            else -> {
                val start = index
                while (index < native.length && !native[index].isWhitespace() &&
                    native[index] != '"' && native[index] != '(' && native[index] != ')' && native[index] != '*') index++
                val bare = native.substring(start, index)
                val prefix = takePrefixMarker()
                if (bare.uppercase() !in setOf("AND", "OR", "NOT", "NEAR")) {
                    // 裸原子里的加号是 simple 拼音/别名的分段标记，保留分段供表达式逐段加引号。
                    val parts = bare.split('+').filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) atoms += SemanticNativeAtom(parts, prefix)
                }
            }
        }
    }
    return atoms.map { atom -> atom.copy(parts = atom.parts.map { it.trim() }.filter { it.isNotEmpty() }) }
        .filter { it.parts.isNotEmpty() }
}

private fun isFunction(atom: SemanticNativeAtom): Boolean = atom.text.lowercase() in SEMANTIC_FUNCTION_TERMS

private fun isNegation(atom: SemanticNativeAtom): Boolean = atom.text.lowercase() in SEMANTIC_NEGATIONS

private fun hasContent(atom: SemanticNativeAtom): Boolean = atom.text.any { it.isLetterOrDigit() }

/**
 * 把原生词元收敛成内容词 OR 查询。
 *
 * - 只清 [SEMANTIC_FUNCTION_TERMS] 里的已知功能词；单字内容词（茶/吻/奈/4/3）一律保留。
 * - 否定词保留，但不与不相邻的词拼成短语。
 * - 连续未登录单字段按**原生原子序列的真实相邻关系**补二元组，不因整段里另有多字词就整段丢弃。
 * - 原生不可用或没有可用词元时，退化为聚焦文本的中文二元组。
 */
fun semanticLexicalTerms(focus: String, native: String): SemanticLexicalTerms {
    val atoms = semanticNativeAtoms(native)
    val kept = atoms.filter { hasContent(it) && !isFunction(it) }
    if (kept.isNotEmpty()) {
        val terms = (kept + unregisteredBigrams(atoms)).distinct()
        return SemanticLexicalTerms(terms, if (atoms.size > kept.size) "terms" else "terms_all")
    }
    if (atoms.any { hasContent(it) }) {
        // 全部被功能词吃掉的极短输入（如"我的"）：保留原生原子，宁多不漏。
        return SemanticLexicalTerms(atoms.filter { hasContent(it) }.distinct(), "native_all")
    }
    val bigrams = semanticCjkBigrams(emptyList(), focus).map { SemanticNativeAtom(it) }
    return SemanticLexicalTerms(bigrams, if (bigrams.isEmpty()) "empty" else "bigram_fallback")
}

/**
 * 未登录词补召回：只对**原生原子序列里连续相邻**的单字内容原子补二元组。
 *
 * 遍历未过滤的原子序列，遇到功能词/标点/多字词就断开，所以"奈的落"、"奈，落"都不会补出"奈落"。
 * 连续段至少 3 个单字才补（两字相邻多为常用词），且有上限，避免长句退化成全文 bigram。
 */
private fun unregisteredBigrams(atoms: List<SemanticNativeAtom>): List<SemanticNativeAtom> {
    // 多段原子（拼音/别名）不是未登录中文单字，按分段断开处理。

    val terms = mutableListOf<SemanticNativeAtom>()
    var run = mutableListOf<String>()
    fun flush() {
        if (run.size in SEMANTIC_UNREGISTERED_RUN_MIN..SEMANTIC_UNREGISTERED_RUN_MAX) {
            for (index in 0 until run.size - 1) terms += SemanticNativeAtom(run[index] + run[index + 1])
        }
        run = mutableListOf()
    }
    atoms.forEach { atom ->
        val ideographic = atom.parts.size == 1 && atom.text.length == 1 && atom.text.first() in '\u4E00'..'\u9FFF'
        val usable = ideographic && !atom.prefix && !isFunction(atom) && !isNegation(atom)
        if (usable) run += atom.text else flush()
    }
    flush()
    return terms
}
