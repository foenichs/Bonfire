package com.foenichs.bonfire.listener

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.service.VisualService
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Messenger
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.*

class PlayerListener(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val msg: Messenger,
    private val visualService: VisualService
) : Listener {
    private val lastOwners = mutableMapOf<UUID, UUID?>()

    /**
     * Primary handler for player movement
     */
    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        handlePlayerUpdate(event.player, event.to, event.from.chunk != event.to.chunk)
    }

    /**
     * Ensures visual states update immediately upon teleportation
     */
    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        val to = event.to
        handlePlayerUpdate(event.player, to, true)
    }

    /**
     * Refreshes other players when an owner joins
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        refreshAffectedPlayers(event.player.uniqueId)
    }

    /**
     * Refreshes other players when an owner leaves
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastOwners.remove(event.player.uniqueId)
        refreshAffectedPlayers(event.player.uniqueId)
    }

    /**
     * Logic for visual updates and chunk ownership notifications
     */
    private fun handlePlayerUpdate(p: Player, loc: Location, chunkChanged: Boolean) {
        visualService.updateValues(p)

        if (chunkChanged) {
            val chunk = loc.chunk
            val currOwner = registry.getAt(chunk)?.owner
            val lastOwner = lastOwners[p.uniqueId]

            if (lastOwner != currOwner || !lastOwners.containsKey(p.uniqueId)) {
                lastOwners[p.uniqueId] = currOwner
                p.updateCommands()

                if (currOwner != null) {
                    msg.actionBar(p, Bukkit.getOfflinePlayer(currOwner).name ?: "Unknown")
                } else if (lastOwner != null) {
                    msg.unclaimedBar(p)
                }
            }
        }
    }

    /**
     * Updates the cached owner for a specific player manually
     */
    fun updateCache(p: Player) {
        lastOwners[p.uniqueId] = registry.getAt(p.location.chunk)?.owner
    }

    /**
     * Finds all online players in the specified owner's claims and refreshes them (delay to ensure correct owner status)
     */
    private fun refreshAffectedPlayers(ownerId: UUID) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
                val claim = registry.getAt(onlinePlayer.location.chunk)
                if (claim?.owner == ownerId) {
                    visualService.updateValues(onlinePlayer)
                    onlinePlayer.updateCommands()
                }
            }
        })
    }
}