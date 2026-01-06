package com.foenichs.bonfire.command

import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component

class BonfireCommand(private val onReload: () -> Unit) {
    fun register(registrar: Commands) {
        val node = Commands.literal("bonfire")
            .requires { it.sender.isOp }
            .then(Commands.literal("reload")
                .executes { ctx ->
                    onReload()
                    ctx.source.sender.sendMessage(Component.text("Successfully reloaded Bonfire's config!"))
                    1
                }
            )

        registrar.register(node.build(), "Bonfire's management command. Operator-only.")
    }
}