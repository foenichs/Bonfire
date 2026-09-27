package com.foenichs.bonfire.ui

import com.foenichs.bonfire.service.ClaimService
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

@Suppress("UnstableApiUsage")
object Dialogs {
    private val msg = Messenger()

    /**
     * Resolves an offline player by name, returns null if they don't exist
     */
    fun resolvePlayer(viewer: Player, name: String): OfflinePlayer? {
        val cropped = name.take(16)
        val offline = Bukkit.getOfflinePlayers().find { it.name?.equals(cropped, true) == true }
        if (offline == null || (!offline.hasPlayedBefore() && !offline.isOnline)) {
            viewer.showDialog(playerNotFound(cropped))
            return null
        }
        return offline
    }

    fun chunkClaimed(viewer: Player, ownerName: String) {
        val cropped = ownerName.take(16)
        val word = ClaimService.chunkWord(viewer.location)
        viewer.showDialog(infoDialog(
            Component.text("The $word you're currently in is claimed by ")
                .append(msg.head(cropped))
                .append(Component.text(" $cropped").decorate(TextDecoration.BOLD).append(Component.text(".")))
        ))
    }

    fun chunkNotClaimed(viewer: Player) {
        val word = ClaimService.chunkWord(viewer.location)
        viewer.showDialog(errorDialog(
            Component.text("The $word you're currently in isn't claimed by anyone.")
        ))
    }

    fun playerNotAdded(viewer: Player, name: String) {
        val cropped = name.take(16)
        viewer.showDialog(errorDialog(
            Component.text()
                .append(Component.text("The player "))
                .append(msg.head(cropped)).append(Component.space()).append(Component.text(cropped, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" isn't added to your claim."))
                .build()
        ))
    }

    fun noPlayersAdded(viewer: Player) {
        viewer.showDialog(errorDialog(
            Component.text("There aren't any players that are added to your claim.")
        ))
    }

    fun cannotClaim(viewer: Player) {
        val word = ClaimService.chunkWord(viewer.location)
        viewer.showDialog(errorDialog(
            Component.text("You can't claim this $word.")
                .append(Component.text(" You have either reached your claim limit or you haven't earned any claims yet.", NamedTextColor.GRAY))
        ))
    }

    fun cannotUnclaimSplit(viewer: Player, chunkWord: String = ClaimService.chunkWord(viewer.location)) {
        viewer.showDialog(errorDialog(
            Component.text("You can't unclaim this $chunkWord.")
                .append(Component.text(" Unclaiming it would split up your claim, please unclaim outer chunks first.", NamedTextColor.GRAY))
        ))
    }

    fun playerAlreadyAdded(viewer: Player, name: String) {
        val cropped = name.take(16)
        viewer.showDialog(errorDialog(
            Component.text()
                .append(Component.text("Nothing changes, as "))
                .append(msg.head(cropped)).append(Component.space()).append(Component.text(cropped, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" is already added with this type."))
                .append(Component.text(" To remove players, use the /chunk removeplayer command.", NamedTextColor.GRAY))
                .build()
        ))
    }

