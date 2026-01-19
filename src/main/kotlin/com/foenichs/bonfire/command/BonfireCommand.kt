package com.foenichs.bonfire.command

import com.foenichs.bonfire.service.ClaimService
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class BonfireCommand(
    private val onReload: () -> Unit,
    private val claimService: ClaimService
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
                        .suggests { _, b ->
                            Bukkit.getOfflinePlayers().forEach { o -> o.name?.let { b.suggest(it) } }
                            b.buildFuture()
                        }
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
                    .suggests { _, b ->
                        Bukkit.getOfflinePlayers().forEach { o -> o.name?.let { b.suggest(it) } }
                        b.buildFuture()
                    }
                    .executes { ctx ->
                        val p = ctx.source.sender as? Player ?: return@executes 0
                        claimService.adminRemoveAll(p, StringArgumentType.getString(ctx, "target"))
                        1
                    }
                )
            )

        registrar.register(node.build(), "Bonfire's management command. Operator-only.")
    }
}