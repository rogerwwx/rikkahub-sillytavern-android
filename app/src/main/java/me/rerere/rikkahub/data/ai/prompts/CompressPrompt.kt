package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are writing a continuity summary for a roleplay conversation, so the story can pick up smoothly from where the recent messages left off. Work only from the raw conversation below and any existing summary, and record what is confirmed up to the point where compression stops.

    The most recent messages stay in the context right after this summary, so they already show the current situation — don't restate them here, and don't treat the state at the compression boundary as if it were still current.

    Write in neutral narration and focus on what later replies need to stay consistent with:
    1. Any story times or plot nodes the text mentions, and the order and causality of important events
    2. Facts that shape what happens next: who the characters are, how they address each other, key items and who has them
    3. What each character knows, and what they don't know yet
    4. Promises, plans, limits, secrets and open threads that are still in effect when compression stops — say who they involve
    5. Relationship turning points that clearly happened, and shifts in attitude that later actions depend on; a sentence or two is enough

    Keep it as narration, not first-person memories or inner monologue, and don't replay the dialogue.
    Never leave out a key cause-and-effect link just because it might live in some external memory — this summary has to stand on its own.
    Tell apart what actually happened from recollections, flashbacks, dreams and guesses; the order messages were sent in is not the story's timeline. If no date is given, keep relative nodes like "after the argument" instead of guessing absolute dates.
    If the input includes an "[Existing summary ...]" section, fold the new material into it: only change a recorded state when the new text clearly changed it, and keep older events that are still needed to follow the story.

    Open with a marker like "[Continuity summary as of ...]" (or the equivalent in the target language); if the node is unclear, say it's undetermined.
    Aim for about {target_tokens} tokens — shorter is fine, don't pad.
    Write in {locale} and output only the summary itself.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