    fun mergeClaims(viewer: Player, onConfirm: () -> Unit) {
        val word = ClaimService.chunkWord(viewer.location)
        val dialog = Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text("Merge Claims"))
                    .body(listOf(DialogBody.plainMessage(
                        Component.text("Claiming this $word would merge two claims, overriding the settings of the claim that was created later.")
                    )))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(Component.text("Merge"), null, 75, DialogAction.customClick({ _, _ ->
                        onConfirm()
                    }, ClickCallback.Options.builder().uses(1).build())),
                    ActionButton.create(Component.text("Cancel"), null, 75, DialogAction.customClick({ _, _ -> }, ClickCallback.Options.builder().uses(1).build()))
                )).build()
            )
        }
        viewer.showDialog(dialog)
    }

    fun nothingChanged(viewer: Player, reason: String) {
        viewer.showDialog(errorDialog(Component.text("Nothing changed, as $reason")))
    }

    fun escapeConfirm(viewer: Player, cooldownText: String, onConfirm: () -> Unit) {
        val dialog = Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text("Just letting you know..."))
                    .body(listOf(DialogBody.plainMessage(
                        Component.text("This should only be used if you're stuck, as you won't be able to use it again for $cooldownText.")
                    )))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(Component.text("Cancel"), null, 75, DialogAction.customClick({ _, _ -> }, ClickCallback.Options.builder().uses(1).build())),
                    ActionButton.create(Component.text("Escape"), null, 75, DialogAction.customClick({ _, _ ->
                        onConfirm()
                    }, ClickCallback.Options.builder().uses(1).build()))
                )).build()
            )
        }
        viewer.showDialog(dialog)
    }

    fun escapeOnCooldown(viewer: Player, remaining: String) {
        viewer.showDialog(errorDialog(
            Component.text("You're currently on cooldown.")
                .append(Component.text(" You'll be able to escape again in $remaining.", NamedTextColor.GRAY))
        ))
    }

    fun escapeFailed(viewer: Player) {
        viewer.showDialog(errorDialog(
            Component.text("Couldn't find anywhere safe nearby to escape to. If you're stuck, please contact a server operator.")
        ))
    }

    fun escapingDisabled(viewer: Player) {
        viewer.showDialog(errorDialog(Component.text("Escaping is disabled on this server.")
            .append(Component.text(" If you're stuck in a claim, please contact a server operator.", NamedTextColor.GRAY))))
    }

    fun claimRulesDialog(viewer: Player, onSelectRule: (String) -> Unit) {
        val dialog = Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text("Claim Rules"))
                    .body(listOf(DialogBody.plainMessage(Component.text("Manage how others can interact with blocks and entities in your claim."))))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(
                        Component.text("Block Actions"),
                        Component.text("Whether blocks can be destroyed or placed by players, explosions, pistons, water, growing structures, etc."),
                        90,
                        DialogAction.customClick({ _, _ -> onSelectRule("blockActions") }, ClickCallback.Options.builder().uses(1).build())
                    ),
                    ActionButton.create(
                        Component.text("Entity Actions"),
                        Component.text("Whether players can damage entities, collide with them, get targeted by them, or only interact with them."),
                        90,
                        DialogAction.customClick({ _, _ -> onSelectRule("entityActions") }, ClickCallback.Options.builder().uses(1).build())
                    )
                )).build()
            )
        }
        viewer.showDialog(dialog)
    }

    fun ruleToggleDialog(
        viewer: Player,
        ruleName: String,
        currentValue: String,
        onSelect: (String) -> Unit
    ) {
        val isBlock = ruleName.equals("blockActions", ignoreCase = true)
        val titleText = if (isBlock) "Block Actions" else "Entity Actions"
        val bodyText = if (isBlock) "When should block actions be allowed for other players in your claim?" else "When should entity actions be allowed for other players in your claim?"
        val labelText = if (isBlock) "Blocks" else "Entities"

        val options = listOf(
            SingleOptionDialogInput.OptionEntry.create("always", Component.text("Always"), currentValue.equals("always", ignoreCase = true)),
            SingleOptionDialogInput.OptionEntry.create("interactOnly", Component.text("Interacting only"), currentValue.equals("interactOnly", ignoreCase = true)),
            SingleOptionDialogInput.OptionEntry.create("never", Component.text("Never"), currentValue.equals("never", ignoreCase = true))
        )

        val input = DialogInput.singleOption("value", 152, options, Component.text(labelText), false)

        val dialog = Dialog.create { b ->
            b.empty().base(
                DialogBase.builder(Component.text(titleText))
                    .body(listOf(DialogBody.plainMessage(Component.text(bodyText))))
                    .inputs(listOf(input))
                    .build()
            ).type(
                DialogType.multiAction(listOf(
                    ActionButton.create(Component.text("Cancel"), null, 75, DialogAction.customClick({ _, _ -> }, ClickCallback.Options.builder().uses(1).build())),
                    ActionButton.create(Component.text("Confirm"), null, 75, DialogAction.customClick({ view, _ ->
                        val selected = view.getText("value") ?: return@customClick
                        onSelect(selected)
                    }, ClickCallback.Options.builder().uses(1).build()))
                )).build()
            )
        }
        viewer.showDialog(dialog)
    }

    fun playerHasNoClaims(viewer: Player, name: String) {
        val cropped = name.take(16)
        viewer.showDialog(errorDialog(
            Component.text()
                .append(Component.text("Nothing changed, as "))
                .append(msg.head(cropped)).append(Component.text(" $cropped ", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("doesn't have any claims."))
                .build()
        ))
    }

    private fun playerNotFound(name: String): Dialog = errorDialog(
        Component.text()
            .append(Component.text("The player "))
            .append(msg.head(name)).append(Component.text(" $name ", NamedTextColor.WHITE, TextDecoration.BOLD))
            .append(Component.text("wasn't found. "))
            .append(Component.text("They must join once before they can be interacted with.", NamedTextColor.GRAY))
            .build()
    )

    /**
     * Template used by information dialogs
     */
    private fun infoDialog(body: Component): Dialog = Dialog.create { b ->
        b.empty().base(
            DialogBase.builder(Component.text("Just letting you know..."))
                .body(listOf(DialogBody.plainMessage(body)))
                .build()
        ).type(
            DialogType.multiAction(listOf(ActionButton.builder(Component.text("Ok")).build())).build()
        )
    }

    /**
     * Template used by other error-related dialogs
     */
    private fun errorDialog(body: Component): Dialog = Dialog.create { b ->
        b.empty().base(
            DialogBase.builder(Component.text("That didn't work..."))
                .body(listOf(DialogBody.plainMessage(body)))
                .build()
        ).type(
            DialogType.multiAction(listOf(ActionButton.builder(Component.text("Ok")).build())).build()
        )
    }
}