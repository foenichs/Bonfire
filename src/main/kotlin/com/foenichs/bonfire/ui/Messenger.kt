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
            Component.text().append(Component.text(" You can't claim chunks yet. "))
                .append(Component.text("Claim limits increase depending on your playtime.", NamedTextColor.GRAY))
                .build()
        )
    }
}