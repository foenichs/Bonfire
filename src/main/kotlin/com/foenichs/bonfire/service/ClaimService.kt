package com.foenichs.bonfire.service

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
    private val playerListener: PlayerListener
) {
    data class PendingMerge(val worldUuid: UUID, val chunkKey: Long, val claims: List<Claim>, val time: Long)
    private val pending = mutableMapOf<UUID, PendingMerge>()

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
                    val id = db.createClaim(p.uniqueId); val pos = ChunkPos(w, k); val claim = Claim(id, p.uniqueId, mutableSetOf(pos))
                    registry.add(claim); db.addChunk(id, pos)
                    msg.send(p, Component.text("Successfully claimed this chunk and created a new claim."))
                    finishAction(p, claim.owner)
                }
            }
        }
    }

    fun tryUnclaim(p: Player) {
        val ch = p.location.chunk; val pos = ChunkPos(ch.world.uid, ch.chunkKey); val c = registry.getAt(pos.worldUuid, pos.chunkKey) ?: return
        if (c.chunks.size <= 1) {
            db.deleteClaim(c.id!!); registry.remove(c)
            msg.send(p, Component.text("Successfully unclaimed this chunk and deleted the claim."))
            msg.unclaimedBar(p); finishAction(p, null)
        } else if (isConnected(c, pos)) {
            c.chunks.remove(pos); db.removeChunk(c.id!!, pos)
            msg.send(p, Component.text("Successfully unclaimed this chunk and removed it from your claim."))
            msg.unclaimedBar(p); finishAction(p, null)
        } else {
            msg.send(p, Component.text().append(Component.text("You can't unclaim this chunk. ")).append(Component.text("Unclaiming it would split up your claim, please unclaim outer chunks first.", NamedTextColor.GRAY)).build())
        }
    }

    /**
     * Update cache, commands and visuals
     */
    private fun finishAction(p: Player, ownerId: UUID?) {
        playerListener.updateCache(p)
        p.updateCommands()
        if (ownerId != null) {
            msg.actionBar(p, Bukkit.getOfflinePlayer(ownerId).name ?: "Unknown")
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

    fun setRule(p: Player, r: String, v: String) {
        val c = registry.getAt(p.location.chunk) ?: return
        when(r) { "allowBlockBreak" -> c.allowBlockBreak = v.toBoolean(); "allowBlockInteract" -> c.allowBlockInteract = v.toBoolean(); "allowEntityInteract" -> c.allowEntityInteract = v }
        db.updateRules(c)
        val desc = when (r) {
            "allowBlockBreak" -> if (v == "true") "Blocks on your claim can now be placed and destroyed by players, pistons, water, etc." else "Blocks on your claim can no longer be placed and destroyed by players, pistons, water, etc."
            "allowBlockInteract" -> if (v == "true") "Players can now interact with blocks on your claim." else "Players can no longer interact with blocks on your claim."
            "allowEntityInteract" -> when (v) { "true" -> "Players can now interact and collide with entites and get targetted by them."; "false" -> "Players can no longer interact and collide with entites or get targetted by them."; "onlyMounts" -> "Players can mount entites, but can't interact and collide with or get targetted by them."; else -> "" }
            else -> ""
        }
        msg.send(p, Component.text().append(Component.text("Set $r to $v. ")).append(Component.text(desc, NamedTextColor.GRAY)).build())
        p.updateCommands()
        Bukkit.getOnlinePlayers().filter { registry.getAt(it.location.chunk)?.id == c.id }.forEach { visualService.updateValues(it) }
    }

    fun addTrust(p: Player, n: String, t: String) {
        val c = registry.getAt(p.location.chunk) ?: return
        val off = Bukkit.getOfflinePlayers().find { it.name?.equals(n, true) == true }
        if (off == null || (!off.hasPlayedBefore() && !off.isOnline)) {
            msg.send(p, Component.text().append(Component.text("This player wasn't found. ")).append(Component.text("They have to join once before they can be added to claims.", NamedTextColor.GRAY)).build())
            return
        }
        if (c.trustedAlways.contains(off.uniqueId) || c.trustedOnline.contains(off.uniqueId)) {
            msg.send(p, Component.text().append(Component.text("This player was added already, nothing changed. ")).append(Component.text("To remove players, use the /chunk removeplayer command.", NamedTextColor.GRAY)).build())
            return
        }
        if (t == "always") { c.trustedAlways.add(off.uniqueId); db.addTrust(c.id!!, off.uniqueId, "always") }
        else { c.trustedOnline.add(off.uniqueId); db.addTrust(c.id!!, off.uniqueId, "whileOnline") }
        val desc = if (t == "always") "They aren't affected by claim rules anymore, even when you're not online." else "While you're online, they aren't affected by claim rules anymore."
        msg.send(p, Component.text().append(Component.text("Added ")).append(msg.head(n)).append(Component.space()).append(Component.text(n, NamedTextColor.WHITE, TextDecoration.BOLD)).append(Component.text(" to your claim. ")).append(Component.text(desc, NamedTextColor.GRAY)).build())
        p.updateCommands()
        off.player?.let { if (registry.getAt(it.location.chunk)?.id == c.id) { visualService.updateValues(it); it.updateCommands() } }
    }

    fun removeTrust(p: Player, n: String) {
        val c = registry.getAt(p.location.chunk) ?: return
        val id = Bukkit.getOfflinePlayers().find { it.name?.equals(n, true) == true }?.uniqueId ?: return
        if (c.trustedAlways.remove(id) || c.trustedOnline.remove(id)) {
            db.removeTrust(c.id!!, id)
            msg.send(p, Component.text().append(Component.text("Removed ")).append(msg.head(n)).append(Component.space()).append(Component.text(n, NamedTextColor.WHITE, TextDecoration.BOLD)).append(Component.text(" from your claim.")).build())
            p.updateCommands()
            Bukkit.getPlayer(id)?.let { if (registry.getAt(it.location.chunk)?.id == c.id) { visualService.updateValues(it); it.updateCommands() } }
        }
    }

    private fun executeMerge(p: Player, m: PendingMerge) {
        val main = m.claims.first(); val pos = ChunkPos(m.worldUuid, m.chunkKey)
        db.addChunk(main.id!!, pos); main.chunks.add(pos)
        m.claims.drop(1).forEach { d -> db.moveChunks(d.id!!, main.id!!); db.deleteClaim(d.id!!); main.chunks.addAll(d.chunks); main.trustedAlways.addAll(d.trustedAlways); main.trustedOnline.addAll(d.trustedOnline); registry.remove(d) }
        msg.send(p, Component.text("Successfully merged your claims.")); finishAction(p, main.owner)
        Bukkit.getOnlinePlayers().filter { registry.getAt(it.location.chunk)?.id == main.id }.forEach { visualService.updateValues(it) }
    }

    private fun findAdj(p: Player, w: UUID, x: Int, z: Int) = registry.getAll().filter { c -> c.owner == p.uniqueId && c.chunks.any { cp -> cp.worldUuid == w && listOf(Chunk.getChunkKey(x+1,z), Chunk.getChunkKey(x-1,z), Chunk.getChunkKey(x,z+1), Chunk.getChunkKey(x,z-1)).contains(cp.chunkKey) } }
}