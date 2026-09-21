package com.foenichs.bonfire.command

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.service.ClaimService
import com.foenichs.bonfire.service.LimitService
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Dialogs
import com.foenichs.bonfire.ui.Messenger
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault

class ChunkCommand(
    private val service: ClaimService,
    private val registry: ClaimRegistry,
    private val limits: LimitService,
    private val msg: Messenger
) : Listener {

    fun register(registrar: Commands) {
        /** Register command permissions to override the default Operator access */
        val pm = Bukkit.getPluginManager()
        listOf(
            "bonfire.command.claim",
            "bonfire.command.owner",
            "bonfire.command.removeplayer"
        ).forEach { node ->
            if (pm.getPermission(node) == null) {
                pm.addPermission(Permission(node, PermissionDefault.FALSE))
            }
        }

        val node = Commands.literal("chunk").executes { ctx ->
            val p = ctx.source.sender as? Player ?: return@executes 0
            val claim = registry.getAt(p.location)
            val l = limits.getLimits(p)
            val canClaim =
                claim == null && registry.getOwnedChunks(p.uniqueId) < l.maxChunks && registry.getOwnedClaimsCount(p.uniqueId) < l.maxClaims

            if (!canClaim && !isOwner(p)) {
                if (claim != null) {
                    val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"
                    Dialogs.chunkClaimed(p, ownerName)
                } else {
                    Dialogs.cannotClaim(p)
                }
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
                            .then(Commands.literal("true").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location)?.allowEntityInteract != "true" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "true"); 1
                            })
                            .then(Commands.literal("false").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location)?.allowEntityInteract != "false" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "false"); 1
                            })
                            .then(Commands.literal("onlyMounts").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location)?.allowEntityInteract != "onlyMounts" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "onlyMounts"); 1
                            })
                    )
            )
            .then(
                Commands.literal("addplayer").requires { it.sender.hasPermission("bonfire.command.owner") }
                    .then(
                        Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                            val p = ctx.source.sender as Player
                            val input = b.remaining.lowercase()

                            Bukkit.getOfflinePlayers().forEach { o ->
                                val name = o.name
                                if (name != null && name.lowercase().startsWith(input) && o.uniqueId != p.uniqueId) {
                                    b.suggest(name)
                                }
                            }
                            b.buildFuture()
                        }.executes { ctx ->
                            val p = ctx.source.sender as? Player ?: return@executes 0
                            if (!service.verifyPermissions(p)) { msg.sendNoAccess(p); return@executes 0 }

                            val resolvedName = getResolvedName(p, StringArgumentType.getString(ctx, "target")) ?: return@executes 0
                            showAddDialog(p, resolvedName)
                            1
                        }.then(Commands.literal("always").executes { ctx ->
                            val p = ctx.source.sender as Player
                            val resolvedName = getResolvedName(p, StringArgumentType.getString(ctx, "target")) ?: return@executes 0
                            service.addTrust(p, resolvedName, "always"); 1
                        }).then(Commands.literal("whileOnline").executes { ctx ->
                            val p = ctx.source.sender as Player
                            val resolvedName = getResolvedName(p, StringArgumentType.getString(ctx, "target")) ?: return@executes 0
                            service.addTrust(p, resolvedName, "whileOnline"); 1
                        })
                    )
            ).then(
                Commands.literal("removeplayer").requires { it.sender.hasPermission("bonfire.command.removeplayer") }.then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        val c = registry.getAt((ctx.source.sender as Player).location)
                        val input = b.remaining.lowercase()

                        c?.trustedAlways?.forEach { id ->
                            val name = Bukkit.getOfflinePlayer(id).name
                            if (name != null && name.lowercase().startsWith(input)) b.suggest(name)
                        }
                        c?.trustedOnline?.forEach { id ->
                            val name = Bukkit.getOfflinePlayer(id).name
                            if (name != null && name.lowercase().startsWith(input)) b.suggest(name)
                        }
                        b.buildFuture()
                    }.executes { ctx ->
                        val p = ctx.source.sender as Player
                        val resolvedName = getResolvedName(p, StringArgumentType.getString(ctx, "target")) ?: return@executes 0
                        service.removeTrust(p, resolvedName); 1
                    })
            )

        registrar.register(node.build(), "The core command of Bonfire.")
    }

    /**
     * Error feedback for dynamic subcommands
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val message = event.message.trim()
        val parts = message.removePrefix("/").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return

        val root = parts[0].lowercase()
        if (root != "chunk" && root != "bonfire:chunk") return
        if (parts.size == 1) return

        val p = event.player
        val claim = registry.getAt(p.location)
        val isOwner = claim != null && claim.owner == p.uniqueId
        val l = limits.getLimits(p)
        val canClaim =
            claim == null && registry.getOwnedChunks(p.uniqueId) < l.maxChunks && registry.getOwnedClaimsCount(p.uniqueId) < l.maxClaims
        when (val sub = parts[1].lowercase()) {
            "claim" -> {
                if (!canClaim) {
                    event.isCancelled = true
                    if (claim != null) {
                        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"
                        Dialogs.chunkClaimed(p, ownerName)
                    } else {
                        Dialogs.cannotClaim(p)
                    }
                }
            }

            "unclaim", "setrule", "addplayer", "removeplayer" -> {
                if (!isOwner) {
                    event.isCancelled = true
                    if (claim != null) {
                        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"
                        Dialogs.chunkClaimed(p, ownerName)
                    } else {
                        Dialogs.chunkNotClaimed(p)
                    }
                    return
                }

                when (sub) {
                    "removeplayer" -> {
                        val hasTrusted = claim.trustedAlways.isNotEmpty() || claim.trustedOnline.isNotEmpty()
                        if (parts.size >= 3) {
                            val targetName = parts[2]
                            event.isCancelled = true
                            val target = Dialogs.resolvePlayer(p, targetName) ?: return
                            val isAdded = claim.trustedAlways.contains(target.uniqueId) || claim.trustedOnline.contains(target.uniqueId)
                            if (!isAdded) {
                                Dialogs.playerNotAdded(p, target.name ?: targetName)
                            } else {
                                event.isCancelled = false
                            }
                        } else if (!hasTrusted) {
                            event.isCancelled = true
                            Dialogs.noPlayersAdded(p)
                        }
                    }

                    "setrule" -> {
                        if (parts.size >= 4) {
                            val rule = parts[2]
                            val value = parts[3]
                            val isAlreadySet = when (rule.lowercase()) {
                                "allowblockbreak" -> value.equals(claim.allowBlockBreak.toString(), true)
                                "allowblockinteract" -> value.equals(claim.allowBlockInteract.toString(), true)
                                "allowentityinteract" -> value.equals(claim.allowEntityInteract, true)
                                else -> false
                            }
                            if (isAlreadySet) {
                                event.isCancelled = true
                                Dialogs.nothingChanged(p, "that rule is already set to $value.")
                            }
                        }
                    }

                    "addplayer" -> {
                        if (parts.size >= 3 && parts[2].equals(p.name, true)) {
                            event.isCancelled = true
                            Dialogs.nothingChanged(p, "you can't add yourself to your own claim.")
                        }
                    }
                }
            }
        }
    }

    /**
     * Dialog for providing missing value
     */
    @Suppress("UnstableApiUsage")
    private fun showAddDialog(p: Player, target: String) {
        val dialog = Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text("Add Player", NamedTextColor.WHITE))
                    .body(listOf(DialogBody.plainMessage(
                        Component.text()
                            .append(Component.text("When should "))
                            .append(msg.head(target)).append(Component.space()).append(Component.text(target, NamedTextColor.WHITE, TextDecoration.BOLD))
                            .append(Component.text(" not be affected by your claim's rules?")).build()
                    )))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(Component.text("Always"), null, 60, DialogAction.staticAction(ClickEvent.runCommand("/chunk addplayer $target always"))),
                    ActionButton.create(Component.text("While I'm online"), null, 100, DialogAction.staticAction(ClickEvent.runCommand("/chunk addplayer $target whileOnline")))
                )).build()
            )
        }
        p.showDialog(dialog)
    }

    /**
     * Boolean rule suggestions and execution
     */
    private fun booleanRuleNode(name: String, property: (Claim) -> Boolean) =
        Commands.literal(name)
            .then(Commands.literal("true").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location)?.let { c -> !property(c) } } ?: true }.executes { ctx ->
                service.setRule(ctx.source.sender as Player, name, "true"); 1
            })
            .then(Commands.literal("false").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location)?.let { c -> property(c) } } ?: true }.executes { ctx ->
                service.setRule(ctx.source.sender as Player, name, "false"); 1
            })

    private fun isOwner(p: Player?) = p?.let { registry.getAt(it.location)?.owner == it.uniqueId } ?: false

    /**
     * Validation for profile names
     */
    private fun getResolvedName(p: Player, input: String): String? = Dialogs.resolvePlayer(p, input)?.name
}