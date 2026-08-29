package com.foenichs.bonfire.service.map

import com.flowpowered.math.vector.Vector2d
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkLayer
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.ExtrudeMarker
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.math.Color
import de.bluecolored.bluemap.api.math.Shape
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.*

class BlueMapService(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry
) {

    private var api: BlueMapAPI? = null
    private val markerSetId = "bonfire_claims"
    private val markerSetLabel = "Bonfire Claims"

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
                world.maps.forEach { map ->
                    // Config Check
                    if (!plugin.config.getBoolean("bluemap.enable-markers", true)) {
                        map.markerSets.remove(markerSetId)
                        return@forEach
                    }

                    // If enabled, get or create the set
                    val markerSet = map.markerSets.getOrPut(markerSetId) {
                        MarkerSet.builder().label(markerSetLabel).build()
                    }

                    markerSet.markers.clear()

                    claims.forEach { claim ->
                        try {
                            createMarkers(markerSet, claim, worldId)
                        } catch (_: ArrayStoreException) {
                            plugin.logger.severe("[Bonfire] Dependency Error: flow-math library is conflicting.")
                            return@forEach
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
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

        // Config Check
        if (!plugin.config.getBoolean("bluemap.enable-markers", true)) return

        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                val markerSet = map.markerSets[markerSetId] ?: return@forEach
                try {
                    createMarkers(markerSet, claim, worldId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Removes a claim's markers explicitly
     */
    fun removeClaim(id: Int, worldId: UUID) {
        val blueMap = api ?: return
        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                markerIds(id).forEach { map.markerSets[markerSetId]?.remove(it) }
            }
        }
    }

    private fun markerIds(id: Int) = listOf("claim_$id", "claim_${id}_ground", "claim_${id}_roof")

    /**
     * Split ground/roof by height where they don't align, otherwise one full-height marker
     */
    private fun createMarkers(markerSet: MarkerSet, claim: Claim, worldId: UUID) {
        val id = claim.id ?: return
        markerIds(id).forEach { markerSet.remove(it) }

        val world = Bukkit.getWorld(worldId)
        val minHeight = (world?.minHeight ?: -64).toFloat()
        val maxHeight = (world?.maxHeight ?: 320).toFloat()

        if (world?.environment != World.Environment.NETHER) {
            tracePolygon(claim.chunks)?.let { markerSet.put("claim_$id", buildMarker(it, claim, minHeight, maxHeight)) }
            return
        }

        val roofY = ChunkPos.NETHER_ROOF_Y.toFloat()
        val groundKeys = claim.chunks.filter { it.layer == ChunkLayer.GROUND }.map { it.chunkKey }.toSet()
        val roofKeys = claim.chunks.filter { it.layer == ChunkLayer.ROOF }.map { it.chunkKey }.toSet()

        val bothChunks = claim.chunks.filter { it.layer == ChunkLayer.GROUND && it.chunkKey in roofKeys }
        val groundOnlyChunks = claim.chunks.filter { it.layer == ChunkLayer.GROUND && it.chunkKey !in roofKeys }
        val roofOnlyChunks = claim.chunks.filter { it.layer == ChunkLayer.ROOF && it.chunkKey !in groundKeys }

        tracePolygon(bothChunks)?.let { markerSet.put("claim_$id", buildMarker(it, claim, minHeight, maxHeight)) }
        tracePolygon(groundOnlyChunks)?.let { markerSet.put("claim_${id}_ground", buildMarker(it, claim, minHeight, roofY)) }
        tracePolygon(roofOnlyChunks)?.let { markerSet.put("claim_${id}_roof", buildMarker(it, claim, roofY, maxHeight)) }
    }

    private fun buildMarker(polygonData: PolygonData, claim: Claim, minY: Float, maxY: Float): ExtrudeMarker {
        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"

        // Load configs
        val labelTemplate = plugin.config.getString("bluemap.label", $$"Claimed by $name")!!
        val label = labelTemplate.replace($$"$name", ownerName)
        val listed = plugin.config.getBoolean("bluemap.list-markers", false)
        val viewDist = plugin.config.getDouble("bluemap.view-distance", 1000.0)

        // Parse color from config
        val colorLine = getAccentColor(0.4f)
        val colorFill = getAccentColor(0.1f)

        // Convert the List<Shape> to typed Array<Shape> for vararg method
        val holesArray = polygonData.holes.toTypedArray()

        return ExtrudeMarker.builder()
            .label(label)
            .shape(polygonData.outerShape, minY, maxY)
            .lineColor(colorLine)
            .fillColor(colorFill)
            .depthTestEnabled(true)
            .lineWidth(2)
            .listed(listed)
            .minDistance(10.0)
            .maxDistance(viewDist)
            .holes(*holesArray)
            .build()
    }

    private fun getAccentColor(alpha: Float): Color {
        val rgb = plugin.config.getString("bluemap.accent-color", "255, 221, 161")!!.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (rgb.size == 3) Color(rgb[0], rgb[1], rgb[2], alpha) else Color(255, 221, 161, alpha)
    }

    private data class PolygonData(val outerShape: Shape, val holes: List<Shape>)

    /**
     * Trace a collection of chunks into a Shape via the shared tracer
     */
    private fun tracePolygon(chunks: Collection<ChunkPos>): PolygonData? {
        if (chunks.isEmpty()) return null
        val chunkSet = chunks.map { (it.chunkKey.toInt() to (it.chunkKey shr 32).toInt()) }.toSet()
        val polygon = ClaimPolygonTracer.traceChunks(chunkSet) ?: return null

        return PolygonData(
            toShape(polygon.outer),
            polygon.holes.map { toShape(it) }
        )
    }

    private fun toShape(points: List<ClaimMapPoint>) = Shape(points.map { Vector2d(it.x, it.z) })
}