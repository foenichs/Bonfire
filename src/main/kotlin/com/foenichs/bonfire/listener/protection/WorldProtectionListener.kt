package com.foenichs.bonfire.listener.protection

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent
import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Directional
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.vehicle.VehicleCreateEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.ItemStack

class WorldProtectionListener(
    plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    init {
        // Global task to handle falling blocks crossing claim borders
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            Bukkit.getWorlds().forEach { world ->
                world.getEntitiesByClass(FallingBlock::class.java).forEach { entity ->
                    if (!entity.isValid || entity.isOnGround) return@forEach

                    val location = entity.location
                    val claim = registry.getAt(location) ?: return@forEach

                    if (!claim.allowBlockBreak && !protection.isOrigin(entity, location)) {
                        entity.world.dropItemNaturally(entity.location, ItemStack(entity.blockData.material))
                        entity.remove()
                    }
                }
            }
        }, 1L, 1L)
    }

    /**
     * Prevent empty boats/minecarts from entering claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val vehicle = event.vehicle
        if (vehicle.passengers.isNotEmpty()) return

        val from = event.from
        val to = event.to
        if (from.chunk == to.chunk && ChunkPos.layerFor(from) == ChunkPos.layerFor(to)) return

        val claim = registry.getAt(to) ?: return
        if (claim.allowEntityInteract != "true" && !protection.isOrigin(vehicle, to)) {
            val material = when (vehicle) {
                is Boat -> vehicle.boatMaterial
                is Minecart -> Material.MINECART
                else -> return
            }
            vehicle.world.dropItemNaturally(vehicle.location, ItemStack(material))
            vehicle.remove()
        }
    }

    /**
     * Liquids flowing into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val fromLocation = event.block.location
        val toBlock = event.toBlock
        if (!protection.isWorldActionAllowed(fromLocation, toBlock.location) && !protection.checkAllowBlockBreak(toBlock.location)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire spreading into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFireSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE) return
        val fromLocation = event.source.location
        val toLocation = event.block.location
        if (!protection.isWorldActionAllowed(fromLocation, toLocation) && !protection.checkAllowBlockBreak(toLocation)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire from outside destroying blocks inside a claim
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val igniter = event.ignitingBlock ?: return
        val fromLocation = igniter.location
        val toLocation = event.block.location
        if (!protection.isWorldActionAllowed(fromLocation, toLocation) && !protection.checkAllowBlockBreak(toLocation)) {
            event.isCancelled = true
        }
    }

    /**
     * Trees and large structures growing into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val sourceLocation = event.location

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetLocation = state.location

            // Only block if moving from outside into a protected claim
            if (!protection.isWorldActionAllowed(
                    sourceLocation, targetLocation
                ) && !protection.checkAllowBlockBreak(targetLocation)
            ) {
                event.isCancelled = true
                return
            }
        }
    }

    /**
     * Bone Meal spreading grass/flowers into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val sourceLocation = event.block.location

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetLocation = state.location

            if (!protection.isWorldActionAllowed(
                    sourceLocation, targetLocation
                ) && !protection.checkAllowBlockBreak(targetLocation)
            ) {
                iterator.remove()
            }
        }
    }

    /**
     * Dispensers firing items, fluids, or projectiles across borders
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        val data = block.blockData

        if (data !is Directional) return

        val targetBlock = block.getRelative(data.facing)
        val fromLocation = block.location
        val toLocation = targetBlock.location

        if (!protection.isWorldActionAllowed(fromLocation, toLocation) && !protection.checkAllowBlockBreak(toLocation)) {
            event.isCancelled = true
        }
    }

    /**
     * Tags Snowman, ArmorStand and FallingBlock when they spawn inside a claim
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        if (entity !is Snowman && entity !is ArmorStand && entity !is FallingBlock) return

        val claim = registry.getAt(event.location)
        if (claim != null) {
            entity.addScoreboardTag("bonfire_origin_${claim.id}")
        }
    }

    /**
     * Specific handler for tagging boats and minecarts when they are created
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleCreate(event: VehicleCreateEvent) {
        val vehicle = event.vehicle
        if (vehicle !is Boat && vehicle !is Minecart) return

        val claim = registry.getAt(vehicle.location) ?: return
        vehicle.addScoreboardTag("bonfire_origin_${claim.id}")
    }

    /**
     * Entities forming blocks, e.g. using the frost walker enchantment
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        val entity = event.entity
        val location = event.block.location
        val claim = registry.getAt(location) ?: return

        if (!claim.allowBlockBreak) {
            when (entity) {
                is Player -> {
                    if (!protection.canBypass(entity, location)) {
                        event.isCancelled = true
                    }
                }
                is Snowman, is ArmorStand -> {
                    if (!protection.isOrigin(entity, location)) {
                        event.isCancelled = true
                    }
                }
                else -> {
                    event.isCancelled = true
                }
            }
        }
    }

    /**
     * Drop falling blocks when entering claims they don't originate from.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        if (entity !is FallingBlock) return
        if (event.to == Material.AIR) return

        val location = event.block.location
        val claim = registry.getAt(location) ?: return

        if (!claim.allowBlockBreak && !protection.isOrigin(entity, location)) {
            event.isCancelled = true
            entity.world.dropItemNaturally(entity.location, ItemStack(entity.blockData.material))
            entity.remove()
        }
    }

    /**
     * Unauthorized players spawning mobs in claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerNaturallySpawnCreatures(event: PlayerNaturallySpawnCreaturesEvent) {
        val player = event.player
        val location = player.location
        val claim = registry.getAt(location) ?: return

        if (
            claim.allowEntityInteract == "false" ||
            claim.allowEntityInteract == "onlyMounts"
        ) {
            if (!protection.canBypass(player, location)) {
                event.isCancelled = true
            }
        }
    }

    /**
     * Unauthorized players spawning phantoms in claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPhantomPreSpawn(event: PhantomPreSpawnEvent) {
        val player = event.spawningEntity as? Player ?: return
        val location = player.location
        val claim = registry.getAt(location) ?: return

        if (
            claim.allowEntityInteract == "false" ||
            claim.allowEntityInteract == "onlyMounts"
        ) {
            if (!protection.canBypass(player, location)) {
                event.isCancelled = true
            }
        }
    }
}