package me.sorihiko.dangomusicgtnh.network

import com.google.gson.GsonBuilder
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

data class MusicGroup(
    val id: String,
    val name: String,
    var host: UUID,
    val members: LinkedHashSet<UUID> = LinkedHashSet(),
    /** Last known display names, persisted so offline members/owners still have a name. */
    val names: MutableMap<UUID, String> = LinkedHashMap(),
)

data class GroupLeaveResult(
    val group: MusicGroup,
    val newHost: UUID?,
    val disbanded: Boolean,
)

/**
 * In-memory group membership with a small JSON store in the server config directory.
 * A player may be in several groups; the active one is a client preference.
 * Logging out never removes membership; only an explicit leave does.
 */
object MusicGroups {
    private val groups = LinkedHashMap<String, MusicGroup>()
    private var file: File? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private data class StoredGroup(
        val id: String = "",
        val name: String = "",
        val host: String = "",
        val members: List<String>? = null,
        val names: Map<String, String>? = null,
    )

    private data class Snapshot(val groups: List<StoredGroup>? = null)

    @Synchronized
    fun attach(storeFile: File) {
        file = storeFile
        load()
    }

    @Synchronized
    fun groupsOf(player: UUID): List<MusicGroup> = groups.values.filter { player in it.members }

    @Synchronized
    fun groupOf(player: UUID): MusicGroup? = groupsOf(player).firstOrNull()

    @Synchronized
    fun groupById(id: String): MusicGroup? = if (id.isBlank()) null else groups[id]

    @Synchronized
    fun create(host: UUID, hostName: String, rawName: String, maxMembers: Int): String? {
        val name = clean(rawName) ?: return "群组名需要 1–24 个字符"
        if (groups.values.any { it.name.equals(name, ignoreCase = true) }) return "同名群组已存在"
        val group = MusicGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            host = host,
        )
        group.members += host
        group.names[host] = hostName
        groups[group.id] = group
        persist()
        return null
    }

    @Synchronized
    fun join(player: UUID, playerName: String, rawName: String, maxMembers: Int): String? {
        val name = clean(rawName) ?: return "群组名不合法"
        val group = groups.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return "找不到群组：$name"
        if (player in group.members) return "你已在该群组中"
        if (group.members.size >= maxMembers) return "群组已满（$maxMembers 人）"
        group.members += player
        group.names[player] = playerName
        persist()
        return null
    }

    /**
     * Leave one group. If the owner leaves, ownership is transferred to the next
     * remaining member; an empty group is deleted. A blank [ref] only works when
     * the player is in exactly one group.
     */
    @Synchronized
    fun leave(player: UUID, ref: String?): GroupLeaveResult? {
        val group = resolveGroup(player, ref) ?: return null
        group.members.remove(player)
        group.names.remove(player)
        var newHost: UUID? = null
        var disbanded = false
        if (group.host == player) {
            val next = group.members.firstOrNull()
            if (next == null) {
                groups.remove(group.id)
                disbanded = true
            } else {
                group.host = next
                newHost = next
            }
        }
        persist()
        return GroupLeaveResult(group, newHost, disbanded)
    }

    @Synchronized
    fun updateName(player: UUID, playerName: String) {
        var changed = false
        for (group in groups.values) {
            if (player in group.members && group.names[player] != playerName) {
                group.names[player] = playerName
                changed = true
            }
        }
        if (changed && file != null) persist()
    }

    @Synchronized
    fun view(group: MusicGroup?, nameOf: (UUID) -> String, idle: Boolean, speaker: UUID? = null): GroupView? {
        group ?: return null
        return GroupView(
            id = group.id,
            name = group.name,
            host = nameOf(group.host),
            hostUuid = group.host.toString(),
            speaker = speaker?.let(nameOf).orEmpty(),
            speakerUuid = speaker?.toString().orEmpty(),
            members = group.members.map(nameOf),
            idle = idle,
        )
    }

    @Synchronized
    fun all(): List<MusicGroup> = groups.values.toList()

    private fun resolveGroup(player: UUID, ref: String?): MusicGroup? {
        val owned = groups.values.filter { player in it.members }
        if (ref.isNullOrBlank()) return owned.singleOrNull()
        return owned.firstOrNull {
            it.id == ref || it.name.equals(ref.trim(), ignoreCase = true)
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val snapshot = gson.fromJson(f.readText(StandardCharsets.UTF_8), Snapshot::class.java) ?: return
            groups.clear()
            for (stored in snapshot.groups.orEmpty().take(128)) {
                val id = stored.id.trim()
                val name = clean(stored.name) ?: continue
                if (id.isEmpty() || groups.containsKey(id)) continue
                if (groups.values.any { it.name.equals(name, ignoreCase = true) }) continue
                val host = runCatching { UUID.fromString(stored.host) }.getOrNull() ?: continue
                val members = LinkedHashSet<UUID>()
                for (value in stored.members.orEmpty()) {
                    runCatching { UUID.fromString(value) }.getOrNull()?.let { members += it }
                }
                members += host
                val names = LinkedHashMap<UUID, String>()
                for ((key, value) in stored.names.orEmpty()) {
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                    if (uuid in members) names[uuid] = value.take(64)
                }
                names.putIfAbsent(host, host.toString().take(8))
                for (member in members) names.putIfAbsent(member, member.toString().take(8))
                groups[id] = MusicGroup(id, name, host, members, names)
            }
            DangoLogger.info("DangoMusic groups loaded: {}", groups.size)
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic groups load failed: {}", e.toString())
        }
    }

    private fun persist() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val snapshot = Snapshot(groups.values.map { group ->
                StoredGroup(
                    id = group.id,
                    name = group.name,
                    host = group.host.toString(),
                    members = group.members.map(UUID::toString),
                    names = group.names.mapKeys { it.key.toString() },
                )
            })
            val tmp = File(f.parentFile, f.name + ".tmp")
            Files.write(tmp.toPath(), gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic groups save failed: {}", e.toString())
        }
    }

    private fun clean(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 1..24) return null
        if (name.any { it.isISOControl() }) return null
        return name
    }
}
