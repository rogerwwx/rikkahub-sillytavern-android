package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You write the continuity summary used to resume a roleplay conversation. Base it only on the raw conversation below and any existing summary, recording what is confirmed as of the compression boundary.

    The most recent messages are kept verbatim and follow this summary directly, so they already carry the current situation. Do not describe the present state of affairs or restate the latest exchange, and do not claim the boundary-time state is still current.

    Use neutral narration and preserve:
    1. Story times or plot nodes the text states, and the order and causality of important events
    2. Facts that constrain later action: identities, names and forms of address, key items and who holds them
    3. What each character already knows and still does not know
    4. Promises, plans, constraints, secrets and unresolved threads still in effect at the boundary, naming who is involved
    5. Relationship turning points that clearly happened, and attitude shifts needed to understand later actions — stated briefly

    Do not write this as first-person recollection or inner monologue, and do not replay dialogue.
    Do not omit necessary causality on the assumption that an external memory system will supply it.
    Distinguish recollection, flashback, dream and speculation from events that actually happened; message send order is not the story timeline. When no date is known, keep relative nodes instead of inventing absolute dates.
    If the input contains an "[Existing summary ...]" section, merge the new material into it: update a state only when new evidence clearly changed it, and keep old events still needed to understand what follows.

    Start the output with a clear marker such as "[Continuity summary as of ...]" (or the equivalent in the target language); if the node is unknown, say it is undetermined.
    Target approximately {target_tokens} tokens; shorter is fine, do not pad to fill the budget.
    Use {locale} language and output the summary directly, without explanations or meta-commentary.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
