package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Creeper
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.scoreboard.Team
import java.util.*

class VisualService(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val limits: LimitService
) {
    private val attachments = mutableMapOf<UUID, PermissionAttachment>()
    private val lastRuleStates = mutableMapOf<UUID, RuleState?>()
    private val entityException = mutableSetOf<UUID>()

    /**
     * Data class to track the state of claim rules for command refreshing
     */
    private data class RuleState(val allowBreak: Boolean, val allowInteract: Boolean, val allowEntity: String)

    /**
     * Lazy-initialized team to disable physical collision via scoreboard
     */
    private val noCollideTeam: Team by lazy {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        var team = scoreboard.getTeam("BonfireNoCollide")
        if (team == null) {
            team = scoreboard.registerNewTeam("BonfireNoCollide")
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)
        }
        team
    }

    /**
     * Adds the player to the entity exception set, resetting ENTITY_INTERACTION_RANGE temporarily.
     */
    fun setEntityException(player: Player) {
        entityException.add(player.uniqueId)
        updateValues(player)
    }

    /**
     * Removes the player from the entity exception set, restoring the restriction.
     */
    fun clearEntityException(player: Player) {
        entityException.remove(player.uniqueId)
        updateValues(player)
    }

    /**
     * Updates client-side attributes, gamemodes, and collision states
     */
    fun updateValues(player: Player) {
        val location = player.location
        val claim = registry.getAt(location)

        // Manage dynamic command permissions and tree refreshes
        updatePermissions(player)

        if (claim == null || protection.canBypass(player, location)) {
            resetPlayer(player)
            return
        }

        // Apply block interaction logic
        if (!claim.allowBlockBreak && claim.allowBlockInteract) {
            if (player.gameMode != GameMode.ADVENTURE) player.gameMode = GameMode.ADVENTURE
            resetAttribute(player, Attribute.BLOCK_INTERACTION_RANGE)
        } else if (!claim.allowBlockBreak) {
            if (player.gameMode == GameMode.ADVENTURE) player.gameMode = GameMode.SURVIVAL
            player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.baseValue = 0.0
        } else {
            if (player.gameMode == GameMode.ADVENTURE) player.gameMode = GameMode.SURVIVAL
            resetAttribute(player, Attribute.BLOCK_INTERACTION_RANGE)
        }

        // Apply entity interaction logic respecting entityException
        val entityRule = claim.allowEntityInteract
        when (entityRule) {
            "false" -> {
                dropNearbyAggro(player)
                if (entityException.contains(player.uniqueId)) {
                    resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
                } else {
                    player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.baseValue = 0.0
                }
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            "onlyMounts" -> {
                dropNearbyAggro(player)
                if (entityException.contains(player.uniqueId)) {
                    resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
                } else {
                    player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.baseValue = 0.0
                }
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            else -> {
                resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
                if (noCollideTeam.hasEntry(player.name)) noCollideTeam.removeEntry(player.name)
            }
        }
    }

    /**
     * Dynamically updates player permissions and refreshes the command tree if state changes
     */
    private fun updatePermissions(player: Player) {
        val attachment = attachments.getOrPut(player.uniqueId) { player.addAttachment(plugin) }
        val claim = registry.getAt(player.location)

        // Only show claim if in wilderness and under limit
        val canClaim = claim == null && registry.getOwnedChunks(player.uniqueId) < limits.getLimits(player).maxChunks

        // Only show management subcommands for the owner
        val isStrictOwner = claim != null && claim.owner == player.uniqueId
        val canRemove = isStrictOwner && (claim.trustedAlways.isNotEmpty() || claim.trustedOnline.isNotEmpty())

        // Track the current values of the rules to detect internal claim updates
        val currentRules = claim?.let { RuleState(it.allowBlockBreak, it.allowBlockInteract, it.allowEntityInteract) }
        val lastRules = lastRuleStates[player.uniqueId]

        val changedClaim = attachment.permissions.getOrDefault("bonfire.command.claim", false) != canClaim
        val changedOwner = attachment.permissions.getOrDefault("bonfire.command.owner", false) != isStrictOwner
        val changedRemove = attachment.permissions.getOrDefault("bonfire.command.removeplayer", false) != canRemove
        val changedRules = currentRules != lastRules

        // Rebuild the command tree if permissions OR rule values changed
        if (changedClaim || changedOwner || changedRemove || changedRules) {
            attachment.setPermission("bonfire.command.claim", canClaim)
            attachment.setPermission("bonfire.command.owner", isStrictOwner)
            attachment.setPermission("bonfire.command.removeplayer", canRemove)

            // Update rule cache and trigger Brigadier refresh
            lastRuleStates[player.uniqueId] = currentRules
            player.updateCommands()
        }
    }

    /**
     * Cleans up permission attachments and state caches when a player leaves
     */
    fun removeAttachment(player: Player) {
        attachments.remove(player.uniqueId)?.remove()
        lastRuleStates.remove(player.uniqueId)
        entityException.remove(player.uniqueId)
    }

    /**
     * Restores a player to standard properties
     */
    private fun resetPlayer(player: Player) {
        if (player.gameMode == GameMode.ADVENTURE) {
            player.gameMode = GameMode.SURVIVAL
        }

        resetAttribute(player, Attribute.BLOCK_INTERACTION_RANGE)
        resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
        if (noCollideTeam.hasEntry(player.name)) noCollideTeam.removeEntry(player.name)
    }

    /**
     * Resets a specific attribute to its vanilla default value
     */
    private fun resetAttribute(player: Player, attr: Attribute) {
        val instance = player.getAttribute(attr) ?: return
        instance.baseValue = instance.defaultValue
    }

    /**
     * Forces nearby mobs to lose interest and stops creepers from exploding
     */
    private fun dropNearbyAggro(player: Player) {
        player.getNearbyEntities(32.0, 32.0, 32.0).forEach { entity ->
            if (entity is Mob && entity.target == player) {
                entity.target = null
                if (entity is Creeper) {
                    entity.isIgnited = false
                }
            }
        }
    }
}