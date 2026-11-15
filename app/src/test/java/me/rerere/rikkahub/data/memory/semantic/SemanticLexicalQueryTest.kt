package me.rerere.rikkahub.data.memory.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 词法检索视图：只验证"当前话题如何收敛成内容词"，不涉及向量、融合或权重。 */
class SemanticLexicalQueryTest {
    private val ringQuery = "嗯嗯,我的鲸鲸说了的话那就是真的哦,一定一定会的" +
        "(同样温柔的吻了吻你的耳朵，接着捧住你左边那只手把它放在我的膝上)" +
        "话说那天我们一起去定戒指应该好了吧，为什么鲸鲸没有戴呢，明明说一声我的名字就能拿走的吧"

    @Test fun explicitTopicSwitchPrefersTheNewTopicOverTheActionScene() {
        val focus = semanticLexicalFocus(ringQuery)
        assertTrue("切换后应保留戒指话题：" + focus, focus.contains("戒指"))
        assertTrue(focus.contains("名字") && focus.contains("拿走"))
        assertFalse("旧动作场景不应继续占词法主查询：" + focus, focus.contains("耳朵"))
        assertFalse(focus.contains("膝上") || focus.contains("温柔的吻"))
        assertFalse("切换标记本身不是内容词：" + focus, focus.startsWith("话说"))
    }

    @Test fun noSwitchKeepsTheCompleteSceneIncludingTheAction() {
        val scene = "(温柔的吻了吻你的耳朵，捧住你左手)" + "你把头靠在我肩上，我们坐了很久。"
        assertEquals(scene, semanticLexicalFocus(scene))
        assertTrue(semanticLexicalFocus(scene).contains("耳朵"))
    }

    @Test fun switchWordNeedsAClauseBoundaryAndRealContent() {
        // 这些"话说/对了"只是句中字面，不是话题切换。
        for (text in listOf("他昨天说的话说了两遍", "我没话说，只想安静一会儿", "她对着我说了好几次", "对了")) {
            assertEquals(text, semanticLexicalFocus(text))
        }
        // 边界成立且后面有实质内容时才算切换。
        assertEquals("那天的事我们改天再谈", semanticLexicalFocus("(他叹气)对了,那天的事我们改天再谈"))
        assertEquals("那枚戒指还在店里", semanticLexicalFocus("嗯嗯。By the way,那枚戒指还在店里"))
    }

    @Test fun bracketNarrationCannotImpersonateATopicSwitch() {
        val text = "我抱着你(他在心里说，对了，明天要下雨)然后我们一起回家了。"
        assertEquals(text, semanticLexicalFocus(text))
        val inner = "我抱着你（顺便问了问店员）然后我们一起回家了。"
        assertEquals(inner, semanticLexicalFocus(inner))
    }

    @Test fun multipleTargetsInsideTheNewTopicAreAllKept() {
        val focus = semanticLexicalFocus("嗯嗯。对了，戒指和项链都还在店里，记得带我的名字去取。")
        assertTrue(focus.contains("戒指") && focus.contains("项链") && focus.contains("名字"))
        assertTrue(focus.contains("取"))
    }

    @Test fun longInputWithoutSwitchIsNotTruncated() {
        val long = "场景铺垫。".repeat(400) + "最后才说到备用钥匙放在玄关抽屉里。"
        assertEquals(long, semanticLexicalFocus(long))
    }

