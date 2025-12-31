package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Creeper
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team

class VisualService(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) {

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
     * Updates client-side attributes, gamemodes, and collision states
     */
    fun updateValues(player: Player) {
        val chunk = player.location.chunk
        val claim = registry.getAt(chunk)

        if (claim == null || protection.canBypass(player, chunk)) {
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

        // Apply entity interaction logic
        val entityRule = claim.allowEntityInteract
        when (entityRule) {
            "false" -> {
                dropNearbyAggro(player)
                player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.baseValue = 0.0
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            "onlyMounts" -> {
                dropNearbyAggro(player)
                resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
                if (!noCollideTeam.hasEntry(player.name)) noCollideTeam.addEntry(player.name)
            }

            else -> {
                resetAttribute(player, Attribute.ENTITY_INTERACTION_RANGE)
                if (noCollideTeam.hasEntry(player.name)) noCollideTeam.removeEntry(player.name)
            }
        }
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