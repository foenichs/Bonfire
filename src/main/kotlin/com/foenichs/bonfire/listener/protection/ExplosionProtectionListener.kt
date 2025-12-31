package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent

class ExplosionProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Explosions caused by entities (TNT, Creepers, Fireballs)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val source = event.entity
        val igniter = if (source is TNTPrimed) source.source as? Player else null
        val creeperTarget = if (source is Creeper) source.target as? Player else null

        val iterator = event.blockList().iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            val claim = registry.getAt(block.chunk) ?: continue

            // TNT ignited by a trusted player bypasses rules
            if (igniter != null) {
                if (!protection.canBypass(igniter, block.chunk)) iterator.remove()
                continue
            }

            // Creeper targeting a trusted player
            if (source is Creeper) {
                if (creeperTarget != null && protection.canBypass(creeperTarget, block.chunk)) continue
                if (!claim.allowBlockBreak) iterator.remove()
                continue
            }

            // Other explosions respect allowBlockBreak
            if (!claim.allowBlockBreak) iterator.remove()
        }
    }

    /**
     * Explosions caused by blocks (Beds, Respawn Anchors)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val iterator = event.blockList().iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            val claim = registry.getAt(block.chunk) ?: continue
            if (!claim.allowBlockBreak) iterator.remove()
        }
    }
}