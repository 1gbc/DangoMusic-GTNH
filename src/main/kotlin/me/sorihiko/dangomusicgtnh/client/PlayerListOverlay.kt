package me.sorihiko.dangomusicgtnh.client

import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.network.NetHandlerPlayClient
import net.minecraft.scoreboard.ScorePlayerTeam

internal object PlayerListOverlay {
    private val modernTab by lazy {
        try {
            val config = Class.forName("serverutils.ServerUtilitiesConfig")
            val mixins = config.getField("mixins").get(null)
            if (mixins.javaClass.getField("modernTabOverlay").getBoolean(mixins)) {
                ModernTab(config.getField("tab").get(null))
            } else null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun bounds(resolution: ScaledResolution): LayoutBounds? {
        val client = Minecraft.getMinecraft()
        val handler = client.thePlayer?.sendQueue ?: return null
        val width = resolution.scaledWidth
        val modern = modernTab
        val bounds = if (modern != null) {
            modern.bounds(client, handler, width) ?: return null
        } else {
            // Forge 1.7.10 lays out all advertised slots, including empty ones.
            val slots = handler.currentServerMaxPlayers
            val columns = ((slots + 19) / 20).coerceAtLeast(1)
            val rows = (slots + columns - 1) / columns
            val gridWidth = columns * minOf(300 / columns, 150)
            LayoutBounds(((width - gridWidth) / 2 - 1).toFloat(), 9f, gridWidth + 1f, rows * 9f + 1f)
        }
        val scale = resolution.scaleFactor
        return LayoutBounds(bounds.x * scale, bounds.y * scale, bounds.width * scale, bounds.height * scale)
    }

    // ServerUtilities supplies the modern layout in GTNH 2.9.0. Its Forge event
    // exposes no rectangle, so estimate its painted background from public tab data.
    private class ModernTab(private val tab: Any) {
        private val heads = tab.javaClass.getField("showPlayerHeads")
        private val pingBars = tab.javaClass.getField("showPingBars")
        private val pingNumber = tab.javaClass.getField("showPingNumber")
        private val headerText = tab.javaClass.getField("headerText")
        private val footerText = tab.javaClass.getField("footerText")
        private val channelType = Class.forName("serverutils.client.tab.TabChannelHandler")
        private val channel = channelType.getField("INSTANCE").get(null)
        private val hasServerData = channelType.getMethod("hasServerData")
        private val header = channelType.getMethod("getHeader")
        private val footer = channelType.getMethod("getFooter")
        private val displayType = Class.forName("serverutils.client.tab.TabDisplayHandler")
        private val display = displayType.getField("INSTANCE").get(null)
        private val name = displayType.getMethod("getDisplayName", String::class.java)
        private val suffix = displayType.getMethod("getSuffix", String::class.java)
        private val wrapper = Class.forName("serverutils.client.gui.misc.GuiPlayerInfoWrapper")
        private val wrappedName = wrapper.getField("displayName")
        private val formatting = Class.forName("serverutils.lib.util.StringUtils")
            .getMethod("addFormatting", String::class.java)

        fun bounds(client: Minecraft, handler: NetHandlerPlayClient, width: Int): LayoutBounds? {
            val font = client.fontRenderer
            val scoreboard = client.theWorld?.scoreboard ?: return null
            val objective = scoreboard.func_96539_a(0)
            val players = handler.playerInfoList
            val count = minOf(players.size, 80)
            if (count == 0 && objective == null) return null
            val showNumber = pingNumber.getBoolean(tab)
            var nameWidth = 0
            var scoreWidth = 0
            var suffixWidth = 0
            var numberWidth = 0
            for (i in 0 until count) {
                val player = players[i]
                val username = player.name
                val displayName = name.invoke(display, username) as String?
                    ?: (if (wrapper.isInstance(player)) wrappedName.get(player) as String? else null)
                    ?: ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(username), username)
                nameWidth = maxOf(nameWidth, font.getStringWidth(displayName))
                if (objective != null) {
                    val score = scoreboard.func_96529_a(username, objective).scorePoints
                    scoreWidth = maxOf(scoreWidth, font.getStringWidth(score.toString()))
                }
                val suffixText = suffix.invoke(display, username) as String?
                if (suffixText != null) suffixWidth = maxOf(suffixWidth, font.getStringWidth(suffixText))
                if (showNumber) {
                    val ping = player.responseTime
                    numberWidth = maxOf(numberWidth, font.getStringWidth(if (ping < 0) "?" else "${ping}ms"))
                }
            }

            val columns = ((count + 19) / 20).coerceAtLeast(1)
            val rows = (count + columns - 1) / columns
            val entryWidth = 3 + nameWidth +
                (if (heads.getBoolean(tab)) 9 else 0) + (if (pingBars.getBoolean(tab)) 11 else 0) +
                (if (showNumber) numberWidth + 2 else 0) +
                (if (objective != null) scoreWidth + 5 else 0) + (if (suffixWidth > 0) suffixWidth + 5 else 0)
            val availableWidth = (width - 50).coerceAtLeast(1)
            val columnWidth = minOf(columns * entryWidth, availableWidth) / columns
            var gridWidth = columnWidth * columns + (columns - 1) * 5
            var bottom = 10 + rows * 9
            val serverText = hasServerData.invoke(channel) == true
            for (text in listOf(
                if (serverText) header.invoke(channel) as String? else headerText.get(tab) as String?,
                if (serverText) footer.invoke(channel) as String? else footerText.get(tab) as String?,
            )) {
                if (text.isNullOrEmpty()) continue
                val formatted = formatting.invoke(null, text.replace("\\n", "\n")) as String
                val lines = formatted.split('\n').flatMap { line ->
                    if (line.isEmpty()) listOf("") else font.listFormattedStringToWidth(line, availableWidth)
                }
                if (lines.isNotEmpty()) {
                    gridWidth = maxOf(gridWidth, lines.maxOf(font::getStringWidth))
                    bottom += lines.size * font.FONT_HEIGHT + 1
                }
            }
            val half = gridWidth / 2 + 1
            return LayoutBounds((width / 2 - half).toFloat(), 9f, half * 2f, bottom - 9f)
        }
    }
}
