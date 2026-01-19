package com.foenichs.bonfire.model

import java.util.*

data class Claim(
    var id: Int? = null,
    var owner: UUID,
    val chunks: MutableSet<ChunkPos> = mutableSetOf(),
    var allowBlockBreak: Boolean = false,
    var allowBlockInteract: Boolean = false,
    var allowEntityInteract: String = "false",
    val trustedAlways: MutableSet<UUID> = mutableSetOf(),
    val trustedOnline: MutableSet<UUID> = mutableSetOf()
)