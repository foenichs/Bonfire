package com.foenichs.bonfire.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
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
        viewer.showDialog(infoDialog(
            Component.text("The chunk you're currently in is claimed by ")
                .append(msg.head(cropped))
                .append(Component.text(" $cropped").decorate(TextDecoration.BOLD).append(Component.text(".")))
        ))
    }

    fun chunkNotClaimed(viewer: Player) {
        viewer.showDialog(errorDialog(
            Component.text("The chunk you're currently in isn't claimed by anyone.")
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
        viewer.showDialog(errorDialog(
            Component.text("You can't claim this chunk.")
                .append(Component.text(" You have either reached your claim limit or you haven't earned any claims yet.", NamedTextColor.GRAY))
        ))
    }

    fun nothingChanged(viewer: Player, reason: String) {
        viewer.showDialog(errorDialog(Component.text("Nothing changed, as $reason")))
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