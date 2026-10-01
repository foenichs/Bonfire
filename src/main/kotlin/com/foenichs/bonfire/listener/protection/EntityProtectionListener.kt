package com.foenichs.bonfire.listener.protection

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.service.VisualService
import com.foenichs.bonfire.storage.ClaimRegistry
import io.papermc.paper.event.player.PlayerNameEntityEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityEnterLoveModeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerEggThrowEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

class EntityProtectionListener(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val visualService: VisualService
) : Listener {

    private val leashedMobs = mutableSetOf<UUID>()
    private val targetingMobs = mutableSetOf<UUID>()

    init {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            checkLeashedMobs()
            checkTargetingMobs()
        }, 1L, 1L)
    }

    /**
     * Origin-tags or unleashes mobs led into claims
     */
    private fun checkLeashedMobs() {
        val iterator = leashedMobs.iterator()
        while (iterator.hasNext()) {
            val mob = Bukkit.getEntity(iterator.next()) as? Mob
            val holder = mob?.takeIf { it.isValid && it.isLeashed }?.leashHolder as? Player
            if (mob == null || holder == null) {
                iterator.remove()
                continue
            }
            if (protection.ownsEntity(holder, mob)) continue

            originTagFor(holder, mob)

            val mobLocation = mob.location
            if (isLeashBlocked(holder, mob, holder.location) || isLeashBlocked(holder, mob, mobLocation)) {
                mob.setLeashHolder(null)
                mob.world.dropItemNaturally(mobLocation, ItemStack(Material.LEAD))
                iterator.remove()
            }
        }
    }

    /**
     * Origin-tags mobs chasing authorized players into claims
     */
    private fun checkTargetingMobs() {
        val iterator = targetingMobs.iterator()
        while (iterator.hasNext()) {
            val mob = Bukkit.getEntity(iterator.next()) as? Mob
            val target = mob?.takeIf { it.isValid }?.target as? Player
            if (mob == null || target == null) {
                iterator.remove()
                continue
            }
            originTagFor(target, mob)
        }
    }

    /**
     * Origin-tags a mob if the player is authorized in its claim
     */
    private fun originTagFor(player: Player, entity: Entity) {
        if (entity !is Mob) return
        val location = entity.location
        val claim = registry.getAt(location) ?: return
        if (protection.canBypass(player, location) && !protection.isOrigin(entity, location)) {
            protection.setOrigin(entity, claim)
        }
    }

    /**
     * Checks if a holder can't lead a mob into a claim
     */
    private fun isLeashBlocked(holder: Player, mob: Mob, location: Location): Boolean {
        val claim = registry.getAt(location) ?: return false
        return claim.entityActions != "always" && !protection.canBypass(holder, location) && !protection.isOrigin(mob, location)
    }

    /**
     * Apply attribute exceptions
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val location = event.to
        val claim = registry.getAt(location) ?: run {
            visualService.clearEntityException(player, location)
            return
        }
        if (protection.canBypass(player, location)) {
            visualService.clearEntityException(player, location)
            return
        }

        val allowEntity = claim.entityActions
        if (allowEntity != "never") {
            visualService.clearEntityException(player, location)
            return
        }

        val target = player.getTargetEntity(5)
        val isPet = target != null && protection.ownsEntity(player, target)

        if (isPet) {
            visualService.setEntityException(player, location)
        } else {
            visualService.clearEntityException(player, location)
        }
    }

    /**
     * Mobs targeting players
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return

        // Authorized players can be targeted normally
        if (protection.canBypass(target, target.location)) return

        val claim = registry.getAt(target.location) ?: return
        if (claim.entityActions != "always") {
            event.target = null
            event.isCancelled = true
        }
    }

    /**
     * Tracks mobs targeting players and origin-tags tempted ones
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTargeted(event: EntityTargetLivingEntityEvent) {
        val mob = event.entity as? Mob ?: return
        val player = event.target as? Player ?: return

        // Tempting doesn't set the target, but fires this event every tick while following
        if (event.reason == EntityTargetEvent.TargetReason.TEMPT) originTagFor(player, mob)
        else targetingMobs.add(mob.uniqueId)
    }

    /**
     * Leashing entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) {
        val player = event.player
        val entity = event.entity

        // Authorized players and pet owners are not restricted
        if (protection.ownsEntity(player, entity)) return

        // Holder stands in a claim the mob can't be led into
        if (event.leashHolder is Player && entity is Mob && isLeashBlocked(player, entity, player.location)) {
            event.isCancelled = true
            return
        }

        if (protection.canBypass(player, entity.location)) return

        val claim = registry.getAt(entity.location) ?: return
        if (claim.entityActions != "always") {
            event.isCancelled = true
        }
    }

    /**
     * Tracks mobs leashed by players
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerLeash(event: PlayerLeashEntityEvent) {
        val mob = event.entity as? Mob ?: return
        if (event.leashHolder !is Player) return
        leashedMobs.add(mob.uniqueId)
    }

    /**
     * Origin-tags animals fed by authorized players
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnterLoveMode(event: EntityEnterLoveModeEvent) {
        val player = event.humanEntity as? Player ?: return
        originTagFor(player, event.entity)
    }

    /**
     * Origin-tags mobs named by authorized players
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityName(event: PlayerNameEntityEvent) {
        originTagFor(event.player, event.entity)
    }

    /**
     * Breaking item frames, paintings, or leash knots
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val remover = event.remover as? Player ?: return

        // Authorized players can break these normally
        if (protection.canBypass(remover, event.entity.location)) return

        event.isCancelled = true
    }

    /**
     * Direct damage or explosions affecting entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val victimLocation = victim.location
        val claim = registry.getAt(victimLocation) ?: return

        // Resolve the responsible player (damager)
        val damager = when (val attacker = event.damager) {
            is Player -> attacker
            is Tameable -> attacker.owner as? Player
            is Projectile -> (attacker.shooter as? Player) ?: ((attacker.shooter as? Tameable)?.owner as? Player)
            is TNTPrimed -> attacker.source as? Player
            is Creeper -> (attacker.igniter as? Player) ?: (attacker.target as? Player)
            else -> null
        }

        // Authorized damagers can always deal damage
        if (damager != null && protection.canBypass(damager, victimLocation)) return

        // Mobs and world damage can only affect authorized players
        if (damager == null && victim is Player && protection.canBypass(victim, victimLocation)) return

        // Enforcement for unauthorized actors
        if (claim.entityActions != "always") {

            // Allow if a player is interacting with entities they own
            if (damager != null && protection.ownsEntity(damager, victim)) return

            // Block explosions and all other damage from unauthorized sources
            if (event.cause == DamageCause.ENTITY_EXPLOSION || event.cause == DamageCause.BLOCK_EXPLOSION) {
                event.isCancelled = true
                return
            }

            // Origin-tagged mobs may act within their claim
            val mobAttacker = (event.damager as? Mob) ?: ((event.damager as? Projectile)?.shooter as? Mob)
            if (damager == null && victim !is Player && mobAttacker != null && protection.isOrigin(mobAttacker, victimLocation)) return

            event.isCancelled = true
        }
    }

    /**
     * Knockback caused by entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityKnockback(event: EntityKnockbackByEntityEvent) {
        val victim = event.entity
        val victimLocation = victim.location
        val claim = registry.getAt(victimLocation) ?: return

        // Responsible player
        val damager = when (val source = event.hitBy) {
            is Player -> source
            is Tameable -> source.owner as? Player
            is Projectile -> (source.shooter as? Player) ?: ((source.shooter as? Tameable)?.owner as? Player)
            else -> null
        }

        // Authorized damagers can deal knockback normally
        if (damager != null && protection.canBypass(damager, victimLocation)) return

        // Only authorized players can be knocked back
        if (damager == null && victim is Player && protection.canBypass(victim, victimLocation)) return

        if (claim.entityActions != "always") {
            // Allow if the damager owns the victim
            if (damager != null && protection.ownsEntity(damager, victim)) return

            // Origin-tagged mobs may act within their claim
            val mobAttacker = (event.hitBy as? Mob) ?: ((event.hitBy as? Projectile)?.shooter as? Mob)
            if (damager == null && victim !is Player && mobAttacker != null && protection.isOrigin(mobAttacker, victimLocation)) return

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
        if (protection.canBypass(player, entity.location)) return

        val claim = registry.getAt(entity.location) ?: return
        if (claim.entityActions == "never") {
            event.isCancelled = true
        }
    }

    /**
     * Placing entities (boats, armor stands, etc.)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        val location = event.entity.location

        if (protection.canBypass(player, location)) return

        val claim = registry.getAt(location) ?: return
        if (claim.entityActions != "always") {
            event.isCancelled = true
        }
    }

    /**
     * Damaging vehicles like boats or minecarts
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        val attacker = event.attacker ?: return
        if (isVehicleActionBlocked(attacker, event.vehicle.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Destroying vehicles like boats or minecarts
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val attacker = event.attacker ?: return
        if (isVehicleActionBlocked(attacker, event.vehicle.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Checks if an attacker can't damage vehicles at a location
     */
    private fun isVehicleActionBlocked(attacker: Entity, location: Location): Boolean {
        val player = when (attacker) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            else -> null
        }
        if (player != null && protection.canBypass(player, location)) return false
        val claim = registry.getAt(location) ?: return false
        return claim.entityActions != "always"
    }

    /**
     * Drop vehicles when exiting in claims they don't originate from
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val vehicle = event.vehicle
        val player = event.exited as? Player ?: return
        val location = vehicle.location
        val claim = registry.getAt(location) ?: return

        if (protection.canBypass(player, location)) return

        if (claim.entityActions != "always") {
            if (!protection.isOrigin(vehicle, location)) {
                val material = when (vehicle) {
                    is Boat -> vehicle.boatMaterial
                    is Minecart -> Material.MINECART
                    else -> return
                }

                // Delay removal to properly finish vanilla dismount
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (vehicle.isValid) {
                        vehicle.world.dropItemNaturally(vehicle.location, ItemStack(material))
                        vehicle.remove()
                    }
                })
            }
        }
    }

    /**
     * Eggs spawning chickens
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerEggThrow(event: PlayerEggThrowEvent) {
        val player = event.player
        val location = event.egg.location
        val claim = registry.getAt(location) ?: return

        if (claim.entityActions != "always") {
            if (!protection.canBypass(player, location)) {
                event.isHatching = false
                event.numHatches = 0
            }
        }
    }
}