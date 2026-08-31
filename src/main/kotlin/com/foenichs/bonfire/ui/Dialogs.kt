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
