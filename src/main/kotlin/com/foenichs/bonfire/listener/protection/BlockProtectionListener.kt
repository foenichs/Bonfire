package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.ProjectileHitEvent
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

    /**
     * Projectiles hitting blocks (smashing decorated pots, etc.)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val block = event.hitBlock ?: return
        val claim = registry.getAt(block.chunk) ?: return
        val shooter = event.entity.shooter as? Player

        if (shooter != null && protection.canBypass(shooter, block.chunk)) return

        if (!claim.allowBlockInteract) {
            event.isCancelled = true
            return
        }

        if (block.type == Material.DECORATED_POT && !claim.allowBlockBreak) {
            event.isCancelled = true
        }
    }
}