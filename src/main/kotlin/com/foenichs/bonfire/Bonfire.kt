package com.foenichs.bonfire

import com.foenichs.bonfire.command.BonfireCommand
import com.foenichs.bonfire.command.ChunkCommand
import com.foenichs.bonfire.listener.PlayerListener
import com.foenichs.bonfire.listener.protection.*
import com.foenichs.bonfire.service.*
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

        // Initialize BlueMap Service (optional)
        val blueMapService = if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapService(this, registry)
        } else {
            null
        }

        // Initialize Listener first (ClaimService needs it for cache updates)
        val playerListener = PlayerListener(this, registry, messenger, visualService)

        // Initialize Logic Service
        val claimService = ClaimService(registry, db, messenger, limitService, visualService, playerListener, blueMapService, this)

        // Register Command Tree
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            ChunkCommand(claimService, registry, limitService, messenger).register(event.registrar())
            BonfireCommand({
                reloadConfig()
                limitService.updateConfig(config)
                blueMapService?.refreshAll()
            }, claimService).register(event.registrar())
        }

        // Register Event Listeners
        val pluginManager = Bukkit.getPluginManager()

        // General player movement and visuals
        pluginManager.registerEvents(playerListener, this)

        // Rule Enforcement
        pluginManager.registerEvents(BlockProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(WorldProtectionListener(protectionService), this)
        pluginManager.registerEvents(PistonProtectionListener(protectionService), this)
        pluginManager.registerEvents(ExplosionProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(InteractProtectionListener(registry, protectionService), this)
        pluginManager.registerEvents(EntityProtectionListener(registry, protectionService), this)

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