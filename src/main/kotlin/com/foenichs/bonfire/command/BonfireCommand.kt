package com.foenichs.bonfire.command

import com.foenichs.bonfire.service.ClaimService
import com.foenichs.bonfire.storage.DatabaseManager
import com.foenichs.bonfire.ui.Dialogs
import com.foenichs.bonfire.ui.Messenger
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

class BonfireCommand(
    private val onReload: () -> Unit,
    private val claimService: ClaimService,
    private val db: DatabaseManager,
    private val msg: Messenger
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
                        showOverrideLimitsDialog(p, target, extraChunks, extraClaims)
                        1
                    }
                )
            )

        registrar.register(node.build(), "Bonfire's management command. Operator-only.")
    }

    private fun showOverrideLimitsDialog(p: Player, target: OfflinePlayer, chunks: Int, claims: Int) {
        p.showDialog(overrideLimitsDialog(p, target, chunks, claims))
    }

    @Suppress("UnstableApiUsage")
    private fun overrideLimitsDialog(p: Player, target: OfflinePlayer, chunks: Int, claims: Int): Dialog {
        val name = (target.name ?: "Unknown").take(16)
        return Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text("Limit Overrides"))
                    .body(listOf(DialogBody.plainMessage(
                        Component.text()
                            .append(Component.text("How many additional chunks and claims should "))
                            .append(msg.head(name)).append(Component.space()).append(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD))
                            .append(Component.text(" have?")).build()
                    )))
                    .inputs(listOf(
                        DialogInput.text("chunks", Component.text("Chunks")).width(120).initial(chunks.toString()).build(),
                        DialogInput.text("claims", Component.text("Claims")).width(120).initial(claims.toString()).build()
                    ))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(Component.text("Apply"), null, 60, DialogAction.customClick({ view, _ ->
                        val newChunks = view.getText("chunks")?.toIntOrNull() ?: 0
                        val newClaims = view.getText("claims")?.toIntOrNull() ?: 0
                        db.setLimitOverride(target.uniqueId, newChunks, newClaims)
                        msg.send(p, Component.text()
                            .append(Component.text("Set "))
                            .append(msg.head(name)).append(Component.space()).append(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD))
                            .append(Component.text("'s limit overrides to $newChunks chunks and $newClaims claims.")).build())
                    }, ClickCallback.Options.builder().uses(1).build())),
                    ActionButton.create(Component.text("Reset values"), null, 90, DialogAction.customClick({ _, audience ->
                        audience.showDialog(overrideLimitsDialog(p, target, 0, 0))
                    }, ClickCallback.Options.builder().uses(1).build()))
                )).build()
            )
        }
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