    @Test fun nativeAtomsSurviveQuotesEscapesOperatorsParenthesesAndPinyin() {
        assertEquals(listOf("戒指", "dai", "zou"), semanticNativeAtoms("""
            "戒指" AND dai OR ("zou")
        """.trimIndent()).map { it.text })
        assertEquals(listOf("a\"b"), semanticNativeAtoms(""""a""b"""").map { it.text })
        // 操作符本身不能变成词元，否则用户的原文会注入 FTS 语法。
        assertEquals(listOf("戒指"), semanticNativeAtoms("NOT NEAR(\"戒指\")").map { it.text })
        assertTrue(semanticNativeAtoms("   ").isEmpty())
        assertTrue(semanticNativeAtoms("AND OR NOT").isEmpty())
    }

    @Test fun pinyinPlusSegmentsUseAdjacentPhrasesAndNeverQuoteThePlus() {
        // simple 的 pinyin 扩展真实形状：(bei+jing* OR b+e+i+j+i+n+g* OR beijing*)
        val atoms = semanticNativeAtoms("(bei+jing* OR b+e+i+j+i+n+g* OR beijing*)")
        assertEquals(3, atoms.size)
        assertEquals(listOf("bei", "jing"), atoms[0].parts)
        assertTrue("前缀必须留在最后一段之外：", atoms[0].prefix)
        assertEquals("beijing", atoms[0].text)
        assertEquals(listOf("b", "e", "i", "j", "i", "n", "g"), atoms[1].parts)
        assertEquals(listOf("beijing"), atoms[2].parts)
        val expression = semanticLexicalExpression(atoms)
        assertFalse("加号不能变成被引用的数据 token：" + expression, expression.contains("\"bei+jing\""))
        assertTrue("拼音段之间必须保留顺序和相邻位置：" + expression,
            expression.startsWith("\"bei\" + \"jing\"* OR "))
        assertTrue("逐字拼音的前缀只作用于最后一段：" + expression,
            expression.contains("\"b\" + \"e\" + \"i\" + \"j\" + \"i\" + \"n\" + \"g\"*"))
        // 引号内的加号是字面值，不能拆段。
        val literal = semanticNativeAtoms(""""a+b"""")
        assertEquals(listOf(listOf("a+b")), literal.map { it.parts })
        assertEquals("\"a+b\"", semanticLexicalExpression(literal))
    }

    @Test fun functionWordsAreFilteredEvenWhenTheyArriveAsPinyinSegments() {
        // for 的 f+o+r*/fo+r*/for* 别名不能绕过功能词过滤。
        val terms = semanticLexicalTerms("for the ring",
            """(f+o+r* OR fo+r* OR for*) AND (the*) AND (ring*)""")
        assertFalse("for 的任何别名都不能进入查询：" + terms.terms,
            terms.terms.any { it.equals("for", ignoreCase = true) })
        assertTrue(terms.terms.toString(), terms.terms.contains("ring"))
    }

    @Test fun quotedLiteralStarAndNativePrefixAreNotTheSameAtom() {
        // 引号内的星号是字面量；括号/短语外的星号是 FTS5 前缀标记。二者不能再合并成同一个 String。
        val literal = semanticNativeAtoms(""""ring*"""")
        assertEquals(listOf(SemanticNativeAtom("ring*", prefix = false)), literal)
        val prefix = semanticNativeAtoms("(ring*)")
        assertEquals(listOf(SemanticNativeAtom("ring", prefix = true)), prefix)
        val numberPrefix = semanticNativeAtoms(""""203"*""")
        assertEquals(listOf(SemanticNativeAtom("203", prefix = true)), numberPrefix)
        // 表达式层：字面星号留在引号里，前缀星号在引号外。
        assertEquals("\"ring*\"", semanticLexicalExpression(literal))
        assertEquals("\"ring\"*", semanticLexicalExpression(prefix))
        assertEquals("\"203\"*", semanticLexicalExpression(numberPrefix))
    }

    @Test fun contentTermsDropFunctionAndSingleCharacterNoise() {
        val terms = semanticLexicalTerms("那天我们一起去定戒指应该好了吧",
            """"话说" AND "那天" AND "我们" AND "一起" AND "去" AND "定" AND "戒指" AND "应该" AND "好了" AND "吧"""")
        assertEquals("terms", terms.strategy)
        assertTrue("戒指必须保留：" + terms.terms, terms.terms.contains("戒指"))
        assertFalse("人称代词/语气词/套话不应进查询：" + terms.terms,
            terms.terms.any { it in setOf("我们", "应该", "吧", "话说") })
        // 剧情时间词"那天"与副词"一起"是内容，不能再按"单字/高频"一刀切丢弃。
        assertTrue("剧情时间词必须保留：" + terms.terms, terms.terms.contains("那天"))
    }

    @Test fun negationIsKeptButNeverBoundIntoANonAdjacentPhrase() {
        val terms = semanticLexicalTerms("我不要那枚戒指了", """"我" AND "不要" AND "那枚" AND "戒指" AND "了"""")
        assertTrue("否定词不能被当虚词清除：" + terms.terms, terms.terms.contains("不要"))
        assertTrue(terms.terms.contains("戒指"))
        assertFalse("不得拼出原文中不相邻的短语：" + terms.terms, terms.terms.any { it == "不要戒指" })
    }

    @Test fun singleCharacterContentAtomSurvivesAlongsideMultiCharacterTerms() {
        // 主代理探针 ①：我不喜欢茶 → 我/不/喜欢/茶，"茶" 是真话题不能被单字规则删掉。
        val terms = semanticLexicalTerms("我不喜欢茶", """"我" AND "不" AND "喜欢" AND "茶"""")
        assertTrue("单字内容词必须保留：" + terms.terms, terms.terms.contains("茶"))
        assertTrue(terms.terms.contains("喜欢"))
        assertTrue("否定词保留：" + terms.terms, terms.terms.contains("不"))
        assertFalse("代词是功能词：" + terms.terms, terms.terms.contains("我"))
    }

    @Test fun singleCharacterActionTargetSurvives() {
        // 探针 ②：耳朵/和/那天/的/吻 → 动作目标"吻"不能丢。
        val terms = semanticLexicalTerms("耳朵和那天的吻", """"耳朵" AND "和" AND "那天" AND "的" AND "吻"""")
        assertTrue("动作目标单字词必须保留：" + terms.terms, terms.terms.contains("吻"))
        assertTrue(terms.terms.contains("耳朵"))
        assertFalse(terms.terms.contains("和") || terms.terms.contains("的"))
    }

    @Test fun realNativePrefixShapeKeepsStarOutsideQuotesAndKeepsNumbers() {
        // 探针 ③④：真实 native 前缀形状 (gpt*) AND "4"* AND (claude*) AND "3"* AND "." AND "5"*
        val native = """(gpt*) AND "4"* AND (claude*) AND "3"* AND "." AND "5"*"""
        val terms = semanticLexicalTerms("gpt 4 和 claude 3.5 对比", native)
        val expression = semanticLexicalExpression(terms.atoms)
        assertTrue("数字不能丢：" + terms.terms, terms.terms.any { it.trimEnd('*') == "4" })
        assertTrue(terms.terms.any { it.trimEnd('*') == "3" } && terms.terms.any { it.trimEnd('*') == "5" })
        assertTrue(terms.terms.any { it.trimEnd('*') == "gpt" })
        assertTrue("前缀星号必须在引号外：" + expression, expression.contains("\"gpt\"*"))
        assertFalse("不能把星号引成字面量：" + expression, expression.contains("gpt*\""))
    }

    @Test fun unregisteredNameInsideALongRunIsRecoveredByRealContiguity() {
        // 探针 ⑤：我/想见/奈/落/汀，混在长段里的未登录名字不能整段消失。
        val terms = semanticLexicalTerms("我想见奈落汀", """"我" AND "想见" AND "奈" AND "落" AND "汀"""")
        assertTrue("未登录单字必须保留：" + terms.terms, listOf("奈", "落", "汀").all { it in terms.terms })
        assertTrue("连续未登录字要补二元组：" + terms.terms, terms.terms.contains("奈落"))
        assertTrue("相邻未登录字都要两两补：" + terms.terms, terms.terms.contains("落汀"))
        assertTrue(terms.terms.contains("想见"))
        // 中间隔着功能词的单字不能被拼成短语（戴/呢/明明/说 → 不得出现"戴说"）。
        val gapped = semanticLexicalTerms("戴呢明明说", """"戴" AND "呢" AND "明明" AND "说"""")
        assertFalse("不得跨过功能词拼接：" + gapped.terms, gapped.terms.any { it == "戴说" })
        // 功能词/标点把单字段断开时，绝不能补出跨段二元组。
        for (native in listOf(""""奈" AND "的" AND "落"""", """"奈" AND "，" AND "落"""", """"奈" AND "落"""")) {
            val broken = semanticLexicalTerms("奈的落", native)
            assertFalse("不得跨段拼接：" + native + " → " + broken.terms, broken.terms.any { it == "奈落" })
        }
    }

    @Test fun narrativeTimeWordsAreNotTreatedAsClockNoise() {
        // 探针 ⑥：剧情里的"明天"是语义内容，不是现实时间新鲜度。
        val terms = semanticLexicalTerms("明天要取戒指", """"明天" AND "要" AND "取" AND "戒指"""")
        assertTrue("剧情时间词不能被当噪声：" + terms.terms, terms.terms.contains("明天"))
        assertTrue(terms.terms.contains("戒指") && terms.terms.contains("取"))
        assertFalse(terms.terms.contains("要"))
    }

    @Test fun shortChineseAndUnregisteredWordsKeepTheirRecall() {
        val single = semanticLexicalTerms("茶", """"茶"""")
        assertTrue("单字查询不能变成空查询：" + single.terms, single.terms.contains("茶"))
        val unregistered = semanticLexicalTerms("晚吟", """"晚吟"""")
        assertTrue(unregistered.terms.contains("晚吟"))
        val noNative = semanticLexicalTerms("晚吟", "")
        assertEquals("bigram_fallback", noNative.strategy)
        assertTrue(noNative.terms.contains("晚吟"))
    }

    @Test fun properNounsEnglishAndNumbersSurvive() {
        val terms = semanticLexicalTerms("GPT-4 和 Claude 3.5 的对比", """"GPT" AND "-" AND "4" AND "和" AND "Claude" AND "3.5" AND "的" AND "对比"""")
        assertTrue(terms.terms.toString(), terms.terms.any { it.contains("Claude") })
        assertTrue(terms.terms.toString(), terms.terms.any { it.contains("GPT") })
        assertTrue(terms.terms.toString(), terms.terms.contains("对比"))
        assertFalse(terms.terms.toString(), terms.terms.contains("的"))
    }

    @Test fun ftsSpecialCharactersCannotInjectOperators() {
        // 裸操作符不进词元；即使它们出现在原生输出里，最终表达式也只能是短语/前缀。
        val terms = semanticLexicalTerms("""她说"别走" 戒指 项链""",
            """她说 AND 别走 AND NEAR AND 戒指 AND NOT AND 项链""")
        assertFalse(terms.terms.toString(), terms.terms.any { it.uppercase() in setOf("NEAR", "NOT", "AND", "OR") })
        assertTrue(terms.terms.contains("戒指") && terms.terms.contains("项链"))
        for (term in terms.atoms) {
            val phrase = semanticFtsPhrase(term.text)
            assertTrue(phrase, phrase.startsWith("\"") && phrase.endsWith("\""))
            assertFalse("内部引号必须按 FTS5 转义：" + phrase, phrase.substring(1, phrase.length - 1).replace("\"\"", "").contains("\""))
        }
    }

    @Test fun emptyAndPunctuationOnlyInputDegradesToEmptyStrategy() {
        assertEquals("empty", semanticLexicalTerms("", "").strategy)
        assertTrue(semanticLexicalTerms("", "").terms.isEmpty())
        assertEquals("", semanticLexicalFocus("   "))
        assertEquals("empty", semanticLexicalTerms("（）！？…", "").strategy)
    }

    @Test fun expressionIsPhraseQuotedAndOrJoined() {
        val terms = semanticLexicalTerms("那天我们一起去定戒指应该好了吧", """"戒指" AND "名字"""")
        val expression = semanticLexicalExpression(terms.atoms)
        assertTrue(expression, expression.startsWith("\"戒指\""))
        assertTrue(expression.contains("\"名字\""))
        assertFalse(expression.contains(" AND "))
    }
}
