package me.rerere.rikkahub.ui.pages.chat

/** 从有效上下文先回到压缩分界线，已经到达分界线或正在翻阅旧原文时直接到顶。 */
internal fun topJumpTarget(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    compressedPrefixSize: Int,
): Int = if (compressedPrefixSize > 0 &&
    (firstVisibleItemIndex > compressedPrefixSize ||
        (firstVisibleItemIndex == compressedPrefixSize && firstVisibleItemScrollOffset > 0))
) compressedPrefixSize else 0
