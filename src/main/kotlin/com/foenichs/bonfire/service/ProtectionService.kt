package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable

class ProtectionService(private val registry: ClaimRegistry) {

    /**
     * Standard bypass check (Owner, TrustedAlways, TrustedOnline, or Creative/Spectator)
     */
    fun canBypass(player: Player, chunk: Chunk): Boolean {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return true

        val claim = registry.getAt(chunk) ?: return true
        val uuid = player.uniqueId

        if (claim.owner == uuid) return true
        if (claim.trustedAlways.contains(uuid)) return true

        if (claim.trustedOnline.contains(uuid)) {
            return Bukkit.getPlayer(claim.owner)?.isOnline == true
        }
        return false
    }

    /**
     * Checks if a player is the owner of a tamed entity
     */
    fun ownsEntity(player: Player, entity: Entity): Boolean {
        if (entity is Tameable && entity.ownerUniqueId == player.uniqueId) return true
        if (entity is AbstractHorse && entity.ownerUniqueId == player.uniqueId) return true
        return false
    }

    /**
     * Logic for world interactions (Pistons, Water)
     */
    fun isWorldActionAllowed(from: Chunk, to: Chunk): Boolean {
        val claimFrom = registry.getAt(from)
        val claimTo = registry.getAt(to)
        return claimFrom?.id == claimTo?.id
    }

    /**
     * Checks allowBlockBreak for a block
     */
    fun checkAllowBlockBreak(targetChunk: Chunk): Boolean {
        val claim = registry.getAt(targetChunk) ?: return true
        return claim.allowBlockBreak
    }
}