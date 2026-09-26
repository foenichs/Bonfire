package com.foenichs.bonfire.model

import java.util.*

data class Claim(
    var id: Int? = null,
    var owner: UUID,
    val chunks: MutableSet<ChunkPos> = mutableSetOf(),
    var blockActions: String = "never",
    var entityActions: String = "never",
    val trustedAlways: MutableSet<UUID> = mutableSetOf(),
    val trustedOnline: MutableSet<UUID> = mutableSetOf(),
    val legacyIds: MutableSet<Int> = mutableSetOf()
)