package me.sorihiko.dangomusicgtnh.network

import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import java.util.LinkedHashSet
import java.util.UUID

data class MusicGroup(
    val id: String,
    val name: String,
    var host: UUID,
    val members: LinkedHashSet<UUID> = LinkedHashSet(),
)

/** In-memory groups; a restart dissolves them, which is intentional for v1. */
object MusicGroups {
    private val groups = LinkedHashMap<String, MusicGroup>()

    @Synchronized
    fun groupOf(player: UUID): MusicGroup? = groups.values.firstOrNull { player in it.members }

    @Synchronized
    fun create(host: UUID, rawName: String, maxMembers: Int): String? {
        val name = clean(rawName) ?: return "群组名需要 1–24 个字符"
        if (groupOf(host) != null) return "你已经在一个音乐群组里"
        if (groups.values.any { it.name.equals(name, ignoreCase = true) }) return "同名群组已存在"
        val group = MusicGroup(UUID.randomUUID().toString(), name, host)
        group.members += host
        groups[group.id] = group
        return null
    }

    @Synchronized
    fun join(player: UUID, rawName: String, maxMembers: Int): String? {
        val name = clean(rawName) ?: return "群组名不合法"
        val group = groups.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return "找不到群组：$name"
        if (groupOf(player) != null) return "你已经在一个音乐群组里"
        if (group.members.size >= maxMembers) return "群组已满（$maxMembers 人）"
        group.members += player
        return null
    }

    @Synchronized
    fun leave(player: UUID): MusicGroup? {
        val group = groupOf(player) ?: return null
        if (group.host == player) {
            groups.remove(group.id)
        } else {
            group.members.remove(player)
        }
        return group
    }

    @Synchronized
    fun view(group: MusicGroup?, nameOf: (UUID) -> String, idle: Boolean): GroupView? {
        group ?: return null
        return GroupView(
            id = group.id,
            name = group.name,
            host = nameOf(group.host),
            hostUuid = group.host.toString(),
            members = group.members.map(nameOf),
            idle = idle,
        )
    }

    @Synchronized
    fun all(): List<MusicGroup> = groups.values.toList()

    private fun clean(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 1..24) return null
        if (name.any { it.isISOControl() }) return null
        return name
    }
}
