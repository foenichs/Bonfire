package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import xyz.jpenilla.squaremap.api.BukkitAdapter
import xyz.jpenilla.squaremap.api.Key
import xyz.jpenilla.squaremap.api.MapWorld
import xyz.jpenilla.squaremap.api.Point
import xyz.jpenilla.squaremap.api.SimpleLayerProvider
import xyz.jpenilla.squaremap.api.SquaremapProvider
import xyz.jpenilla.squaremap.api.marker.Marker
import xyz.jpenilla.squaremap.api.marker.MarkerOptions
import java.awt.Color
import java.util.UUID

class SquaremapService(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry
) : ClaimMapService {
    private val layerKey = Key.of("bonfire_claims")
    private val providers = mutableMapOf<UUID, Pair<MapWorld, SimpleLayerProvider>>()

    override fun refreshAll() {
        clearLayers()
        if (!plugin.config.getBoolean("squaremap.enable-markers", true)) return

        val claimsByWorld = registry.getAll().groupBy { it.chunks.firstOrNull()?.worldUuid }

        SquaremapProvider.get().mapWorlds().forEach { mapWorld ->
            val worldId = BukkitAdapter.bukkitWorld(mapWorld).uid
            val provider = createProvider(mapWorld, worldId)

            claimsByWorld[worldId].orEmpty().forEach { claim ->
                createMarker(provider, claim)
            }
        }
    }

    override fun updateClaim(claim: Claim) {
        if (!plugin.config.getBoolean("squaremap.enable-markers", true)) return
        if (claim.chunks.isEmpty()) return

        val worldId = claim.chunks.first().worldUuid
        val provider = getOrCreateProvider(worldId) ?: return
        createMarker(provider, claim)
    }

    override fun removeClaim(id: Int, worldId: UUID) {
        providers[worldId]?.second?.removeMarker(markerKey(id))
    }

    override fun shutdown() {
        clearLayers()
    }

    private fun createMarker(provider: SimpleLayerProvider, claim: Claim) {
        val id = claim.id ?: return
        val polygon = ClaimPolygonTracer.trace(claim) ?: return
        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"
        val labelTemplate = plugin.config.getString("squaremap.label", "Claimed by \$name")!!
        val label = labelTemplate.replace("\$name", ownerName)

        val outer = polygon.outer.map { Point.of(it.x, it.z) }
        val holes = polygon.holes.map { hole -> hole.map { Point.of(it.x, it.z) } }
        val marker = Marker.polygon(outer, holes)

        marker.markerOptions(
            MarkerOptions.builder()
                .strokeColor(readColor("squaremap.stroke-color", Color(255, 221, 161)))
                .strokeWeight(plugin.config.getInt("squaremap.stroke-weight", 2).coerceAtLeast(1))
                .strokeOpacity(plugin.config.getDouble("squaremap.stroke-opacity", 0.8).coerceIn(0.0, 1.0))
                .fillColor(readColor("squaremap.fill-color", Color(255, 221, 161)))
                .fillOpacity(plugin.config.getDouble("squaremap.fill-opacity", 0.15).coerceIn(0.0, 1.0))
                .clickTooltip(label)
        )

        provider.addMarker(markerKey(id), marker)
    }

    private fun getOrCreateProvider(worldId: UUID): SimpleLayerProvider? {
        providers[worldId]?.let { return it.second }

        val mapWorld = SquaremapProvider.get().mapWorlds().firstOrNull {
            BukkitAdapter.bukkitWorld(it).uid == worldId
        } ?: return null

        return createProvider(mapWorld, worldId)
    }

    private fun createProvider(mapWorld: MapWorld, worldId: UUID): SimpleLayerProvider {
        if (mapWorld.layerRegistry().hasEntry(layerKey)) {
            mapWorld.layerRegistry().unregister(layerKey)
        }

        val provider = SimpleLayerProvider.builder(
            plugin.config.getString("squaremap.layer-label", "Bonfire Claims")!!
        )
            .showControls(true)
            .defaultHidden(plugin.config.getBoolean("squaremap.default-hidden", false))
            .layerPriority(plugin.config.getInt("squaremap.layer-priority", 50))
            .zIndex(plugin.config.getInt("squaremap.z-index", 50))
            .build()

        mapWorld.layerRegistry().register(layerKey, provider)
        providers[worldId] = mapWorld to provider
        return provider
    }

    private fun clearLayers() {
        providers.values.forEach { (mapWorld, provider) ->
            provider.clearMarkers()
            if (mapWorld.layerRegistry().hasEntry(layerKey)) {
                mapWorld.layerRegistry().unregister(layerKey)
            }
        }
        providers.clear()
    }

    private fun markerKey(id: Int): Key = Key.of("claim_$id")

    private fun readColor(path: String, fallback: Color): Color {
        val rgb = plugin.config.getString(path)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 3 }
            ?: return fallback

        return Color(
            rgb[0].coerceIn(0, 255),
            rgb[1].coerceIn(0, 255),
            rgb[2].coerceIn(0, 255)
        )
    }
}
