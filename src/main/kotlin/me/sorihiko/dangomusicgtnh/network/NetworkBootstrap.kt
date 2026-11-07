package me.sorihiko.dangomusicgtnh.network

import com.google.gson.Gson
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.network.NetworkRegistry
import cpw.mods.fml.common.network.simpleimpl.IMessage
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler
import cpw.mods.fml.common.network.simpleimpl.MessageContext
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper
import cpw.mods.fml.relauncher.Side
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.NearbyProtocol
import net.minecraft.entity.player.EntityPlayerMP

/**
 * The channel exists on both sides. The handler for the direction a side never receives
 * is a no-op stub, so a dedicated server never loads client classes (and vice versa).
 */
object NetworkBootstrap {
    val gson = Gson()

    private var initialized = false
    private var channel: SimpleNetworkWrapper? = null

    @JvmStatic
    fun init() {
        if (initialized) return
        initialized = true
        val ch = NetworkRegistry.INSTANCE.newSimpleChannel(NearbyProtocol.CHANNEL)
        channel = ch
        val server = FMLCommonHandler.instance().side == Side.SERVER
        ch.registerMessage(
            if (server) C2SHandler::class.java else C2SDummyHandler::class.java,
            C2SMessage::class.java,
            1,
            Side.SERVER
        )
        ch.registerMessage(
            if (server) S2CDummyHandler::class.java else S2CHandler::class.java,
            S2CMessage::class.java,
            2,
            Side.CLIENT
        )
    }

    private fun fits(json: String): Boolean {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size <= NearbyProtocol.MAX_PACKET_BYTES) return true
        DangoLogger.warn("DangoMusic packet dropped: {} bytes (limit {})", size, NearbyProtocol.MAX_PACKET_BYTES)
        return false
    }

    fun sendToServer(type: Int, payload: Any) {
        val ch = channel ?: return
        val json = gson.toJson(payload)
        if (!fits(json)) return
        try {
            ch.sendToServer(C2SMessage(type, json))
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic client packet {} send failed: {}", type, e.javaClass.simpleName)
        }
    }

    fun sendTo(player: EntityPlayerMP, type: Int, payload: Any) {
        val json = gson.toJson(payload)
        sendJsonTo(player, type, json)
    }

    fun sendJsonTo(player: EntityPlayerMP, type: Int, json: String) {
        val ch = channel ?: return
        if (!fits(json)) return
        try {
            ch.sendTo(S2CMessage(type, json), player)
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic server packet {} send failed: {}", type, e.javaClass.simpleName)
        }
    }
}

class C2SHandler : IMessageHandler<C2SMessage, IMessage?> {
    override fun onMessage(message: C2SMessage, ctx: MessageContext): IMessage? {
        val player = ctx.serverHandler.playerEntity ?: return null
        NearbyServer.handle(player, message.type, message.json)
        return null
    }
}

class S2CHandler : IMessageHandler<S2CMessage, IMessage?> {
    override fun onMessage(message: S2CMessage, ctx: MessageContext): IMessage? {
        NearbyClient.handle(message.type, message.json)
        return null
    }
}

class C2SDummyHandler : IMessageHandler<C2SMessage, IMessage?> {
    override fun onMessage(message: C2SMessage, ctx: MessageContext): IMessage? = null
}

class S2CDummyHandler : IMessageHandler<S2CMessage, IMessage?> {
    override fun onMessage(message: S2CMessage, ctx: MessageContext): IMessage? = null
}
