package com.foenichs.bonfire.model

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.*

/**
 * Nether claims are split into ground and roof
 */
enum class ChunkLayer { GROUND, ROOF }
fun ChunkLayer.opposite(): ChunkLayer = if (this == ChunkLayer.GROUND) ChunkLayer.ROOF else ChunkLayer.GROUND

data class ChunkPos(
    val worldUuid: UUID,
    val chunkKey: Long,
    val layer: ChunkLayer = ChunkLayer.GROUND
) {
    /**
     * The chunks connected to this one, horizontally and across the ground/roof split
     */
    fun neighbors(): List<ChunkPos> {
        val x = chunkKey.toInt(); val z = (chunkKey shr 32).toInt()
        return listOf(
            ChunkPos(worldUuid, Chunk.getChunkKey(x + 1, z), layer),
            ChunkPos(worldUuid, Chunk.getChunkKey(x - 1, z), layer),
            ChunkPos(worldUuid, Chunk.getChunkKey(x, z + 1), layer),
            ChunkPos(worldUuid, Chunk.getChunkKey(x, z - 1), layer),
            ChunkPos(worldUuid, chunkKey, layer.opposite())
        )
    }

    companion object {
        const val NETHER_ROOF_Y = 127.0

        /**
         * Determines layer of a nether location
         */
        fun layerFor(location: Location): ChunkLayer {
            val world = location.world ?: return ChunkLayer.GROUND
            return if (world.environment == World.Environment.NETHER && location.y >= NETHER_ROOF_Y) ChunkLayer.ROOF
            else ChunkLayer.GROUND
        }

        /**
         * Builds the ChunkPos (including layer) of a location, without loading its chunk
         */
        fun of(location: Location): ChunkPos =
            ChunkPos(location.world.uid, Chunk.getChunkKey(location.blockX shr 4, location.blockZ shr 4), layerFor(location))
    }
}