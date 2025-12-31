package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent

class BlockProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Helper for permission checks
     */
    private fun isActionBlocked(player: Player, chunk: Chunk): Boolean {
        if (protection.canBypass(player, chunk)) return false
        val claim = registry.getAt(chunk) ?: return false
        return !claim.allowBlockBreak
    }

    /**
     * Players breaking blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (isActionBlocked(event.player, event.block.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Players placing blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (isActionBlocked(event.player, event.block.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Players emptying buckets
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (isActionBlocked(event.player, event.block.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Players filling buckets
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (isActionBlocked(event.player, event.block.chunk)) {
            event.isCancelled = true
        }
    }
}