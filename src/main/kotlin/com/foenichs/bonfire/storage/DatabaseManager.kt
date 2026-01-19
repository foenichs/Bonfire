package com.foenichs.bonfire.storage

import com.foenichs.bonfire.model.ChunkPos
import com.foenichs.bonfire.model.Claim
import java.io.File
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID

class DatabaseManager(dataFolder: File) {
    private val connection = DriverManager.getConnection("jdbc:sqlite:${dataFolder.path}/claims.db")

    init {
        if (!dataFolder.exists()) dataFolder.mkdirs()
        val s = connection.createStatement()
        s.execute("CREATE TABLE IF NOT EXISTS claims (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, allow_block_break BOOLEAN DEFAULT 0, allow_block_interact BOOLEAN DEFAULT 0, allow_entity_interact TEXT DEFAULT 'false')")
        s.execute("CREATE TABLE IF NOT EXISTS claim_chunks (claim_id INTEGER, world_uuid TEXT NOT NULL, chunk_key INTEGER NOT NULL, FOREIGN KEY(claim_id) REFERENCES claims(id) ON DELETE CASCADE)")
        s.execute("CREATE TABLE IF NOT EXISTS trusted_players (claim_id INTEGER, player_uuid TEXT NOT NULL, trust_type TEXT NOT NULL, FOREIGN KEY(claim_id) REFERENCES claims(id) ON DELETE CASCADE)")
    }

    fun loadAll(): List<Claim> {
        val claims = mutableListOf<Claim>()
        val rs = connection.createStatement().executeQuery("SELECT * FROM claims")
        while (rs.next()) {
            val id = rs.getInt("id")
            val c = Claim(
                id,
                UUID.fromString(rs.getString("owner_uuid")),
                mutableSetOf(),
                rs.getBoolean("allow_block_break"),
                rs.getBoolean("allow_block_interact"),
                rs.getString("allow_entity_interact")
            )
            val crs = connection.createStatement().executeQuery("SELECT world_uuid, chunk_key FROM claim_chunks WHERE claim_id = $id")
            while (crs.next()) c.chunks.add(
                ChunkPos(
                    UUID.fromString(crs.getString("world_uuid")),
                    crs.getLong("chunk_key")
                )
            )
            val trs = connection.createStatement().executeQuery("SELECT player_uuid, trust_type FROM trusted_players WHERE claim_id = $id")
            while (trs.next()) {
                val u = UUID.fromString(trs.getString("player_uuid"))
                if (trs.getString("trust_type") == "ALWAYS") c.trustedAlways.add(u) else c.trustedOnline.add(u)
            }
            claims.add(c)
        }
        return claims
    }

    fun createClaim(owner: UUID): Int {
        val ps = connection.prepareStatement("INSERT INTO claims (owner_uuid) VALUES (?)", Statement.RETURN_GENERATED_KEYS)
        ps.setString(1, owner.toString()); ps.executeUpdate()
        return ps.generatedKeys.let { if (it.next()) it.getInt(1) else -1 }
    }

    fun addChunk(id: Int, p: ChunkPos) {
        val ps = connection.prepareStatement("INSERT INTO claim_chunks (claim_id, world_uuid, chunk_key) VALUES (?, ?, ?)")
        ps.setInt(1, id); ps.setString(2, p.worldUuid.toString()); ps.setLong(3, p.chunkKey); ps.executeUpdate()
    }

    fun removeChunk(id: Int, p: ChunkPos) {
        val ps = connection.prepareStatement("DELETE FROM claim_chunks WHERE claim_id = ? AND world_uuid = ? AND chunk_key = ?")
        ps.setInt(1, id); ps.setString(2, p.worldUuid.toString()); ps.setLong(3, p.chunkKey); ps.executeUpdate()
    }

    fun updateRules(c: Claim) {
        val ps = connection.prepareStatement("UPDATE claims SET allow_block_break = ?, allow_block_interact = ?, allow_entity_interact = ? WHERE id = ?")
        ps.setBoolean(1, c.allowBlockBreak); ps.setBoolean(2, c.allowBlockInteract); ps.setString(3, c.allowEntityInteract); ps.setInt(4, c.id!!); ps.executeUpdate()
    }

    fun addTrust(id: Int, u: UUID, type: String) {
        val ps = connection.prepareStatement("INSERT INTO trusted_players (claim_id, player_uuid, trust_type) VALUES (?, ?, ?)")
        ps.setInt(1, id); ps.setString(2, u.toString()); ps.setString(3, type.uppercase()); ps.executeUpdate()
    }

    fun removeTrust(id: Int, u: UUID) {
        val ps = connection.prepareStatement("DELETE FROM trusted_players WHERE claim_id = ? AND player_uuid = ?")
        ps.setInt(1, id); ps.setString(2, u.toString()); ps.executeUpdate()
    }

    fun moveChunks(f: Int, t: Int) {
        connection.createStatement().execute("UPDATE claim_chunks SET claim_id = $t WHERE claim_id = $f")
    }

    fun deleteClaim(id: Int) = connection.createStatement().execute("DELETE FROM claims WHERE id = $id")

    fun updateOwner(id: Int, newOwner: UUID) {
        val ps = connection.prepareStatement("UPDATE claims SET owner_uuid = ? WHERE id = ?")
        ps.setString(1, newOwner.toString())
        ps.setInt(2, id)
        ps.executeUpdate()
    }
    fun close() = connection.close()
}