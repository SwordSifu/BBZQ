package io.github.bbzq.roaming.hook

import io.github.bbzq.roaming.BilibiliSponsorBlock

object SkipVideoAdState {
    @Volatile var durationMs: Long = 0L
    @Volatile var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
}
