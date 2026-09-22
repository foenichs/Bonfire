package com.foenichs.bonfire.listener

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.service.VisualService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

class PlayerListener(
    private val plugin: Bonfire,
    private val visualService: VisualService
) : Listener {

    /**
     * Primary handler for player movement
     */
    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val crossedChunk = event.from.chunk != event.to.chunk
        val crossedLayer = ChunkPos.layerFor(event.from) != ChunkPos.layerFor(event.to)
        if (crossedChunk || crossedLayer) {
            visualService.refresh(event.player, event.to)
        }
    }

    /**
     * Ensures visual states update immediately upon teleportation
     */
    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        visualService.refresh(event.player, event.to)
    }

    /**
     * Initializes state on join and refreshes affected players
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val p = event.player
        visualService.refresh(p, p.location)
        visualService.refreshForOwner(p.uniqueId)
    }

    /**
     * Refreshes other players when an owner leaves
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val p = event.player
        visualService.cleanup(p)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            visualService.refreshForOwner(p.uniqueId)
        })
    }
}