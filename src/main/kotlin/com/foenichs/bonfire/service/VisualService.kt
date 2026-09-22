package com.foenichs.bonfire.service

import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Messenger
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Creeper
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team
import java.util.*

class VisualService(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val limits: LimitService,
    private val msg: Messenger
) {
    private val lastOwners = mutableMapOf<UUID, UUID?>()
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
    fun setEntityException(player: Player, location: Location = player.location) {
        entityException.add(player.uniqueId)
        refresh(player, location)
    }

    /**
     * Removes the player from the entity exception set, restoring the restriction.
     */
    fun clearEntityException(player: Player, location: Location = player.location) {
        entityException.remove(player.uniqueId)
        refresh(player, location)
    }

    /**
     * Refresh a player's attributes, gamemode, collision, command tree, and action bar
     */
    fun refresh(player: Player, location: Location = player.location, notifyChunkChange: Boolean = false) {
        val claim = registry.getAt(location)

        // Manage dynamic command tree refreshes
        updateCommandTree(player, location)

        // Manage action bar notifications
        val currOwner = claim?.owner
        val lastOwner = lastOwners[player.uniqueId]
        val hasCache = lastOwners.containsKey(player.uniqueId)

        if (notifyChunkChange || !hasCache || lastOwner != currOwner) {
            lastOwners[player.uniqueId] = currOwner
            if (currOwner != null) {
                msg.actionBar(player, Bukkit.getOfflinePlayer(currOwner).name ?: "Unknown")
            } else if (hasCache || notifyChunkChange) {
                msg.unclaimedBar(player)
            }
        }

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
     * Refreshes all online players within a specific chunk
     */
    fun refreshChunk(pos: ChunkPos) {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (ChunkPos.of(player.location) == pos) {
                refresh(player, player.location, notifyChunkChange = true)
            }
        }
    }

    /**
     * Refreshes all online players within any chunk of a claim
     */
    fun refreshClaim(claim: Claim) {
        val claimId = claim.id
        Bukkit.getOnlinePlayers().forEach { player ->
            val loc = player.location
            if (registry.getAt(loc)?.id == claimId || claim.chunks.contains(ChunkPos.of(loc))) {
                refresh(player, loc, notifyChunkChange = true)
            }
        }
    }

    /**
     * Refreshes all online players in claims associated with an owner
     */
    fun refreshForOwner(ownerId: UUID) {
        Bukkit.getOnlinePlayers().forEach { player ->
            val claim = registry.getAt(player.location)
            if (claim != null && (claim.owner == ownerId || claim.trustedOnline.contains(ownerId))) {
                refresh(player, player.location)
            }
        }
    }

    /**
     * Refreshes the command tree when player context or claim state changes
     */
    private fun updateCommandTree(player: Player, location: Location = player.location) {
        val claim = registry.getAt(location)

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
        lastOwners.remove(player.uniqueId)
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