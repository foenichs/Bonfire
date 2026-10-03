package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Messenger
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Creeper
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scoreboard.Team
import java.util.*

class VisualService(
    plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val limits: LimitService,
    private val msg: Messenger,
    private val escape: EscapeService
) {
    private val previousGameModeKey = NamespacedKey(plugin, "previous_gamemode")
    private val blockReachKey = NamespacedKey(plugin, "block_reach")
    private val entityReachKey = NamespacedKey(plugin, "entity_reach")

    private val lastOwners = mutableMapOf<UUID, UUID?>()
    private val lastCommandStates = mutableMapOf<UUID, CommandState>()
    private val entityException = mutableSetOf<UUID>()
    private val lastRefreshTicks = mutableMapOf<UUID, Int>()

    companion object {
        private const val REFRESH_INTERVAL_TICKS = 5
    }

    /**
     * Data classes to track states for command tree refreshing
     */
    private data class CommandState(val canClaim: Boolean, val canEscape: Boolean, val isOwner: Boolean, val canRemove: Boolean, val rules: RuleState?)
    private data class RuleState(val blockActions: String, val entityActions: String)

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
        if (entityException.add(player.uniqueId)) refresh(player, location) else refreshThrottled(player, location)
    }

    /**
     * Removes the player from the entity exception set, restoring the restriction.
     */
    fun clearEntityException(player: Player, location: Location = player.location) {
        if (entityException.remove(player.uniqueId)) refresh(player, location) else refreshThrottled(player, location)
    }

    /**
     * Resyncs moving players, at most once every few ticks
     */
    private fun refreshThrottled(player: Player, location: Location) {
        val last = lastRefreshTicks[player.uniqueId]
        if (last != null && Bukkit.getCurrentTick() - last < REFRESH_INTERVAL_TICKS) return
        refresh(player, location)
    }

    /**
     * Refresh a player's attributes, gamemode, collision, command tree, and action bar
     */
    fun refresh(player: Player, location: Location = player.location, forceActionBar: Boolean = false) {
        lastRefreshTicks[player.uniqueId] = Bukkit.getCurrentTick()
        val claim = registry.getAt(location)

        // Manage dynamic command tree refreshes
        updateCommandTree(player, location)

        // Manage action bar notifications
        val currOwner = claim?.owner
        val lastOwner = lastOwners[player.uniqueId]
        val hasCache = lastOwners.containsKey(player.uniqueId)

        if (forceActionBar || !hasCache || lastOwner != currOwner) {
            lastOwners[player.uniqueId] = currOwner
            if (currOwner != null) {
                msg.actionBar(player, Bukkit.getOfflinePlayer(currOwner).name ?: "Unknown")
            } else if (hasCache) {
                msg.unclaimedBar(player)
            }
        }

        if (claim == null || protection.canBypass(player, location)) {
            resetPlayer(player)
            return
        }

        // Apply block interaction logic
        if (claim.blockActions == "interactOnly") {
            enterAdventure(player)
            allowReach(player, Attribute.BLOCK_INTERACTION_RANGE, blockReachKey)
        } else if (claim.blockActions == "never") {
            leaveAdventure(player)
            restrictReach(player, Attribute.BLOCK_INTERACTION_RANGE, blockReachKey)
        } else {
            leaveAdventure(player)
            allowReach(player, Attribute.BLOCK_INTERACTION_RANGE, blockReachKey)
        }

        // Apply entity interaction logic respecting entityException
        val entityRule = claim.entityActions
        when (entityRule) {
            "never" -> {
                dropNearbyAggro(player)
                if (entityException.contains(player.uniqueId)) {
                    allowReach(player, Attribute.ENTITY_INTERACTION_RANGE, entityReachKey)
                } else {
                    restrictReach(player, Attribute.ENTITY_INTERACTION_RANGE, entityReachKey)
                }
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            "interactOnly" -> {
                dropNearbyAggro(player)
                allowReach(player, Attribute.ENTITY_INTERACTION_RANGE, entityReachKey)
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            else -> {
                allowReach(player, Attribute.ENTITY_INTERACTION_RANGE, entityReachKey)
                if (noCollideTeam.hasEntry(player.name)) noCollideTeam.removeEntry(player.name)
            }
        }
    }

    /**
     * Switches to adventure mode, remembering the previous game mode
     */
    private fun enterAdventure(player: Player) {
        if (player.gameMode == GameMode.ADVENTURE) return
        player.persistentDataContainer.set(previousGameModeKey, PersistentDataType.STRING, player.gameMode.name)
        player.gameMode = GameMode.ADVENTURE
    }

    /**
     * Restores the gamemode from before a claim switched to adventure mode
     */
    private fun leaveAdventure(player: Player) {
        val previous = player.persistentDataContainer.get(previousGameModeKey, PersistentDataType.STRING) ?: return
        player.persistentDataContainer.remove(previousGameModeKey)
        if (player.gameMode == GameMode.ADVENTURE) {
            player.gameMode = runCatching { GameMode.valueOf(previous) }.getOrDefault(GameMode.SURVIVAL)
        }
    }

    /**
     * Reduces an interaction range to 0 with a modifier, leaving the base value untouched
     */
    private fun restrictReach(player: Player, attr: Attribute, key: NamespacedKey) {
        val instance = player.getAttribute(attr) ?: return
        if (instance.getModifier(key) == null) {
            instance.addTransientModifier(AttributeModifier(key, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
        }
    }

    /**
     * Removes the interaction range restriction again
     */
    private fun allowReach(player: Player, attr: Attribute, key: NamespacedKey) {
        player.getAttribute(attr)?.removeModifier(key)
    }

    /**
     * Refreshes all online players within a specific chunk
     */
    fun refreshChunk(pos: ChunkPos) {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (ChunkPos.of(player.location) == pos) {
                refresh(player, player.location, forceActionBar = true)
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
                refresh(player, loc, forceActionBar = true)
            }
        }
    }

    /**
     * Refreshes all online players in claims associated with an owner (join, quit, trust changes)
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
        val canClaim = claim == null && registry.getOwnedChunks(player.uniqueId) < l.maxChunks &&
                (registry.getOwnedClaimsCount(player.uniqueId) < l.maxClaims ||
                        !ClaimService.isNewClaim(registry, player.uniqueId, location))
        val canEscape = escape.isRestricted(player, location)
        val isStrictOwner = claim != null && claim.owner == player.uniqueId
        val canRemove = claim != null && isStrictOwner && (claim.trustedAlways.isNotEmpty() || claim.trustedOnline.isNotEmpty())
        val currentRules = claim?.let { RuleState(it.blockActions, it.entityActions) }

        val currentState = CommandState(canClaim, canEscape, isStrictOwner, canRemove, currentRules)
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
        lastRefreshTicks.remove(player.uniqueId)
    }

    /**
     * Restores a player to standard properties
     */
    private fun resetPlayer(player: Player) {
        leaveAdventure(player)
        allowReach(player, Attribute.BLOCK_INTERACTION_RANGE, blockReachKey)
        allowReach(player, Attribute.ENTITY_INTERACTION_RANGE, entityReachKey)
        if (noCollideTeam.hasEntry(player.name)) noCollideTeam.removeEntry(player.name)
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