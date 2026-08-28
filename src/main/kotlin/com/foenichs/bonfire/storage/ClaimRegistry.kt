package com.foenichs.bonfire.storage

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.model.ChunkLayer
import com.foenichs.bonfire.model.ChunkPos
import org.bukkit.Location
import java.util.*

class ClaimRegistry(private val claims: MutableList<Claim>) {
    fun getAll() = claims
    fun getAt(w: UUID, k: Long, layer: ChunkLayer) = claims.find { c -> c.chunks.any { it.worldUuid == w && it.chunkKey == k && it.layer == layer } }

    /**
     * Resolves the claim at a location, respecting roof/ground splits
     */
    fun getAt(location: Location) = getAt(location.world.uid, location.chunk.chunkKey, ChunkPos.layerFor(location))

    fun getOwnedChunks(u: UUID) = claims.filter { it.owner == u }.sumOf { it.chunks.size }
    fun getOwnedClaimsCount(u: UUID) = claims.count { it.owner == u }
    fun add(c: Claim) = claims.add(c)
    fun remove(c: Claim) = claims.remove(c)
}