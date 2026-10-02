package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.DatabaseManager
import org.bukkit.Statistic
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player

class LimitService(private var config: FileConfiguration, private val db: DatabaseManager) {
    data class Limits(
        val maxChunks: Int, val maxClaims: Int,
        val minutesToNextChunk: Int?, val minutesToNextClaim: Int?,
        val chunksCapped: Boolean, val claimsCapped: Boolean,
        val chunksAtCap: Boolean, val claimsAtCap: Boolean,
        val baseChunks: Int, val baseClaims: Int,
        val multipliedChunks: Int, val multipliedClaims: Int
    )

    companion object {
        private const val MULTIPLIER_PREFIX = "bonfire.playtime_multiplier."
    }

    fun updateConfig(c: FileConfiguration) {
        this.config = c
    }

    fun getLimits(p: Player): Limits {
        if (!config.getBoolean("limits.enabled", true)) return Limits(
            maxChunks = Int.MAX_VALUE, maxClaims = Int.MAX_VALUE,
            minutesToNextChunk = null, minutesToNextClaim = null,
            chunksCapped = false, claimsCapped = false,
            chunksAtCap = false, claimsAtCap = false,
            baseChunks = Int.MAX_VALUE, baseClaims = Int.MAX_VALUE,
            multipliedChunks = Int.MAX_VALUE, multipliedClaims = Int.MAX_VALUE
        )
        val mins = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200
        val earningEnabled = config.getBoolean("limits.playtime-earning.enabled", true)
        val minsPerChunk = config.getInt("limits.playtime-earning.minutes-per-chunk", 60)
        val minsPerClaim = config.getInt("limits.playtime-earning.minutes-per-claim", 1440)

        // Value 0 disables playtime earning
        val earningChunks = earningEnabled && minsPerChunk > 0
        val earningClaims = earningEnabled && minsPerClaim > 0
        val earnedCh = if (earningChunks) mins / minsPerChunk else 0
        val earnedCl = if (earningClaims) mins / minsPerClaim else 0
        val fCh = config.getInt("limits.starting-values.chunks", 0) + earnedCh
        val fCl = config.getInt("limits.starting-values.claims", 1) + earnedCl
        val mCh = config.getInt("limits.maximum.chunks", -1)
        val mCl = config.getInt("limits.maximum.claims", 5)
        val (extraCh, extraCl) = db.getLimitOverride(p.uniqueId)

        val chunksCapped = mCh != -1
        val claimsCapped = mCl != -1
        val baseCh = if (chunksCapped) fCh.coerceAtMost(mCh) else fCh
        val baseCl = if (claimsCapped) fCl.coerceAtMost(mCl) else fCl
        val nextChunk = if (earningChunks && (!chunksCapped || fCh < mCh)) minsPerChunk - mins % minsPerChunk else null
        val nextClaim = if (earningClaims && (!claimsCapped || fCl < mCl)) minsPerClaim - mins % minsPerClaim else null

        val multiplier = playtimeMultiplier(p)
        val multipliedCh = multiplier?.let { (baseCh * it).toInt().let { v -> if (chunksCapped) v.coerceAtMost(mCh) else v } } ?: baseCh
        val multipliedCl = multiplier?.let { (baseCl * it).toInt().let { v -> if (claimsCapped) v.coerceAtMost(mCl) else v } } ?: baseCl

        return Limits(
            maxOf(baseCh, multipliedCh) + extraCh,
            maxOf(baseCl, multipliedCl) + extraCl,
            nextChunk, nextClaim,
            chunksCapped, claimsCapped,
            chunksCapped && fCh >= mCh, claimsCapped && fCl >= mCl,
            baseCh, baseCl,
            multipliedCh, multipliedCl
        )
    }

    /**
     * The highest playtime multiplier permission a player holds
     */
    fun playtimeMultiplier(p: Player): Double? {
        return p.effectivePermissions
            .mapNotNull { if (it.value && it.permission.startsWith(MULTIPLIER_PREFIX)) it.permission.removePrefix(MULTIPLIER_PREFIX).toIntOrNull() else null }
            .maxOrNull()?.div(100.0)
    }

    /**
     * Syncs overrides with used multiplier claim limits, making them persistent
     */
    fun syncMultiplierOverride(p: Player, ownedChunks: Int, ownedClaims: Int) {
        if (playtimeMultiplier(p) == null) return
        val l = getLimits(p)
        val bonusCh = (ownedChunks - l.baseChunks).coerceIn(0, (l.multipliedChunks - l.baseChunks).coerceAtLeast(0))
        val bonusCl = (ownedClaims - l.baseClaims).coerceIn(0, (l.multipliedClaims - l.baseClaims).coerceAtLeast(0))
        db.setLimitOverride(p.uniqueId, bonusCh, bonusCl)
    }
}