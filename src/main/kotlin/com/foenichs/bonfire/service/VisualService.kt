package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Creeper
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team
import java.util.*

class VisualService(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val limits: LimitService
) {
    private val lastCommandStates = mutableMapOf<UUID, CommandState>()
    private val entityException = mutableSetOf<UUID>()

    /**
     * Data classes to track states for command tree refreshing
     */
    private data class CommandState(val canClaim: Boolean, val isOwner: Boolean, val canRemove: Boolean, val rules: RuleState?)
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

        // Manage dynamic command tree refreshes
        updateCommandTree(player)

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
     * Dynamically refreshes the command tree if state changes
     */
    private fun updateCommandTree(player: Player) {
        val claim = registry.getAt(player.location)

        val l = limits.getLimits(player)
        val canClaim = claim == null && registry.getOwnedChunks(player.uniqueId) < l.maxChunks && registry.getOwnedClaimsCount(player.uniqueId) < l.maxClaims
        val isStrictOwner = claim != null && claim.owner == player.uniqueId
        val canRemove = claim != null && isStrictOwner && (claim.trustedAlways.isNotEmpty() || claim.trustedOnline.isNotEmpty())
        val currentRules = claim?.let { RuleState(it.allowBlockBreak, it.allowBlockInteract, it.allowEntityInteract) }

        val currentState = CommandState(canClaim, isStrictOwner, canRemove, currentRules)
        if (lastCommandStates[player.uniqueId] != currentState) {
            lastCommandStates[player.uniqueId] = currentState
            player.updateCommands()
        }
    }

    /**
     * Cleans up state caches when a player leaves
     */
    fun cleanup(player: Player) {
        lastCommandStates.remove(player.uniqueId)
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
        instance.baseValue = attr.defaultValue
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