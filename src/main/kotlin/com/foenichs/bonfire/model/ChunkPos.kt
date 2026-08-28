package com.foenichs.bonfire.model

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
         * Builds the ChunkPos (including layer) of a location
         */
        fun of(location: Location): ChunkPos = ChunkPos(location.world.uid, location.chunk.chunkKey, layerFor(location))
    }
}