package com.foenichs.bonfire

import com.foenichs.bonfire.command.BonfireCommand
import com.foenichs.bonfire.command.ChunkCommand
import com.foenichs.bonfire.listener.PlayerListener
import com.foenichs.bonfire.listener.protection.*
import com.foenichs.bonfire.service.*
import com.foenichs.bonfire.service.map.BlueMapClaimMapService
import com.foenichs.bonfire.service.map.BlueMapService
import com.foenichs.bonfire.service.map.ClaimMapService
import com.foenichs.bonfire.service.map.SquaremapService
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.storage.DatabaseManager
import com.foenichs.bonfire.ui.Messenger
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class Bonfire : JavaPlugin() {
    private lateinit var db: DatabaseManager
    private lateinit var registry: ClaimRegistry
    private lateinit var protectionService: ProtectionService
    private lateinit var visualService: VisualService

    override fun onEnable() {
        // Version Check
        Bukkit.getAsyncScheduler().runNow(this) { _ ->
            try {
                val conn = java.net.URI("https://api.modrinth.com/v2/project/bonfire/version").toURL().openConnection()
                conn.setRequestProperty("User-Agent", "${pluginMeta.authors[0]}/${pluginMeta.name}/${pluginMeta.version}")
                val json = conn.getInputStream().bufferedReader().use { it.readText() }
                val versions = "\"version_number\":\"([^\"]+)\"".toRegex().findAll(json).map { it.groupValues[1] }.toList()
                val latest = versions.firstOrNull() ?: return@runNow
                val index = versions.indexOf(pluginMeta.version)

                if (index == 0) {
                    logger.info("Running on the latest version.")
                } else if (index > 0) {
                    logger.info("Currently running $index version(s) behind (${pluginMeta.version} -> $latest)")
                    logger.info("Download the latest version from https://modrinth.com/plugin/bonfire")
                } else {
                    logger.info("Running on the latest development version.")
                }
            } catch (_: Exception) {}
        }

        // Initialize Configuration
        saveDefaultConfig()

        // Initialize Database and Memory Cache
        db = DatabaseManager(dataFolder)
        registry = ClaimRegistry(db.loadAll().toMutableList())

        // Initialize Services
        val messenger = Messenger()
        val limitService = LimitService(config, registry)
        protectionService = ProtectionService(registry)
        visualService = VisualService(this, registry, protectionService, limitService)
        val migrationService = MigrationService(this, db, registry, protectionService)

        // Initialize map integrations (optional)
        val blueMapService = if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapService(this, registry)
        } else {
            null
        }
        val squaremapService = if (Bukkit.getPluginManager().isPluginEnabled("squaremap")) {
            SquaremapService(this, registry).also { it.refreshAll() }
        } else {
            null
        }
        val mapServices: List<ClaimMapService> = listOfNotNull(
            blueMapService?.let(::BlueMapClaimMapService),
            squaremapService
        )

        // Initialize Listener first (ClaimService needs it for cache updates)
        val playerListener = PlayerListener(this, registry, messenger, visualService)

        // Initialize Logic Service
        val claimService = ClaimService(registry, db, messenger, limitService, visualService, playerListener, mapServices, migrationService, this)

        // Register Command Tree
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            ChunkCommand(claimService, registry, limitService, messenger).register(event.registrar())
            BonfireCommand({
                reloadConfig()
                limitService.updateConfig(config)
                mapServices.forEach { it.refreshAll() }
            }, claimService).register(event.registrar())
        }

        // Register Event Listeners
        val pluginManager = Bukkit.getPluginManager()

        // General player movement and visuals
        pluginManager.registerEvents(playerListener, this)

        // Rule Enforcement
        pluginManager.registerEvents(BlockProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(WorldProtectionListener(this, registry, protectionService), this)
        pluginManager.registerEvents(PistonProtectionListener(protectionService), this)
        pluginManager.registerEvents(ExplosionProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(InteractProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(EntityProtectionListener(this, registry, protectionService, visualService), this)

        // bStats
        val pluginId = 28932
        Metrics(this, pluginId)
    }

    override fun onDisable() {
        // Ensure database connection is closed safely
        if (::db.isInitialized) {
            db.close()
        }
    }
}
