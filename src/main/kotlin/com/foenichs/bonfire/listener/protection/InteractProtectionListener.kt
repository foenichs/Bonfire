package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
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
            if (protection.canBypass(player, block.chunk)) return

            val claim = registry.getAt(block.chunk) ?: return

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
}