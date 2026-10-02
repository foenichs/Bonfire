package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Hanging
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.plugin.Plugin

class BlockProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Helper for permission checks
     */
    private fun isActionBlocked(player: Player, location: Location, rule: (Claim) -> String = { it.blockActions }): Boolean {
        if (protection.canBypass(player, location)) return false
        val claim = registry.getAt(location) ?: return false
        return rule(claim) != "always"
    }

    /**
     * Picks entity actions for leash knots and block actions for other hanging entities
     */
    private fun hangingRule(hanging: Hanging): (Claim) -> String =
        if (hanging is LeashHitch) { c -> c.entityActions } else { c -> c.blockActions }

    /**
     * Players breaking blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (isActionBlocked(event.player, event.block.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Players placing blocks
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (isActionBlocked(event.player, event.block.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Players emptying buckets
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (isActionBlocked(event.player, event.block.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Players filling buckets
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (isActionBlocked(event.player, event.block.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Breaking item frames, paintings, or leash knots
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val location = event.entity.location
        val rule = hangingRule(event.entity)

        if (event.cause == HangingBreakEvent.RemoveCause.EXPLOSION) {
            val source = (event as? HangingBreakByEntityEvent)?.damageSource?.directEntity
            if (protection.isExplosionBlocked(source, location, rule)) event.isCancelled = true
            return
        }

        // The responsible player, also when shooting projectiles
        val remover = (event as? HangingBreakByEntityEvent)?.damageSource?.causingEntity as? Player ?: return
        if (isActionBlocked(remover, location, rule)) {
            event.isCancelled = true
        }
    }

    /**
     * Placing item frames, paintings, or leash knots
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (isActionBlocked(player, event.entity.location, hangingRule(event.entity))) {
            event.isCancelled = true
        }
    }

    /**
     * Registers cushion break handling, as its events only exist since 26.3
     */
    fun registerCushionBreaks(plugin: Plugin) {
        @Suppress("UNCHECKED_CAST")
        val eventClass = runCatching { Class.forName("io.papermc.paper.event.entity.EntityBreakEvent") }.getOrNull() as? Class<out Event> ?: return
        Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.LOWEST, { _, event ->
            if (eventClass.isInstance(event)) onCushionBreak(event)
        }, plugin, true)
    }

    /**
     * Breaking cushions, by players or explosions
     */
    private fun onCushionBreak(event: Event) {
        val location = (event as EntityEvent).entity.location
        val cause = event.javaClass.getMethod("getCause").invoke(event) as Enum<*>
        val damageSource = runCatching { event.javaClass.getMethod("getDamageSource").invoke(event) }.getOrNull() as? DamageSource

        if (cause.name == "EXPLOSION") {
            if (protection.isExplosionBlocked(damageSource?.directEntity, location)) (event as Cancellable).isCancelled = true
            return
        }

        // The responsible player, also when shooting projectiles
        val remover = damageSource?.causingEntity as? Player ?: return
        if (isActionBlocked(remover, location)) {
            (event as Cancellable).isCancelled = true
        }
    }

    /**
     * Projectiles hitting blocks (smashing decorated pots, etc.)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val block = event.hitBlock ?: return
        val claim = registry.getAt(block.location) ?: return
        val shooter = event.entity.shooter as? Player

        if (shooter != null && protection.canBypass(shooter, block.location)) return

        if (claim.blockActions == "never") {
            event.isCancelled = true
            return
        }

        if (block.type == Material.DECORATED_POT && claim.blockActions != "always") {
            event.isCancelled = true
        }
    }
}