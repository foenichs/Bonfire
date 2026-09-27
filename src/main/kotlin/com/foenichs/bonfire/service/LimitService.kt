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
        val chunksAtCap: Boolean, val claimsAtCap: Boolean
    )

    fun updateConfig(c: FileConfiguration) {
        this.config = c
    }

    fun getLimits(p: Player): Limits {
        if (!config.getBoolean("limits.enabled", true)) return Limits(Int.MAX_VALUE, Int.MAX_VALUE, null, null, false, false, false, false)
        val mins = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200
        val earningEnabled = config.getBoolean("limits.playtime-earning.enabled", true)
        val minsPerChunk = config.getInt("limits.playtime-earning.minutes-per-chunk", 60)
        val minsPerClaim = config.getInt("limits.playtime-earning.minutes-per-claim", 1440)
        val earnedCh = if (earningEnabled) mins / minsPerChunk else 0
        val earnedCl = if (earningEnabled) mins / minsPerClaim else 0
        val fCh = config.getInt("limits.starting-values.chunks", 0) + earnedCh
        val fCl = config.getInt("limits.starting-values.claims", 1) + earnedCl
        val mCh = config.getInt("limits.maximum.chunks", -1)
        val mCl = config.getInt("limits.maximum.claims", 5)
        val (extraCh, extraCl) = db.getLimitOverride(p.uniqueId)

        val chunksCapped = mCh != -1
        val claimsCapped = mCl != -1
        val nextChunk = if (earningEnabled && (!chunksCapped || fCh < mCh)) minsPerChunk - mins % minsPerChunk else null
        val nextClaim = if (earningEnabled && (!claimsCapped || fCl < mCl)) minsPerClaim - mins % minsPerClaim else null

        return Limits(
            (if (chunksCapped) fCh.coerceAtMost(mCh) else fCh) + extraCh,
            (if (claimsCapped) fCl.coerceAtMost(mCl) else fCl) + extraCl,
            nextChunk, nextClaim,
            chunksCapped, claimsCapped,
            chunksCapped && fCh >= mCh, claimsCapped && fCl >= mCl
        )
    }
}