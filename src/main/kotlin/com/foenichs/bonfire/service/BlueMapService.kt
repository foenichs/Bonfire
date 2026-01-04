package com.foenichs.bonfire.service

import com.flowpowered.math.vector.Vector2d
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.ExtrudeMarker
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.math.Color
import de.bluecolored.bluemap.api.math.Shape
import org.bukkit.Bukkit
import java.util.*
import kotlin.math.abs

class BlueMapService(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry
) {

    private var api: BlueMapAPI? = null
    private val markerSetId = "bonfire_claims"
    private val markerSetLabel = "Bonfire Claims"

    // Pastel Gold
    private val colorLine = Color(255, 221, 161, 0.4f)
    private val colorFill = Color(255, 231, 161, 0.1f)

    init {
        BlueMapAPI.onEnable { bluemap ->
            this.api = bluemap
            // Run synchronously to safely access the ClaimRegistry
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { refreshAll() }, 40L)
        }

        BlueMapAPI.onDisable {
            this.api = null
        }
    }

    /**
     * Refreshes all claims on the map
     */
    fun refreshAll() {
        val blueMap = api ?: return

        // Group claims by world
        val claimsByWorld = registry.getAll().groupBy { it.chunks.firstOrNull()?.worldUuid }

        // Process each world
        claimsByWorld.forEach { (worldId, claims) ->
            if (worldId == null) return@forEach

            blueMap.getWorld(worldId).ifPresent { world ->
                val markerSet = world.maps.firstOrNull()?.markerSets?.getOrPut(markerSetId) {
                    MarkerSet.builder().label(markerSetLabel).build()
                } ?: return@ifPresent

                markerSet.markers.clear()

                claims.forEach { claim ->
                    try {
                        createMarker(markerSet, claim)
                    } catch (_: ArrayStoreException) {
                        // Use the plugin's logger instead of Bukkit.getLogger()
                        plugin.logger.severe("[Bonfire] Dependency Error: flow-math library is conflicting.")
                        return@forEach
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    /**
     * Updates a single claim
     */
    fun updateClaim(claim: Claim) {
        val blueMap = api ?: return
        val worldId = claim.chunks.firstOrNull()?.worldUuid ?: return

        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                val markerSet = map.markerSets[markerSetId] ?: return@forEach
                val markerId = "claim_${claim.id}"

                if (claim.chunks.isEmpty()) {
                    markerSet.remove(markerId)
                } else {
                    try {
                        createMarker(markerSet, claim)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    /**
     * Removes a claim marker explicitly
     */
    fun removeClaim(id: Int, worldId: UUID) {
        val blueMap = api ?: return
        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                map.markerSets[markerSetId]?.remove("claim_$id")
            }
        }
    }

    private fun createMarker(markerSet: MarkerSet, claim: Claim) {
        val polygonData = tracePolygon(claim) ?: return
        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"
        val claimName = "Claimed by $ownerName"

        // Convert the List<Shape> to typed Array<Shape> for vararg method
        val holesArray = polygonData.holes.toTypedArray()

        val marker =
            ExtrudeMarker.builder().label(claimName).shape(polygonData.outerShape, 64f, 320f).lineColor(colorLine)
                .fillColor(colorFill).depthTestEnabled(true).lineWidth(2).listed(false).minDistance(10.0)
                .maxDistance(1000.0).holes(*holesArray).build()

        markerSet.put("claim_${claim.id}", marker)
    }

    private data class PolygonData(val outerShape: Shape, val holes: List<Shape>)

    /**
     * Algorithm to trace the outer edge and holes of a collection of chunks
     */
    private fun tracePolygon(claim: Claim): PolygonData? {
        if (claim.chunks.isEmpty()) return null

        data class Segment(val x1: Double, val z1: Double, val x2: Double, val z2: Double)

        val segments = HashSet<Segment>()
        // Map keys to X/Z pairs for faster lookup
        val chunkSet = claim.chunks.map { (it.chunkKey.toInt() to (it.chunkKey shr 32).toInt()) }.toSet()

        chunkSet.forEach { (cx, cz) ->
            val minX = cx * 16.0
            val maxX = minX + 16.0
            val minZ = cz * 16.0
            val maxZ = minZ + 16.0

            // Only add edges that border a chunk not in the claim
            if (!chunkSet.contains(cx to cz - 1)) segments.add(Segment(minX, minZ, maxX, minZ)) // Top (North)
            if (!chunkSet.contains(cx to cz + 1)) segments.add(Segment(maxX, maxZ, minX, maxZ)) // Bottom (South)
            if (!chunkSet.contains(cx - 1 to cz)) segments.add(Segment(minX, maxZ, minX, minZ)) // Left (West)
            if (!chunkSet.contains(cx + 1 to cz)) segments.add(Segment(maxX, minZ, maxX, maxZ)) // Right (East)
        }

        if (segments.isEmpty()) return null

        val loops = ArrayList<ArrayList<Vector2d>>()

        // Extract all closed loops from the segments
        while (segments.isNotEmpty()) {
            val points = ArrayList<Vector2d>()
            val startSegment = segments.first()
            var currentSegment = startSegment

            points.add(Vector2d(currentSegment.x1, currentSegment.z1))
            segments.remove(currentSegment)

            while (true) {
                val nextPoint = Vector2d(currentSegment.x2, currentSegment.z2)

                // Closure check
                if (nextPoint == points[0]) break

                points.add(nextPoint)

                // Find connecting segment (using epsilon for floating point safety)
                val next =
                    segments.find { abs(it.x1 - currentSegment.x2) < 0.01 && abs(it.z1 - currentSegment.z2) < 0.01 }

                if (next != null) {
                    currentSegment = next
                    segments.remove(next)
                } else {
                    // Loop is incomplete or broken (should not happen in grid logic)
                    break
                }
            }
            loops.add(optimizePoints(points))
        }

        if (loops.isEmpty()) return null

        // Identify the outer shell as the loop with the minimum X coordinate (left-most edge)
        // Any other loops found inside the outer shell are treated as holes
        val outerLoop = loops.minByOrNull { loop -> loop.minOf { it.x } } ?: return null
        val holes = loops.filter { it !== outerLoop }.map { Shape(it) }

        return PolygonData(Shape(outerLoop), holes)
    }

    /**
     * Removes collinear points to reduce shape complexity
     */
    private fun optimizePoints(input: ArrayList<Vector2d>): ArrayList<Vector2d> {
        if (input.size < 3) return input

        val result = ArrayList<Vector2d>()

        for (i in input.indices) {
            val prev = input[(i - 1 + input.size) % input.size]
            val curr = input[i]
            val next = input[(i + 1) % input.size]

            // Check if X coordinates align (Vertical line)
            val isVertical = abs(prev.x - curr.x) < 0.01 && abs(curr.x - next.x) < 0.01
            // Check if Z coordinates align (Horizontal line)
            val isHorizontal = abs(prev.y - curr.y) < 0.01 && abs(curr.y - next.y) < 0.01

            // Keep the point only if it forms a corner
            if (!isVertical && !isHorizontal) {
                result.add(curr)
            }
        }
        return result
    }
}