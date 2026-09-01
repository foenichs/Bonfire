package com.foenichs.bonfire.service

import com.foenichs.bonfire.storage.DatabaseManager
import org.bukkit.Statistic
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player

class LimitService(private var config: FileConfiguration, private val db: DatabaseManager) {
    data class Limits(val maxChunks: Int, val maxClaims: Int)

    fun updateConfig(c: FileConfiguration) {
        this.config = c
    }

    fun getLimits(p: Player): Limits {
        if (!config.getBoolean("limits.enabled", true)) return Limits(Int.MAX_VALUE, Int.MAX_VALUE)
        val mins = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200
        val earnedCh = if (config.getBoolean(
                "limits.playtime-earning.enabled", true
            )
        ) mins / config.getInt("limits.playtime-earning.minutes-per-chunk", 60) else 0
        val earnedCl = if (config.getBoolean(
                "limits.playtime-earning.enabled", true
            )
        ) mins / config.getInt("limits.playtime-earning.minutes-per-claim", 1440) else 0
        val fCh = config.getInt("limits.starting-values.chunks", 0) + earnedCh
        val fCl = config.getInt("limits.starting-values.claims", 1) + earnedCl
        val mCh = config.getInt("limits.maximum.chunks", -1)
        val mCl = config.getInt("limits.maximum.claims", 5)
        val (extraCh, extraCl) = db.getLimitOverride(p.uniqueId)
        return Limits((if (mCh == -1) fCh else fCh.coerceAtMost(mCh)) + extraCh,(if (mCl == -1) fCl else fCl.coerceAtMost(mCl)) + extraCl)
    }
}