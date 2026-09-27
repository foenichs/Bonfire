package com.foenichs.bonfire.service

import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.ChunkLayer
import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.storage.DatabaseManager
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.StringReader

class MigrationService(
    private val plugin: Bonfire,
    private val db: DatabaseManager,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    private val requiredDataVersion = "1.6.0"

    companion object {
        /**
         * Migrates database schema for new claim layers and rules
         */
        fun migrateDatabase(db: DatabaseManager) {
            val conn = db.connection
            val s = conn.createStatement()
            val columns = mutableSetOf<String>()
            conn.metaData.getColumns(null, null, "claims", null).use { rs ->
                while (rs.next()) columns.add(rs.getString("COLUMN_NAME").lowercase())
            }

            if (columns.isNotEmpty() && !columns.contains("block_actions")) {
                s.execute("ALTER TABLE claims ADD COLUMN block_actions TEXT DEFAULT 'never'")
                if (columns.contains("allow_block_break")) {
                    s.execute("UPDATE claims SET block_actions = CASE WHEN allow_block_break = 1 THEN 'always' WHEN allow_block_interact = 1 THEN 'interactOnly' ELSE 'never' END")
                }
            }
            if (columns.isNotEmpty() && !columns.contains("entity_actions")) {
                s.execute("ALTER TABLE claims ADD COLUMN entity_actions TEXT DEFAULT 'never'")
                if (columns.contains("allow_entity_interact")) {
                    s.execute("UPDATE claims SET entity_actions = CASE WHEN allow_entity_interact = 'true' THEN 'always' WHEN allow_entity_interact = 'onlyMounts' THEN 'interactOnly' ELSE 'never' END")
                }
            }

            val hasLayerColumn = conn.metaData.getColumns(null, null, "claim_chunks", "layer").use { it.next() }
            if (!hasLayerColumn) {
                s.execute("ALTER TABLE claim_chunks ADD COLUMN layer TEXT NOT NULL DEFAULT 'GROUND'")
            }
        }

        private val configKeyLine = Regex("""^(\s*)([\w.-]+):\s*(.*)$""")
        private val configInlineComment = Regex("""^(.*?)(\s+#.*)?$""")
        private val configYaml = Yaml()

        /**
         * Rebuilds config.yml from default while keeping original values
         */
        fun migrateConfig(plugin: JavaPlugin) {
            val configFile = File(plugin.dataFolder, "config.yml")
            val template = plugin.getResource("config.yml")?.bufferedReader()?.use { it.readText() } ?: return

            if (!configFile.exists()) {
                plugin.saveDefaultConfig()
                return
            }

            val old = YamlConfiguration.loadConfiguration(configFile)
            migrateDefaultRules(old)
            val merged = mergeConfig(template, old)

            if (merged != configFile.readText()) configFile.writeText(merged)
        }

        /**
         * Maps legacy claim rules onto blockActions/entityActions
         */
        private fun migrateDefaultRules(old: YamlConfiguration) {
            if (!old.isSet("default-rules.blockActions") && (old.isSet("default-rules.allowBlockBreak") || old.isSet("default-rules.allowBlockInteract"))) {
                old.set("default-rules.blockActions", if (old.getBoolean("default-rules.allowBlockBreak")) "always" else if (old.getBoolean("default-rules.allowBlockInteract")) "interactOnly" else "never")
            }
            if (!old.isSet("default-rules.entityActions") && old.isSet("default-rules.allowEntityInteract")) {
                old.set("default-rules.entityActions", when (old.getString("default-rules.allowEntityInteract")) { "true" -> "always"; "onlyMounts" -> "interactOnly"; else -> "never" })
            }
        }

        /**
         * Inserts original values into the template, dropping unused map sections
         */
        private fun mergeConfig(template: String, old: YamlConfiguration): String {
            val text = template.replace("\r\n", "\n")
            val defaults = YamlConfiguration.loadConfiguration(StringReader(text))
            val skip = listOf("bluemap" to "BlueMap", "squaremap" to "squaremap").filter { (path, pluginName) ->
                if (Bukkit.getPluginManager().isPluginEnabled(pluginName)) return@filter false
                val section = defaults.getConfigurationSection(path)
                section == null || !old.isSet(path) || section.getKeys(true).filter { !section.isConfigurationSection(it) }.all { old.get("$path.$it") == section.get(it) }
            }.map { it.first }

            return text.split("\n\n").filter { block ->
                val firstLine = block.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                firstLine == null || configKeyLine.matchEntire(firstLine)?.groupValues?.get(2) !in skip
            }.joinToString("\n\n") { mergeBlock(it, old) }
        }

        /**
         * Inserts original values into one config section
         */
        private fun mergeBlock(block: String, old: YamlConfiguration): String {
            val path = ArrayDeque<Pair<Int, String>>()
            return block.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trimStart()
                val match = if (trimmed.isEmpty() || trimmed.startsWith("#")) null else configKeyLine.matchEntire(line)

                if (match == null) {
                    line
                } else {
                    val indent = match.groupValues[1].length
                    val key = match.groupValues[2]
                    val rest = match.groupValues[3]
                    while (path.isNotEmpty() && path.last().first >= indent) path.removeLast()

                    if (rest.isBlank()) {
                        path.addLast(indent to key)
                        line
                    } else {
                        val fullPath = (path.map { it.second } + key).joinToString(".")
                        val (value, comment) = configInlineComment.matchEntire(rest)!!.destructured
                        val newValue = if (old.isSet(fullPath)) configYaml.dump(old.get(fullPath)).trim() else value
                        "${" ".repeat(indent)}$key: $newValue$comment"
                    }
                }
            }
        }
    }

    init {
        val currentVersion = db.getMetadata("data_version")
        val isFresh = registry.getAll().isEmpty()

        if (isFresh) {
            db.setMetadata("data_version", requiredDataVersion)
        } else if (currentVersion == null || isNewerThan(currentVersion)) {
            migrateDatabase(db)
            db.fillMigrationQueue()
            mergeNetherRoofClaims()
            db.setMetadata("data_version", requiredDataVersion)
        }

        if (db.getQueueSize() > 0) {
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }
    }

    /**
     * Apply origin tagging to all relevant entities in a chunk and remove it from the queue
     */
    fun processChunk(chunk: Chunk) {
        val worldUuid = chunk.world.uid
        val chunkKey = chunk.chunkKey

        chunk.entities.forEach { entity ->
            if (entity is Vehicle || entity is FallingBlock || entity is Snowman || entity is ArmorStand) {
                val claim = registry.getAt(entity.location) ?: return@forEach
                if (!protection.isOrigin(entity, entity.location)) {
                    // Remove stale origin tags
                    val tagsToRemove = entity.scoreboardTags.filter { it.startsWith("bonfire_origin_") }
                    tagsToRemove.forEach { entity.removeScoreboardTag(it) }

                    // Apply valid tag
                    entity.addScoreboardTag("bonfire_origin_${claim.id}")
                }
            }
        }

        // Cleanup of the migration queue
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            if (db.isChunkInQueue(worldUuid, chunkKey)) {
                db.removeFromQueue(worldUuid, chunkKey)

                // If queue is empty, unregister the listener
                if (db.getQueueSize() == 0) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        ChunkLoadEvent.getHandlerList().unregister(this)
                    })
                }
            }
        }
    }

    /**
     * Split pre-existing claimed chunks in the nether into ground and roof chunks
     */
    private fun mergeNetherRoofClaims() {
        registry.getAll().forEach { claim ->
            val netherGroundChunks = claim.chunks.filter { pos ->
                pos.layer == ChunkLayer.GROUND && Bukkit.getWorld(pos.worldUuid)?.environment == World.Environment.NETHER
            }
            if (netherGroundChunks.isEmpty()) return@forEach

            netherGroundChunks.forEach { pos ->
                val roofPos = ChunkPos(pos.worldUuid, pos.chunkKey, ChunkLayer.ROOF)
                claim.chunks.add(roofPos)
                db.addChunk(claim.id!!, roofPos)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (db.isChunkInQueue(event.chunk.world.uid, event.chunk.chunkKey)) {
            processChunk(event.chunk)
        }
    }

    /**
     * Checks if the required version is newer than the current database version
     */
    private fun isNewerThan(current: String): Boolean {
        val numRegex = "\\d+".toRegex()
        val req = numRegex.findAll(requiredDataVersion).map { it.value.toInt() }.toList()
        val curr = numRegex.findAll(current).map { it.value.toInt() }.toList()

        for (i in 0 until maxOf(req.size, curr.size)) {
            val n1 = req.getOrElse(i) { 0 }
            val n2 = curr.getOrElse(i) { 0 }
            if (n1 > n2) return true
            if (n1 < n2) return false
        }
        return false
    }
}