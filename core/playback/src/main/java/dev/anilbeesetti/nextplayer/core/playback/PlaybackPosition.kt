package dev.anilbeesetti.nextplayer.core.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackPosition(
    val identifier: String,
    val position: Long,
    val lastUpdated: Long,
)
