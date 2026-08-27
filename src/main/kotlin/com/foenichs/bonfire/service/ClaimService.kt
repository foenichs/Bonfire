package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.listener.PlayerListener
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.storage.DatabaseManager
import com.foenichs.bonfire.ui.Messenger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Player
import java.util.*

class ClaimService(
    private val registry: ClaimRegistry,
    private val db: DatabaseManager,
    private val msg: Messenger,
    private val limits: LimitService,
    private val visualService: VisualService,
    private val playerListener: PlayerListener,
    private val mapServices: List<ClaimMapService>,
    private val migrationService: MigrationService,
    private val plugin: Bonfire
) {
    data class PendingMerge(val worldUuid: UUID, val chunkKey: Long, val claims: List<Claim>, val time: Long)
    private val pending = mutableMapOf<UUID, PendingMerge>()

    fun verifyPermissions(p: Player): Boolean {
        visualService.updateValues(p)
        val claim = registry.getAt(p.location.chunk)
        return claim != null && claim.owner == p.uniqueId
    }

    /**
     * Claiming a chunk and either adding it to a claim or creating a new claim
     */
    fun tryClaim(p: Player) {
        val ch = p.location.chunk; val w = ch.world.uid; val k = ch.chunkKey; val limits = limits.getLimits(p)
        if (registry.getAt(w, k) != null) return
        if (registry.getOwnedChunks(p.uniqueId) >= limits.maxChunks) {
            msg.send(p, Component.text().append(Component.text("You've reached your chunk limit. "))
                .append(Component.text("Keep playing to earn more chunks and claims.", NamedTextColor.GRAY)).build())
            return
        }

        val adj = findAdj(p, w, ch.x, ch.z)
        when {
            adj.size == 1 -> {
                val c = adj.first(); val pos = ChunkPos(w, k); c.chunks.add(pos); db.addChunk(c.id!!, pos)
                migrationService.processChunk(ch)
                updateClaimMarkers(c)
                msg.send(p, Component.text("Successfully claimed this chunk and added it to your claim."))
                finishAction(p, c.owner)
            }
            adj.size > 1 -> {
                val now = System.currentTimeMillis(); val ex = pending[p.uniqueId]
                if (ex != null && ex.chunkKey == k && (now - ex.time) <= 15000) { executeMerge(p, ex); pending.remove(p.uniqueId) }
                else {
                    pending[p.uniqueId] = PendingMerge(w, k, adj.sortedBy { it.id }, now)
                    msg.send(p, Component.text().append(Component.text("Claiming this chunk would merge two claims, overriding the settings of the claim that was created later.", NamedTextColor.GRAY)).append(Component.text(" If you want to merge both claims, run that command again.", NamedTextColor.WHITE)).build())
                }
            }
            else -> {
                if (registry.getOwnedClaimsCount(p.uniqueId) >= limits.maxClaims) {
                    msg.send(p, Component.text().append(Component.text("You've reached your claim limit. "))
                        .append(Component.text("Keep playing to earn more chunks and claims.", NamedTextColor.GRAY)).build())
                } else {
                    val id = db.createClaim(p.uniqueId); val pos = ChunkPos(w, k)
                    val defBreak = plugin.config.getBoolean("default-rules.allowBlockBreak", false)
                    val defInteract = plugin.config.getBoolean("default-rules.allowBlockInteract", false)
                    val defEntity = plugin.config.getString("default-rules.allowEntityInteract", "false")!!

                    val claim = Claim(id, p.uniqueId, mutableSetOf(pos), defBreak, defInteract, defEntity)
                    registry.add(claim); db.addChunk(id, pos); db.updateRules(claim)
                    migrationService.processChunk(ch)
                    updateClaimMarkers(claim)
                    msg.send(p, Component.text("Successfully claimed this chunk and created a new claim."))
                    finishAction(p, claim.owner)
                }
            }
        }
    }

    /**
     * Unclaiming a chunk and either removing it from the claim or deleting the claim
     */
    fun tryUnclaim(p: Player) {
        if (!verifyPermissions(p)) { msg.sendNoAccess(p); return }
        val ch = p.location.chunk; val pos = ChunkPos(ch.world.uid, ch.chunkKey); val c = registry.getAt(pos.worldUuid, pos.chunkKey) ?: return
        if (c.chunks.size <= 1) {
            handleClaimRemoval(c)
            msg.send(p, Component.text("Successfully unclaimed this chunk and deleted the claim."))
        } else if (isConnected(c, pos)) {
            handleChunkUnclaim(c, pos)
            msg.send(p, Component.text("Successfully unclaimed this chunk and removed it from your claim."))
            finishAction(p, null)
        } else {
            msg.send(p, Component.text().append(Component.text("You can't unclaim this chunk. ")).append(Component.text("Unclaiming it would split up your claim, please unclaim outer chunks first.", NamedTextColor.GRAY)).build())
        }
    }

    /**
     * Set a new owner for a claim (OP-only)
     */
    fun adminSetOwner(p: Player, newOwnerName: String) {
        val claim = registry.getAt(p.location.chunk) ?: run { msg.send(p, Component.text("Nothing changed, you aren't inside a claimed chunk.")); return }
        val offline = Bukkit.getOfflinePlayers().find { it.name?.equals(newOwnerName, true) == true } ?: run { msg.send(p, Component.text("Nothing changed, that player wasn't found on the server.")); return }

        val oldOwnerId = claim.owner
        claim.owner = offline.uniqueId
        db.updateOwner(claim.id!!, offline.uniqueId)
        updateClaimMarkers(claim)

        msg.send(p, Component.text("Successfully transferred the ownership of this claim to ${offline.name}."))
        finishActionForClaim(claim)
        Bukkit.getPlayer(oldOwnerId)?.let { visualService.updateValues(it) }
    }

    /**
     * Remove a claim (OP-only)
     */
    fun adminRemoveClaim(p: Player) {
        val claim = registry.getAt(p.location.chunk) ?: run { msg.send(p, Component.text("Nothing changed, you aren't inside a claim.")); return }
        handleClaimRemoval(claim)
        msg.send(p, Component.text("Successfully removed the claim and unclaimed all chunks."))
    }

    /**
     * Unclaim a chunk (OP-only)
     */
    fun adminUnclaimChunk(p: Player) {
        val chunk = p.location.chunk; val pos = ChunkPos(chunk.world.uid, chunk.chunkKey); val claim = registry.getAt(chunk) ?: run { msg.send(p, Component.text("Nothing changed, you are not inside a claim.")); return }

        if (claim.chunks.size <= 1) {
            handleClaimRemoval(claim)
            msg.send(p, Component.text("Successfully removed the claim and unclaimed all chunks."))
        } else if (isConnected(claim, pos)) {
            handleChunkUnclaim(claim, pos)
            msg.send(p, Component.text("Successfully unclaimed this chunk as an operator."))
            finishAction(p, null)
        } else {
            msg.send(p, Component.text("You can't unclaim this chunk. Unclaiming it would split up this claim, please unclaim outer chunks first."))
        }
    }

    /**
     * Remove all claims owned by a player (OP-only)
     */
    fun adminRemoveAll(p: Player, targetName: String) {
        val offline = Bukkit.getOfflinePlayers().find { it.name?.equals(targetName, true) == true } ?: run { msg.send(p, Component.text("Nothing changed, that player wasn't found on the server.")); return }
        val targetClaims = registry.getAll().filter { it.owner == offline.uniqueId }

        if (targetClaims.isEmpty()) { msg.send(p, Component.text("Nothing changed, this player has no claims.")); return }

        targetClaims.forEach { handleClaimRemoval(it) }
        msg.send(p, Component.text("Successfully removed ${targetClaims.size} claims owned by ${offline.name} and unclaimed all chunks."))
    }

    /**
     * Change a claim rule
     */
    fun setRule(p: Player, r: String, v: String) {
        if (!verifyPermissions(p)) { msg.sendNoAccess(p); return }
        val c = registry.getAt(p.location.chunk) ?: return
        if (r == "allowEntityInteract" && v != "true" && v != "false" && v != "onlyMounts") return
        when(r) { "allowBlockBreak" -> c.allowBlockBreak = v.toBoolean(); "allowBlockInteract" -> c.allowBlockInteract = v.toBoolean(); "allowEntityInteract" -> c.allowEntityInteract = v }
        db.updateRules(c)
        val desc = when (r) {
            "allowBlockBreak" -> if (v == "true") "Blocks on your claim can now be placed and destroyed by players, pistons, water, etc." else "Blocks on your claim can no longer be placed and destroyed by players, pistons, water, etc."
            "allowBlockInteract" -> if (v == "true") "Players can now interact with blocks on your claim." else "Players can no longer interact with blocks on your claim."
            "allowEntityInteract" -> when (v) { "true" -> "Players can now interact and collide with entites and get targetted by them."; "false" -> "Players can no longer interact and collide with entites or get targetted by them."; "onlyMounts" -> "Players can mount entites, but can't interact and collide with or get targetted by them."; else -> "" }
            else -> ""
        }
        msg.send(p, Component.text().append(Component.text("Set $r to $v. ")).append(Component.text(desc, NamedTextColor.GRAY)).build())
        visualService.updateValues(p)
        finishActionForClaim(c)
    }

    /**
     * Add players to claims
     */
    fun addTrust(p: Player, n: String, t: String) {
        if (!verifyPermissions(p)) { msg.sendNoAccess(p); return }
        val c = registry.getAt(p.location.chunk) ?: return
        val off = Bukkit.getOfflinePlayers().find { it.name?.equals(n, true) == true }
        if (off == null || (!off.hasPlayedBefore() && !off.isOnline)) {
            msg.send(p, Component.text().append(Component.text("This player wasn't found. ")).append(Component.text("They have to join once before they can be added to claims.", NamedTextColor.GRAY)).build())
            return
        }

        val isAlways = t == "always"
        if ((isAlways && c.trustedAlways.contains(off.uniqueId)) || (!isAlways && c.trustedOnline.contains(off.uniqueId))) {
            msg.send(p, Component.text().append(Component.text("This player is added already with this type, nothing changed. ")).append(Component.text("To remove players, use the /chunk removeplayer command.", NamedTextColor.GRAY)).build())
            return
        }

        val isUpdate = if (isAlways) c.trustedOnline.remove(off.uniqueId) else c.trustedAlways.remove(off.uniqueId)
        if (isUpdate) db.removeTrust(c.id!!, off.uniqueId)

        if (isAlways) { c.trustedAlways.add(off.uniqueId); db.addTrust(c.id!!, off.uniqueId, "ALWAYS") }
        else { c.trustedOnline.add(off.uniqueId); db.addTrust(c.id!!, off.uniqueId, "WHILE_ONLINE") }

        val verb = if (isUpdate) "Updated " else "Added "
        val desc = if (isAlways) "They aren't affected by claim rules anymore, even when you're not online." else "While you're online, they aren't affected by claim rules anymore."
        msg.send(p, Component.text().append(Component.text(verb)).append(msg.head(n)).append(Component.space()).append(Component.text(n, NamedTextColor.WHITE, TextDecoration.BOLD)).append(Component.text(" in your claim. ")).append(Component.text(desc, NamedTextColor.GRAY)).build())

        finishActionForClaim(c)
        off.player?.let { visualService.updateValues(it) }
    }

    /**
     * Remove added players from claims
     */
    fun removeTrust(p: Player, n: String) {
        if (!verifyPermissions(p)) { msg.sendNoAccess(p); return }
        val c = registry.getAt(p.location.chunk) ?: return
        val id = Bukkit.getOfflinePlayers().find { it.name?.equals(n, true) == true }?.uniqueId ?: return
        if (c.trustedAlways.remove(id) || c.trustedOnline.remove(id)) {
            db.removeTrust(c.id!!, id)
            msg.send(p, Component.text().append(Component.text("Removed ")).append(msg.head(n)).append(Component.space()).append(Component.text(n, NamedTextColor.WHITE, TextDecoration.BOLD)).append(Component.text(" from your claim.")).build())
            finishActionForClaim(c)
            Bukkit.getPlayer(id)?.let { visualService.updateValues(it) }
        }
    }

    /**
     * Merges two claims and creates aliases
     */
    private fun executeMerge(p: Player, m: PendingMerge) {
        val main = m.claims.first(); val pos = ChunkPos(m.worldUuid, m.chunkKey)
        db.addChunk(main.id!!, pos); main.chunks.add(pos)
        migrationService.processChunk(p.location.chunk)

        m.claims.drop(1).forEach { d ->
            val wid = d.chunks.first().worldUuid; val id = d.id!!
            db.moveChunks(id, main.id!!)
            main.legacyIds.add(id); main.legacyIds.addAll(d.legacyIds)
            db.addAlias(main.id!!, id); db.moveAliases(id, main.id!!)

            db.deleteClaim(id)
            main.chunks.addAll(d.chunks); main.trustedAlways.addAll(d.trustedAlways); main.trustedOnline.addAll(d.trustedOnline)
            registry.remove(d); removeClaimMarkers(id, wid)
        }
        updateClaimMarkers(main)
        msg.send(p, Component.text("Successfully merged your claims.")); finishAction(p, main.owner)
        finishActionForClaim(main)
    }

    private fun handleClaimRemoval(c: Claim) {
        val wid = c.chunks.first().worldUuid; val id = c.id!!
        db.deleteClaim(id); registry.remove(c)
        c.chunks.forEach { db.removeFromQueue(it.worldUuid, it.chunkKey) }
        removeClaimMarkers(id, wid)
        refreshPlayersAfterRemoval(c)
    }

    private fun handleChunkUnclaim(c: Claim, pos: ChunkPos) {
        c.chunks.remove(pos); db.removeChunk(c.id!!, pos)
        db.removeFromQueue(pos.worldUuid, pos.chunkKey)
        updateClaimMarkers(c)
    }

    private fun updateClaimMarkers(claim: Claim) {
        mapServices.forEach { it.updateClaim(claim) }
    }

    private fun removeClaimMarkers(id: Int, worldId: UUID) {
        mapServices.forEach { it.removeClaim(id, worldId) }
    }

    private fun refreshPlayersAfterRemoval(c: Claim) {
        Bukkit.getOnlinePlayers().forEach { online ->
            if (c.chunks.contains(ChunkPos(online.world.uid, online.location.chunk.chunkKey))) {
                playerListener.updateCache(online); visualService.updateValues(online)
                msg.unclaimedBar(online)
            }
        }
    }

    private fun finishAction(p: Player, ownerId: UUID?) {
        val chunk = p.location.chunk
        Bukkit.getOnlinePlayers().forEach { online ->
            if (online.location.chunk == chunk) {
                playerListener.updateCache(online); visualService.updateValues(online)
                if (ownerId != null) msg.actionBar(online, Bukkit.getOfflinePlayer(ownerId).name ?: "Unknown") else msg.unclaimedBar(online)
            }
        }
    }

    private fun finishActionForClaim(c: Claim) {
        Bukkit.getOnlinePlayers().forEach { online ->
            if (registry.getAt(online.location.chunk)?.id == c.id) {
                playerListener.updateCache(online); visualService.updateValues(online)
                msg.actionBar(online, Bukkit.getOfflinePlayer(c.owner).name ?: "Unknown")
            }
        }
    }

    private fun isConnected(c: Claim, r: ChunkPos): Boolean {
        val rem = c.chunks.filter { it != r }; if (rem.isEmpty()) return true
        val start = rem.first(); val vis = mutableSetOf<ChunkPos>(); val q: Queue<ChunkPos> = LinkedList(); q.add(start); vis.add(start)
        while (q.isNotEmpty()) {
            val curr = q.poll(); val x = curr.chunkKey.toInt(); val z = (curr.chunkKey shr 32).toInt()
            listOf(Chunk.getChunkKey(x+1,z), Chunk.getChunkKey(x-1,z), Chunk.getChunkKey(x,z+1), Chunk.getChunkKey(x,z-1)).forEach { k ->
                val p = ChunkPos(curr.worldUuid, k); if (rem.contains(p) && vis.add(p)) q.add(p)
            }
        }
        return vis.size == rem.size
    }

    private fun findAdj(p: Player, w: UUID, x: Int, z: Int) = registry.getAll().filter { c -> c.owner == p.uniqueId && c.chunks.any { cp -> cp.worldUuid == w && listOf(Chunk.getChunkKey(x+1,z), Chunk.getChunkKey(x-1,z), Chunk.getChunkKey(x,z+1), Chunk.getChunkKey(x,z-1)).contains(cp.chunkKey) } }
}
