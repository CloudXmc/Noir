package moe.meowrealms.noir.command

import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.model.ModelManager
import moe.meowrealms.noir.network.ClientConnectionManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class NoirTabCompleter : TabCompleter {
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> = when (args.size) {
        1 -> availableSubcommands(sender)
            .filter { it.startsWith(args[0], ignoreCase = true) }
            .sorted()

        2 -> if (args[0].equals("setmodel", true)
            && sender.hasPermission(NoirConstants.PermissionConstants.SET_MODEL_COMMAND)) {
            Bukkit.getOnlinePlayers().asSequence().map { it.name }
                .filter { it.startsWith(args[1], true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
        } else if (args[0].equals("kickysm", true)
            && sender.hasPermission(NoirConstants.PermissionConstants.KICK_YSM_COMMAND)) {
            ClientConnectionManager.getOnlineYsmPlayerNames().filter { it.startsWith(args[1], true) }
        } else emptyList()

        3 -> if (args[0].equals("setmodel", true)
            && sender.hasPermission(NoirConstants.PermissionConstants.SET_MODEL_COMMAND)) {
            ModelManager.getModelIds().asSequence()
                .filter { it.startsWith(args[2], true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
        } else emptyList()

        4 -> if (args[0].equals("setmodel", true)
            && sender.hasPermission(NoirConstants.PermissionConstants.SET_MODEL_COMMAND)) {
            ModelManager.getTexturesOf(args[2]).asSequence()
                .filter { it.startsWith(args[3], true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
        } else emptyList()

        else -> emptyList()
    }

    private fun availableSubcommands(sender: CommandSender): List<String> {
        val result = mutableListOf("help")
        if (sender.hasPermission(NoirConstants.PermissionConstants.RELOAD_MODELS_COMMAND)) result.add("reload")
        if (sender.hasPermission(NoirConstants.PermissionConstants.SET_MODEL_COMMAND)) result.add("setmodel")
        if (sender.hasPermission(NoirConstants.PermissionConstants.LIST_PLAYERS_COMMAND)) result.add("players")
        if (sender.hasPermission(NoirConstants.PermissionConstants.BROADCAST_COMMAND)) result.add("broadcast")
        if (sender.hasPermission(NoirConstants.PermissionConstants.KICK_YSM_COMMAND)) result.add("kickysm")
        return result
    }
}
