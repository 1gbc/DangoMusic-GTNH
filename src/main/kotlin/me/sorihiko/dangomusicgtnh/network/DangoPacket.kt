package me.sorihiko.dangomusicgtnh.network

import cpw.mods.fml.common.network.simpleimpl.IMessage
import io.netty.buffer.ByteBuf
import me.sorihiko.dangomusicgtnh.multiplayer.NearbyProtocol

/**
 * One small envelope for the whole protocol: a type id and a UTF-8 JSON payload.
 * Keeping the envelope common avoids loading client-only handler classes on a server.
 */
abstract class DangoMessage : IMessage {
    var type: Int = 0
    var json: String = ""

    override fun fromBytes(buf: ByteBuf) {
        type = buf.readInt()
        val length = buf.readInt()
        require(length in 0..NearbyProtocol.MAX_PACKET_BYTES) { "DangoMusic packet too large" }
        json = buf.readBytes(length).toString(Charsets.UTF_8)
    }

    override fun toBytes(buf: ByteBuf) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= NearbyProtocol.MAX_PACKET_BYTES) { "DangoMusic packet too large" }
        buf.writeInt(type)
        buf.writeInt(bytes.size)
        buf.writeBytes(bytes)
    }
}

class C2SMessage() : DangoMessage() {
    constructor(type: Int, json: String) : this() {
        this.type = type
        this.json = json
    }
}

class S2CMessage() : DangoMessage() {
    constructor(type: Int, json: String) : this() {
        this.type = type
        this.json = json
    }
}
