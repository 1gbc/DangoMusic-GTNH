package me.sorihiko.dangomusicgtnh.network

import net.minecraft.command.CommandBase
import net.minecraft.command.CommandException
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.util.ChatComponentText

/**
 * Server-side control surface. The player-facing toggles live in the mod's settings UI;
 * these commands cover group membership and operator checks.
 */
class CommandDangoMusic : CommandBase() {

    override fun getCommandName(): String = "dangomusic"

    override fun getCommandUsage(sender: ICommandSender?): String =
        "/dangomusic group create <name> | join <name> | leave | info | status | reload"

    override fun getRequiredPermissionLevel(): Int = 0

    @Throws(CommandException::class)
    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        if (args.isEmpty()) {
            reply(sender, getCommandUsage(sender))
            return
        }
        when (args[0].lowercase()) {
            "group" -> group(sender, args)
            "status" -> reply(sender, NearbyServer.statusText())
            "reload" -> {
                if (!sender.canCommandSenderUseCommand(2, "dangomusic")) {
                    throw CommandException("需要 OP 权限")
                }
                ServerConfig.reload()
                reply(sender, "DangoMusic 服务端配置已重载")
            }
            else -> reply(sender, getCommandUsage(sender))
        }
    }

    private fun group(sender: ICommandSender, args: Array<String>) {
        val player = sender as? EntityPlayerMP
        if (player == null) {
            reply(sender, "该命令只能在游戏内使用")
            return
        }
        val sub = args.getOrNull(1)?.lowercase() ?: run {
            reply(sender, getCommandUsage(sender))
            return
        }
        when (sub) {
            "create" -> {
                val name = args.getOrNull(2)
                if (name == null) {
                    reply(sender, "用法：/dangomusic group create <name>")
                    return
                }
                reply(sender, NearbyServer.groupCreate(player, name) ?: "已创建音乐群组：$name")
            }
            "join" -> {
                val name = args.getOrNull(2)
                if (name == null) {
                    reply(sender, "用法：/dangomusic group join <name>")
                    return
                }
                reply(sender, NearbyServer.groupJoin(player, name) ?: "已加入音乐群组：$name")
            }
            "leave" -> reply(sender, NearbyServer.groupLeave(player) ?: "已离开音乐群组")
            "info" -> reply(sender, NearbyServer.groupInfo(player))
            else -> reply(sender, getCommandUsage(sender))
        }
    }

    private fun reply(sender: ICommandSender, text: String) {
        sender.addChatMessage(ChatComponentText(text))
    }
}
