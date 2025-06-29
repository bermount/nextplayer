package dev.anilbeesetti.nextplayer.core.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackPosition(
    val filename: String,
    val position: Long,
    val lastUpdated: Long,
)
