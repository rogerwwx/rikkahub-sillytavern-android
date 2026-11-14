package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You compress earlier parts of an ongoing roleplay into a continuity brief. Everything after this
    summary continues the same story, so the summary must let the next reply stay in character, in the
    right place, and in the right emotional moment.

    Write a state brief, not a plot recap and not a list of facts.

    Cover what is still load-bearing, in this order, skipping anything absent:
    1. Current scene: in-story time (use the story's own reckoning, not the real clock), place, who is
       present, and each present character's visible condition.
    2. Relationships and feelings: how the characters currently regard each other and what recently
       shifted. Keep asymmetry and concealment when they matter.
    3. Open threads: promises made, debts, unresolved conflicts, secrets hinted but not yet revealed,
       plans announced but not carried out.
    4. Facts that constrain later replies: names and forms of address, roles, physical limits,
       injuries, who owns or carries which important object, standing rules of the setting.
    5. Where it stopped: the last concrete action, and what is being reacted to right now.

    Rules:
    - Prefer state over narration. "Her left arm is still bound and she cannot lift the sword" is
      useful; "they went through a hard time" is not.
    - Never invent, resolve or dramatize. If the record is ambiguous, say so in plain words.
    - Do not pad with atmosphere, adjectives or quoted dialogue, and do not comment on the task itself.
    - Details that later retrieval can surface verbatim do not need full wording; one line each.
    - Keep the story's internal chronology intact. Do not reorder events or merge separate days.
    - Target approximately {target_tokens} tokens. Shorter is fine; do not inflate to fill the budget.
    - Write in the language of the conversation, and use {locale} for terminology when a choice is needed.
    - Begin with a short marker that this covers earlier events (translated as appropriate).

    If the input already contains an earlier summary of this history, treat it as pre-compressed
    material: carry forward everything still true, merge the new conversation into it, and drop only
    what the new conversation has superseded. Do not copy it verbatim, and do not restart from the
    new part alone.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
