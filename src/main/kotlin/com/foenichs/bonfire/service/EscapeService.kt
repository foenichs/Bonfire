package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkLayer
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Dialogs
import com.foenichs.bonfire.ui.Messenger
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.*

class EscapeService(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val msg: Messenger,
    private val plugin: Bonfire
) {
    private val cooldowns = mutableMapOf<UUID, Long>()

    private data class Bounds(val minCx: Int, val maxCx: Int, val minCz: Int, val maxCz: Int)

    companion object {
        private const val HOLE_MARGIN = 2
        private const val MAX_BLOCK_RADIUS = 1024
        private const val MAX_VERTICAL_RANGE = 48
    }

    fun isEnabled(): Boolean = plugin.config.getBoolean("escaping.enabled", true)

    /**
     * Whether the player can't break blocks or interact with entities
     */
    fun isRestricted(p: Player, location: Location = p.location): Boolean {
        if (!isEnabled()) return false
        val claim = registry.getAt(location) ?: return false
        if (protection.canBypass(p, location)) return false
        return !claim.allowBlockBreak || claim.allowEntityInteract != "true"
    }

    /**
     * Shows the escape confirmation or cooldown dialog
     */
    fun promptEscape(p: Player) {
        if (!isRestricted(p)) return
        val remaining = remainingCooldown(p)
        if (remaining > 0) { Dialogs.escapeOnCooldown(p, formatDuration(remaining)); return }
        Dialogs.escapeConfirm(p, formatDuration(cooldownSeconds())) { performEscape(p) }
    }

    /**
     * Re-validates and teleports the player to the nearest unclaimed surface
     */
    private fun performEscape(p: Player) {
        if (!p.isOnline || !isRestricted(p) || remainingCooldown(p) > 0) return

        val target = findEscapeLocation(p) ?: run { Dialogs.escapeFailed(p); return }
        target.yaw = p.location.yaw; target.pitch = p.location.pitch

        cooldowns[p.uniqueId] = System.currentTimeMillis()
        p.teleportAsync(target)
        msg.send(p, Component.text("You successfully escaped the claim. You can escape again in ${formatDuration(cooldownSeconds())}."))
    }

    private fun cooldownSeconds(): Long = plugin.config.getLong("escaping.cooldown", 300L)

    /**
     * Ensures that config reloads take effect immediately
     */
    private fun remainingCooldown(p: Player): Long {
        val lastUse = cooldowns[p.uniqueId] ?: return 0
        val remainingMs = cooldownSeconds() * 1000 - (System.currentTimeMillis() - lastUse)
        return if (remainingMs > 0) (remainingMs + 999) / 1000 else 0
    }

    /**
     * Finds the nearest safe unclaimed surface, widening the search if nothing nearby is found
     */
    private fun findEscapeLocation(player: Player): Location? {
        val loc = player.location; val world = loc.world ?: return null
        val layer = ChunkPos.layerFor(loc); val claim = registry.getAt(loc) ?: return null

        val claimChunks = claim.chunks.filter { it.worldUuid == world.uid && it.layer == layer }
        if (claimChunks.isEmpty()) return null

        val bounds = Bounds(
            claimChunks.minOf { it.chunkKey.toInt() } - HOLE_MARGIN,
            claimChunks.maxOf { it.chunkKey.toInt() } + HOLE_MARGIN,
            claimChunks.minOf { (it.chunkKey shr 32).toInt() } - HOLE_MARGIN,
            claimChunks.maxOf { (it.chunkKey shr 32).toInt() } + HOLE_MARGIN
        )
        val cache = HashMap<Long, Boolean>()

        return search(world, loc, layer, bounds, cache, MAX_VERTICAL_RANGE) ?: search(world, loc, layer, bounds, cache, null)
    }

    /**
     * Ring search outward from the player for the nearest eligible surface
     */
    private fun search(world: World, loc: Location, layer: ChunkLayer, bounds: Bounds, cache: HashMap<Long, Boolean>, verticalRange: Int?): Location? {
        var best: Location? = null
        var bestDistSq = Double.MAX_VALUE

        for (radius in 0..MAX_BLOCK_RADIUS) {
            if (radius.toDouble() * radius >= bestDistSq) break

            for ((x, z) in ring(loc.blockX, loc.blockZ, radius)) {
                val cx = x shr 4; val cz = z shr 4
                val key = Chunk.getChunkKey(cx, cz)
                val eligible = cache.getOrPut(key) {
                    world.isChunkGenerated(cx, cz) && registry.getAt(world.uid, key, layer) == null && !isEnclosed(world.uid, cx, cz, layer, bounds)
                }
                if (!eligible) continue

                val y = surfaceY(world, x, z, loc.blockY, layer, verticalRange) ?: continue
                val dx = (x - loc.blockX).toDouble(); val dy = y - loc.blockY; val dz = (z - loc.blockZ).toDouble()
                val distSq = dx * dx + dy * dy + dz * dz
                if (distSq < bestDistSq) { bestDistSq = distSq; best = Location(world, x + 0.5, y, z + 0.5) }
            }
        }
        return best
    }

    /**
     * Flood-fills unclaimed chunks, returns true if they never reach past the given bounds
     */
    private fun isEnclosed(worldId: UUID, startCx: Int, startCz: Int, layer: ChunkLayer, bounds: Bounds): Boolean {
        val visited = HashSet<Long>(); val queue: Queue<IntArray> = LinkedList()
        queue.add(intArrayOf(startCx, startCz)); visited.add(Chunk.getChunkKey(startCx, startCz))

        while (queue.isNotEmpty()) {
            val (x, z) = queue.poll()
            if (x < bounds.minCx || x > bounds.maxCx || z < bounds.minCz || z > bounds.maxCz) return false

            val neighbors = arrayOf(intArrayOf(x + 1, z), intArrayOf(x - 1, z), intArrayOf(x, z + 1), intArrayOf(x, z - 1))
            for (n in neighbors) {
                val key = Chunk.getChunkKey(n[0], n[1])
                if (visited.add(key) && registry.getAt(worldId, key, layer) == null) queue.add(n)
            }
        }
        return true
    }

    /**
     * Resolves the standable surface Y; searches locally or across the full layer
     */
    private fun surfaceY(world: World, x: Int, z: Int, refY: Int, layer: ChunkLayer, verticalRange: Int?): Double? {
        if (world.environment != World.Environment.NETHER) return highestSurfaceY(world, x, z)

        val floor = if (layer == ChunkLayer.GROUND) world.minHeight + 1 else ChunkPos.NETHER_ROOF_Y.toInt()
        val ceiling = if (layer == ChunkLayer.GROUND) ChunkPos.NETHER_ROOF_Y.toInt() - 1 else world.maxHeight - 1
        val minY = if (verticalRange != null) maxOf(floor, refY - verticalRange) else floor
        val maxY = if (verticalRange != null) minOf(ceiling, refY + verticalRange) else ceiling
        return nearestStandableY(world, x, z, refY, minY, maxY)
    }

    private fun highestSurfaceY(world: World, x: Int, z: Int): Double? {
        var y = world.maxHeight - 1
        while (y > world.minHeight) {
            val block = world.getBlockAt(x, y, z)
            if (block.type == Material.LAVA) return null
            standingTop(block)?.let { return it }
            y--
        }
        return null
    }

    /**
     * Searches outward from refY for the closest standable spot within bounds
     */
    private fun nearestStandableY(world: World, x: Int, z: Int, refY: Int, minY: Int, maxY: Int): Double? {
        if (minY > maxY) return null
        val start = refY.coerceIn(minY, maxY)
        standableTop(world, x, start, z)?.let { return it }

        var offset = 1
        while (start - offset >= minY || start + offset <= maxY) {
            val down = start - offset
            if (down >= minY) standableTop(world, x, down, z)?.let { return it }

            val up = start + offset
            if (up <= maxY) standableTop(world, x, up, z)?.let { return it }

            offset++
        }
        return null
    }

    /**
     * Solid floor at y with two clear blocks above
     */
    private fun standableTop(world: World, x: Int, y: Int, z: Int): Double? {
        if (y + 2 >= world.maxHeight) return null
        val top = standingTop(world.getBlockAt(x, y, z)) ?: return null
        if (!isClear(world.getBlockAt(x, y + 1, z)) || !isClear(world.getBlockAt(x, y + 2, z))) return null
        return top
    }

    /**
     * Exact collision top of a block, null if lava or non-solid
     */
    private fun standingTop(block: Block): Double? {
        if (block.type == Material.LAVA) return null
        val localTop = block.collisionShape.boundingBoxes.maxOfOrNull { it.maxY } ?: return null
        return block.y + localTop
    }

    /**
     * Checks if a block has no collision and is not lava
     */
    private fun isClear(block: Block) = block.type != Material.LAVA && block.collisionShape.boundingBoxes.isEmpty()

    /**
     * The border points of a square at the given radius around (cx, cz), nearest first
     */
    private fun ring(cx: Int, cz: Int, r: Int): List<IntArray> {
        if (r == 0) return listOf(intArrayOf(cx, cz))
        val points = mutableListOf<IntArray>()
        for (dx in -r..r) { points.add(intArrayOf(cx + dx, cz - r)); points.add(intArrayOf(cx + dx, cz + r)) }
        for (dz in -r + 1..<r) { points.add(intArrayOf(cx - r, cz + dz)); points.add(intArrayOf(cx + r, cz + dz)) }
        return points.sortedBy { p -> val dx = p[0] - cx; val dz = p[1] - cz; dx * dx + dz * dz }
    }

    /**
     * Formats a duration in seconds as a readable string, e.g. "1 hour and 2 minutes"
     */
    private fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600; val minutes = (s % 3600) / 60; val seconds = s % 60

        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours hour" + if (hours != 1L) "s" else "")
        if (minutes > 0) parts.add("$minutes minute" + if (minutes != 1L) "s" else "")
        if (seconds > 0 || parts.isEmpty()) parts.add("$seconds second" + if (seconds != 1L) "s" else "")

        return when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> "${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}"
        }
    }
}