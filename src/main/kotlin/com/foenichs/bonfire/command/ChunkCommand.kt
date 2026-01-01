package com.foenichs.bonfire.command

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.service.ClaimService
import com.foenichs.bonfire.service.LimitService
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Messenger
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class ChunkCommand(
    private val service: ClaimService,
    private val registry: ClaimRegistry,
    private val limits: LimitService,
    private val msg: Messenger
) {
    fun register(registrar: Commands) {
        val node = Commands.literal("chunk").executes { ctx ->
            val p = ctx.source.sender as? Player ?: return@executes 0
            val canClaim =
                registry.getAt(p.location.chunk) == null && registry.getOwnedChunks(p.uniqueId) < limits.getLimits(p).maxChunks

            if (!canClaim && !isOwner(p)) {
                msg.sendNoAccess(p)
            } else {
                ctx.source.sender.sendMessage(Component.text("Usage: /chunk <subcommand>", NamedTextColor.RED))
            }
            1
        }.then(Commands.literal("claim").requires { it.sender.hasPermission("bonfire.command.claim") }.executes { ctx ->
            service.tryClaim(ctx.source.sender as Player); 1
        })

            .then(Commands.literal("unclaim").requires { it.sender.hasPermission("bonfire.command.owner") }.executes { ctx ->
                service.tryUnclaim(ctx.source.sender as Player); 1
            })

            .then(
                Commands.literal("setrule").requires { it.sender.hasPermission("bonfire.command.owner") }
                    .then(booleanRuleNode("allowBlockBreak") { it.allowBlockBreak })
                    .then(booleanRuleNode("allowBlockInteract") { it.allowBlockInteract }).then(
                        Commands.literal("allowEntityInteract")
                            .then(Commands.argument("value", StringArgumentType.word()).suggests { ctx, b ->
                                val p = ctx.source.sender as Player
                                val current = registry.getAt(p.location.chunk)?.allowEntityInteract ?: "false"
                                listOf("true", "false", "onlyMounts").filter { it != current }.forEach { b.suggest(it) }
                                b.buildFuture()
                            }.executes { ctx ->
                                service.setRule(
                                    ctx.source.sender as Player,
                                    "allowEntityInteract",
                                    StringArgumentType.getString(ctx, "value")
                                ); 1
                            })
                    )
            )
            .then(
                Commands.literal("addplayer").requires { it.sender.hasPermission("bonfire.command.owner") }
                    .then(
                        Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                            val p = ctx.source.sender as Player
                            val c = registry.getAt(p.location.chunk)
                            Bukkit.getOfflinePlayers().forEach { o ->
                                if (o.name != null && o.uniqueId != p.uniqueId && c != null && !c.trustedAlways.contains(
                                        o.uniqueId
                                    ) && !c.trustedOnline.contains(o.uniqueId)
                                ) b.suggest(o.name!!)
                            }
                            b.buildFuture()
                        }.then(Commands.literal("always").executes { ctx ->
                            service.addTrust(
                                ctx.source.sender as Player, StringArgumentType.getString(ctx, "target"), "always"
                            ); 1
                        }).then(Commands.literal("whileOnline").executes { ctx ->
                            service.addTrust(
                                ctx.source.sender as Player, StringArgumentType.getString(ctx, "target"), "whileOnline"
                            ); 1
                        })
                    )
            ).then(
                Commands.literal("removeplayer").requires { it.sender.hasPermission("bonfire.command.removeplayer") }.then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        val c = registry.getAt((ctx.source.sender as Player).location.chunk)
                        c?.trustedAlways?.forEach { id -> Bukkit.getOfflinePlayer(id).name?.let { b.suggest(it) } }
                        c?.trustedOnline?.forEach { id -> Bukkit.getOfflinePlayer(id).name?.let { b.suggest(it) } }
                        b.buildFuture()
                    }.executes { ctx ->
                        service.removeTrust(ctx.source.sender as Player, StringArgumentType.getString(ctx, "target")); 1
                    })
            )

        registrar.register(node.build(), "The core command of Bonfire.")
    }

    /**
     * Boolean rule suggestions and execution
     */
    private fun booleanRuleNode(name: String, property: (Claim) -> Boolean) =
        Commands.literal(name).then(Commands.argument("value", BoolArgumentType.bool()).suggests { ctx, b ->
            val p = ctx.source.sender as Player
            val current = registry.getAt(p.location.chunk)?.let { property(it) } ?: false
            b.suggest((!current).toString())
            b.buildFuture()
        }.executes { ctx ->
            service.setRule(
                ctx.source.sender as Player, name, ctx.getArgument("value", Boolean::class.java).toString()
            ); 1
        })

    private fun isOwner(p: Player?) = p?.let { registry.getAt(it.location.chunk)?.owner == it.uniqueId } ?: false
}