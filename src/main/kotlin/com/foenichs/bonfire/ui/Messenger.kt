package com.foenichs.bonfire.ui

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.`object`.ObjectContents
import org.bukkit.entity.Player

class Messenger {

    /**
     * The central campfire icon with hover text
     */
    private fun icon() = Component.`object`(ObjectContents.sprite(Key.key("items"), Key.key("item/campfire")))
        .hoverEvent(HoverEvent.showText(Component.text("Bonfire")))

    /**
     * A component representing a player's head
     */
    fun head(name: String) = Component.`object`(ObjectContents.playerHead(name))

    /**
     * Sends a formatted message with vertical padding and the plugin icon
     */
    fun send(p: Player, content: Component) {
        p.sendMessage(
            Component.text().append(Component.newline()).append(icon()).append(Component.space()).append(content)
                .append(Component.newline()).build()
        )
    }

    /**
     * Displays the current chunk owner in the action bar
     */
    fun actionBar(p: Player, ownerName: String) {
        p.sendActionBar(
            Component.text().append(head(ownerName)).append(Component.space()).append(Component.text(ownerName)).build()
        )
    }

    /**
     * Displays the unclaimed status in the action bar
     */
    fun unclaimedBar(p: Player) {
        p.sendActionBar(
            Component.text().append(icon()).append(Component.space())
                .append(Component.text("Unclaimed", NamedTextColor.WHITE)).build()
        )
    }

    /**
     * Sends the error message for insufficient playtime/limits
     */
    fun sendNoAccess(p: Player) {
        send(
            p,
            Component.text().append(Component.text("You don't have access to do that right now.")).build()
        )
    }

    /**
     * Formats a duration in seconds as a readable string, e.g. "1 hour and 2 minutes"
     */
    fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600; val minutes = (s % 3600) / 60; val seconds = s % 60

        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours hour" + if (hours != 1L) "s" else "")
        if (minutes > 0) parts.add("$minutes minute" + if (minutes != 1L) "s" else "")
        if (seconds > 0 || parts.isEmpty()) parts.add("$seconds second" + if (seconds != 1L) "s" else "")

        return when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> "${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}"
        }
    }
}