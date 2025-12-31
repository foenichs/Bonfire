package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.entity.*
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent

class EntityProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Mobs targeting players
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return

        // Authorized players can be targeted normally
        if (protection.canBypass(target, target.location.chunk)) return

        val claim = registry.getAt(target.location.chunk) ?: return
        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {
            event.target = null
            event.isCancelled = true
        }
    }

    /**
     * Breaking item frames, paintings, or leash knots
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val remover = event.remover as? Player ?: return

        // Authorized players can break these normally
        if (protection.canBypass(remover, event.entity.location.chunk)) return

        event.isCancelled = true
    }

    /**
     * Direct damage or explosions affecting entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val victimChunk = victim.location.chunk
        val claim = registry.getAt(victimChunk) ?: return

        // If the victim is an authorized player, they take damage normally
        if (victim is Player && protection.canBypass(victim, victimChunk)) return

        // Resolve the responsible player (damager)
        val damager = when (val attacker = event.damager) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            is TNTPrimed -> attacker.source as? Player
            is Creeper -> (attacker.igniter as? Player) ?: (attacker.target as? Player)
            else -> null
        }

        // If the damager is an authorized player, they deal damage normally
        if (damager != null && protection.canBypass(damager, victimChunk)) return

        // Enforcement for unauthorized actors
        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {

            // Allow if a player is interacting with entities they own
            if (damager != null && protection.ownsEntity(damager, victim)) return

            // Block non-authorized damage (mobs hitting players, players hitting mobs, etc.)
            if (event.cause == DamageCause.ENTITY_EXPLOSION || event.cause == DamageCause.BLOCK_EXPLOSION) {
                event.isCancelled = true
                return
            }

            event.isCancelled = true
        }
    }

    /**
     * General entity interaction
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        processInteract(event.player, event.rightClicked, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        processInteract(event.player, event.rightClicked, event)
    }

    private fun processInteract(player: Player, entity: Entity, event: Cancellable) {
        // Authorized players and pet owners are not restricted
        if (protection.ownsEntity(player, entity)) return
        if (protection.canBypass(player, entity.location.chunk)) return

        val claim = registry.getAt(entity.location.chunk) ?: return
        if (claim.allowEntityInteract == "false") {
            event.isCancelled = true
        } else if (claim.allowEntityInteract == "onlyMounts") {
            val mountable = listOf(
                AbstractHorse::class, Pig::class, Strider::class, Boat::class,
                Minecart::class, Camel::class, Llama::class
            )
            // If the entity isn't mountable, block it
            if (mountable.none { it.isInstance(entity) }) {
                event.isCancelled = true
            }
        }
    }
}