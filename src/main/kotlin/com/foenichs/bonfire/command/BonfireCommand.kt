package com.foenichs.bonfire.command

import com.foenichs.bonfire.service.ClaimService
import com.foenichs.bonfire.service.LimitService
import com.foenichs.bonfire.storage.DatabaseManager
import com.foenichs.bonfire.ui.Dialogs
import com.foenichs.bonfire.ui.Messenger
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

class BonfireCommand(
    private val onReload: () -> Unit,
    private val claimService: ClaimService,
    private val db: DatabaseManager,
    private val msg: Messenger,
    private val limits: LimitService
) {
    fun register(registrar: Commands) {
        val node = Commands.literal("bonfire")
            .requires { it.sender.isOp }
            .then(Commands.literal("reloadConfig")
                .executes { ctx ->
                    onReload()
                    ctx.source.sender.sendMessage(Component.text("Successfully reloaded Bonfire's config!", NamedTextColor.GREEN))
                    1
                }
            )
            .then(Commands.literal("modifyClaim")
                .then(Commands.literal("remove")
                    .executes { ctx ->
                        val p = ctx.source.sender as? Player ?: return@executes 0
                        claimService.adminRemoveClaim(p)
                        1
                    }
                )
                .then(Commands.literal("setowner")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests { _, b -> suggestOfflinePlayers(b) }
                        .executes { ctx ->
                            val p = ctx.source.sender as? Player ?: return@executes 0
                            claimService.adminSetOwner(p, StringArgumentType.getString(ctx, "target"))
                            1
                        }
                    )
                )
            )
            .then(Commands.literal("modifyChunk")
                .then(Commands.literal("unclaim")
                    .executes { ctx ->
                        val p = ctx.source.sender as? Player ?: return@executes 0
                        claimService.adminUnclaimChunk(p)
                        1
                    }
                )
            )
            .then(Commands.literal("removeAllClaims")
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests { _, b -> suggestOfflinePlayers(b) }
                    .executes { ctx ->
                        val p = ctx.source.sender as? Player ?: return@executes 0
                        claimService.adminRemoveAll(p, StringArgumentType.getString(ctx, "target"))
                        1
                    }
                )
            )
            .then(Commands.literal("overrideLimits")
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests { _, b -> suggestOfflinePlayers(b) }
                    .executes { ctx ->
                        val p = ctx.source.sender as? Player ?: return@executes 0
                        val target = Dialogs.resolvePlayer(p, StringArgumentType.getString(ctx, "target")) ?: return@executes 0
                        val (extraChunks, extraClaims) = db.getLimitOverride(target.uniqueId)
                        val multiplierActive = target.player?.let { limits.playtimeMultiplier(it) != null } ?: false
                        val name = (target.name ?: "Unknown").take(16)
                        Dialogs.overrideLimits(p, name, extraChunks, extraClaims, multiplierActive) { newChunks, newClaims ->
                            db.setLimitOverride(target.uniqueId, newChunks, newClaims)
                            msg.send(p, Component.text()
                                .append(Component.text("Successfully updated the additional limits for "))
                                .append(msg.head(name)).append(Component.space()).append(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD))
                                .append(Component.text(" to $newChunks chunks and $newClaims claims.")).build())
                        }
                        1
                    }
                )
            )

        registrar.register(node.build(), "Bonfire's experimental management command. Operator-only.")
    }

    private fun suggestOfflinePlayers(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        Bukkit.getOfflinePlayers().forEach { o ->
            val name = o.name
            if (name != null && name.lowercase().startsWith(input)) {
                builder.suggest(name)
            }
        }
        return builder.buildFuture()
    }
}