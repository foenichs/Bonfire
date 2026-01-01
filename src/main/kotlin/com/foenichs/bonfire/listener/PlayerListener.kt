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
     * Initializes state on join and refreshes affected players
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val p = event.player
        // Initialize the player state and notification immediately
        handlePlayerUpdate(p, p.location, true)
        refreshAffectedPlayers(p.uniqueId)
    }

    /**
     * Refreshes other players when an owner leaves
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val p = event.player
        visualService.removeAttachment(p)
        lastOwners.remove(p.uniqueId)
        refreshAffectedPlayers(p.uniqueId)
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
            val hasCache = lastOwners.containsKey(p.uniqueId)

            if (!hasCache || lastOwner != currOwner) {
                lastOwners[p.uniqueId] = currOwner

                if (currOwner != null) {
                    msg.actionBar(p, Bukkit.getOfflinePlayer(currOwner).name ?: "Unknown")
                } else if (hasCache) {
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
                }
            }
        })
    }
}