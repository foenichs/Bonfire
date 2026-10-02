package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntitySpawnEvent

class ExplosionProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Tags TNT when it spawns inside a claim.
     * This allows TNT dupers/cannons built inside a claim to work within that claim.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTntSpawn(event: EntitySpawnEvent) {
        if (event.entity !is TNTPrimed) return
        val claim = registry.getAt(event.location) ?: return
        event.entity.addScoreboardTag("bonfire_origin_${claim.id}")
    }

    /**
     * Explosions caused by entities (TNT, Creepers, Fireballs)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { protection.isExplosionBlocked(event.entity, it.location) }
    }

    /**
     * Explosions caused by blocks (Beds, Respawn Anchors)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { protection.isExplosionBlocked(null, it.location) }
    }
}