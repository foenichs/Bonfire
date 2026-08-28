package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable

class ProtectionService(private val registry: ClaimRegistry) {

    /**
     * Standard bypass check (Owner, TrustedAlways, TrustedOnline, or Creative+OP/Spectator)
     */
    fun canBypass(player: Player, location: Location): Boolean {
        if (player.gameMode == GameMode.SPECTATOR || (player.gameMode == GameMode.CREATIVE && player.isOp)) return true

        val claim = registry.getAt(location) ?: return true
        val uuid = player.uniqueId

        if (claim.owner == uuid) return true
        if (claim.trustedAlways.contains(uuid)) return true

        if (claim.trustedOnline.contains(uuid)) {
            return Bukkit.getPlayer(claim.owner)?.isOnline == true
        }
        return false
    }

    /**
     * Checks if the entity has a bonfire_origin_{claim.id} tag for the given location's claim
     */
    fun isOrigin(entity: Entity?, location: Location): Boolean {
        val claim = registry.getAt(location) ?: return false
        val tags = entity?.scoreboardTags ?: return false

        // Current ID
        if (tags.contains("bonfire_origin_${claim.id}")) return true

        // Any of the legacy IDs
        return claim.legacyIds.any { legacyId -> tags.contains("bonfire_origin_$legacyId") }
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
    fun isWorldActionAllowed(from: Location, to: Location): Boolean {
        val claimFrom = registry.getAt(from)
        val claimTo = registry.getAt(to) ?: return true
        if (claimFrom?.id == claimTo.id) return true

        if (claimFrom != null) {
            val ownerTo = claimTo.owner
            val ownerFrom = claimFrom.owner
            if (ownerTo == ownerFrom) return true

            // Always allowed for claims of added players
            if (claimTo.trustedAlways.contains(ownerFrom)) return true
            if (claimTo.trustedOnline.contains(ownerFrom) && Bukkit.getPlayer(ownerTo)?.isOnline == true) return true
        }

        return false
    }

    /**
     * Checks allowBlockBreak for a block
     */
    fun checkAllowBlockBreak(targetLocation: Location): Boolean {
        val claim = registry.getAt(targetLocation) ?: return true
        return claim.allowBlockBreak
    }
}