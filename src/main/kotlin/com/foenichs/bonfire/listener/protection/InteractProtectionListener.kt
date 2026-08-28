package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import io.papermc.paper.event.block.VaultChangeStateEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockReceiveGameEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEvent

class InteractProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Right-clicking blocks or physical pressure triggers
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player

        if (event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.PHYSICAL) {
            if (protection.canBypass(player, block.location)) return

            val claim = registry.getAt(block.location) ?: return

            if (
                event.action == Action.PHYSICAL &&
                (block.type == Material.FARMLAND || block.type == Material.TURTLE_EGG) &&
                !claim.allowBlockBreak
            ) {
                event.setUseInteractedBlock(Event.Result.DENY)
                event.isCancelled = true
                return
            }

            if (!claim.allowBlockInteract) {
                val itemInHand = event.item
                if (claim.allowBlockBreak && itemInHand != null && itemInHand.type.isBlock) {
                    return
                }

                event.setUseInteractedBlock(Event.Result.DENY)

                // Cancel physical actions like trampling or pressure plates
                if (event.action == Action.PHYSICAL) {
                    event.isCancelled = true
                }
            }
        }
    }

    /**
     * Arrows and dropped items triggering buttons or pressure plates
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityInteract(event: EntityInteractEvent) {
        val block = event.block
        val claim = registry.getAt(block.location) ?: return

        if (!claim.allowBlockInteract) {
            val entity = event.entity

            // Allow projectiles shot by an added player
            if (entity is Projectile) {
                val shooter = entity.shooter as? Player
                if (shooter != null && protection.canBypass(shooter, block.location)) return
                event.isCancelled = true
                return
            }

            // Allow items dropped by an added player
            if (entity is Item) {
                val throwerId = entity.thrower ?: return
                val thrower = Bukkit.getPlayer(throwerId)
                if (thrower != null) {
                    if (protection.canBypass(thrower, block.location)) return
                } else {
                    if (throwerId == claim.owner || claim.trustedAlways.contains(throwerId)) return
                }
                event.isCancelled = true
                return
            }
        }
    }

    /**
     * Blocks receiving game events like vibrations
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onReceiveGameEvent(event: BlockReceiveGameEvent) {
        val block = event.block
        val claim = registry.getAt(block.location) ?: return

        if (!claim.allowBlockInteract) {
            val entity = event.entity
            if (entity is Player && protection.canBypass(entity, block.location)) return

            event.isCancelled = true
        }
    }

    /**
     * Vault blocks changing state when a player approaches
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVaultChange(event: VaultChangeStateEvent) {
        val block = event.block
        val claim = registry.getAt(block.location) ?: return

        if (!claim.allowBlockInteract) {
            val player = event.player ?: return
            if (protection.canBypass(player, block.location)) return

            event.isCancelled = true
        }
    }

    // Trial spawners detecting players can't be implemented yet
    // https://github.com/PaperMC/Paper/issues/12988
}