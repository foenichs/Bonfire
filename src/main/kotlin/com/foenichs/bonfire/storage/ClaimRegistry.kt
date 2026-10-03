package com.foenichs.bonfire.storage

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.model.ChunkLayer
import com.foenichs.bonfire.model.ChunkPos
import org.bukkit.Location
import java.util.*

class ClaimRegistry(loaded: List<Claim>) {
    private val claims = loaded.toMutableList()

    // Chunk to claim index
    private val index = HashMap<ChunkPos, Claim>()

    init {
        claims.forEach { c -> c.chunks.forEach { index[it] = c } }
    }

    fun getAll(): List<Claim> = claims
    fun getAt(pos: ChunkPos) = index[pos]
    fun getAt(w: UUID, k: Long, layer: ChunkLayer) = index[ChunkPos(w, k, layer)]

    /**
     * Resolves the claim at a location, respecting roof/ground splits
     */
    fun getAt(location: Location) = index[ChunkPos.of(location)]

    fun getOwnedChunks(u: UUID) = claims.filter { it.owner == u }.sumOf { it.chunks.size }
    fun getOwnedClaimsCount(u: UUID) = claims.count { it.owner == u }

    fun add(c: Claim) {
        claims.add(c)
        c.chunks.forEach { index[it] = c }
    }

    fun remove(c: Claim) {
        claims.remove(c)
        c.chunks.forEach { if (index[it] === c) index.remove(it) }
    }

    fun addChunk(c: Claim, pos: ChunkPos) {
        c.chunks.add(pos)
        index[pos] = c
    }

    fun addChunks(c: Claim, positions: Collection<ChunkPos>) = positions.forEach { addChunk(c, it) }

    fun removeChunk(c: Claim, pos: ChunkPos) {
        c.chunks.remove(pos)
        if (index[pos] === c) index.remove(pos)
    }
}
