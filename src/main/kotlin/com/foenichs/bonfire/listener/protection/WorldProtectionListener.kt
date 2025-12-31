package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.world.StructureGrowEvent

class WorldProtectionListener(
    private val protection: ProtectionService
) : Listener {

    /**
     * Flowing liquids breaking blocks (grass, torches, redstone, etc.)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val toBlock = event.toBlock
        if (toBlock.isEmpty || toBlock.isLiquid) return
        if (!protection.checkAllowBlockBreak(toBlock.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire spreading into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFireSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE) return
        if (!protection.checkAllowBlockBreak(event.block.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire destroying blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (!protection.checkAllowBlockBreak(event.block.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Trees and large structures growing into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val sourceChunk = event.location.chunk

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetChunk = state.chunk

            // Only block if moving from outside into a protected claim
            if (!protection.isWorldActionAllowed(
                    sourceChunk, targetChunk
                ) && !protection.checkAllowBlockBreak(targetChunk)
            ) {
                event.isCancelled = true
                return
            }
        }
    }

    /**
     * Bone Meal spreading grass/flowers into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val sourceChunk = event.block.chunk

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetChunk = state.chunk

            if (!protection.isWorldActionAllowed(
                    sourceChunk, targetChunk
                ) && !protection.checkAllowBlockBreak(targetChunk)
            ) {
                iterator.remove()
            }
        }
    }
}