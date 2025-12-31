package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent

class PistonProtectionListener(
    private val protection: ProtectionService
) : Listener {

    /**
     * Pistons pushing blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (isMovementIllegal(event.block, event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    /**
     * Pistons pulling blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (isMovementIllegal(event.block, event.blocks, event.direction.oppositeFace)) {
            event.isCancelled = true
        }
    }

    /**
     * Check if movement goes into or out of claims
     */
    private fun isMovementIllegal(piston: Block, blocks: List<Block>, moveDir: BlockFace): Boolean {
        val pistonChunk = piston.chunk

        // Check the space immediately in front of the piston (the Piston Arm)
        val faceChunk = piston.getRelative(moveDir).chunk
        if (!protection.isWorldActionAllowed(pistonChunk, faceChunk)) return true

        // Check every block being moved
        for (b in blocks) {
            val fromChunk = b.chunk
            val toChunk = b.getRelative(moveDir).chunk

            // Movement across borders or interaction with foreign blocks is blocked
            if (!protection.isWorldActionAllowed(fromChunk, toChunk)) return true
            if (!protection.isWorldActionAllowed(pistonChunk, fromChunk)) return true
        }
        return false
    }
}