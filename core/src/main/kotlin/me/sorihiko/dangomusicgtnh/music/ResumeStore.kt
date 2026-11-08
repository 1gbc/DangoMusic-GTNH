package me.sorihiko.dangomusicgtnh.music

import com.google.gson.Gson
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.SharedTrack
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * 记住上次的播放队列、当前曲目和进度，下次进游戏恢复成“暂停在原处”（参考网易云桌面版）。
 *
 * 队列只在换了一份队列时写（可能有几千首）；进度是个很小的文件，换歌、暂停、播放中每 15 秒、
 * 退出游戏时各写一次。主动按停止会清掉进度，下次就不恢复。Apple Music 的队列不存（它由内嵌播放器自己管）。
 */
object ResumeStore {
    private class QueueFile(val version: Int = 1, val tracks: List<SharedTrack> = emptyList())
    private class StateFile(
        val version: Int = 1,
        val index: Int = -1,
        val key: String = "",
        val positionMs: Long = 0L,
        val savedAtMs: Long = 0L,
    )

    class Session(val tracks: List<NeteaseTrack>, val index: Int, val positionMs: Long)

    /** 太长的歌单只存当前曲目前后这一段，恢复后照样能上一首 / 下一首 */
    private const val MAX_TRACKS = 5000

    private val gson = Gson()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Resume").apply { isDaemon = true } }

    @Volatile var dir: Path? = null
    /** 上次写进文件的那份队列（按引用比较），队列没换就不重写 */
    @Volatile private var writtenQueue: List<NeteaseTrack>? = null
    @Volatile private var writtenOffset = 0
    @Volatile private var writtenCount = 0

    private fun base(): Path? = dir ?: try { me.sorihiko.dangomusicgtnh.DangoMusic.instance.clientDir } catch (_: Exception) { null }
    private fun queueFile() = base()?.resolve("resume-queue.json")
    private fun stateFile() = base()?.resolve("resume-state.json")

    private fun storable(queue: List<NeteaseTrack>) =
        queue.isNotEmpty() && queue.none { it.source == MusicSource.APPLE }

    /** 换歌、暂停、定时和退出时调用：队列换过（按引用）才重写队列文件，进度每次都写 */
    @Synchronized
    fun save(queue: List<NeteaseTrack>, index: Int, positionMs: Long, sync: Boolean = false) {
        if (!storable(queue) || index !in queue.indices) return
        // 队列换了，或者超长歌单里当前曲目移出了已存的那一段：重写队列
        if (writtenQueue !== queue || index - writtenOffset !in 0 until writtenCount) {
            val window = window(queue.size, index)
            writtenQueue = queue
            writtenOffset = window.first
            writtenCount = window.last - window.first + 1
            val tracks = queue.subList(window.first, window.last + 1).map(SharedTrack::from)
            write(queueFile(), gson.toJson(QueueFile(tracks = tracks)), sync)
        }
        val local = index - writtenOffset
        val state = StateFile(index = local, key = queue[index].key, positionMs = positionMs.coerceAtLeast(0L), savedAtMs = System.currentTimeMillis())
        write(stateFile(), gson.toJson(state), sync)
    }

    /** 恢复后这份队列就是文件里那份，不用马上重写 */
    @Synchronized
    fun adopt(queue: List<NeteaseTrack>) {
        writtenQueue = queue
        writtenOffset = 0
        writtenCount = queue.size
    }

    /** 用户主动停止：下次进游戏不恢复 */
    fun clear() {
        write(stateFile(), gson.toJson(StateFile(savedAtMs = System.currentTimeMillis())))
    }

    fun load(): Session? = try {
        val q = queueFile()?.takeIf { Files.isRegularFile(it) } ?: return null
        val s = stateFile()?.takeIf { Files.isRegularFile(it) } ?: return null
        val queue = gson.fromJson(String(Files.readAllBytes(q), StandardCharsets.UTF_8), QueueFile::class.java)
        val state = gson.fromJson(String(Files.readAllBytes(s), StandardCharsets.UTF_8), StateFile::class.java)
        val tracks = queue?.tracks.orEmpty().mapNotNull { runCatching { it.toTrack() }.getOrNull() }
        val index = state?.index ?: -1
        if (state == null || state.key.isEmpty() || index !in tracks.indices || tracks[index].key != state.key) null
        else Session(tracks, index, state.positionMs)
    } catch (e: Exception) {
        DangoLogger.warn("Resume state unreadable: {}", e.javaClass.simpleName)
        null
    }

    private fun window(size: Int, index: Int): IntRange {
        if (size <= MAX_TRACKS) return 0 until size
        val start = (index - MAX_TRACKS / 2).coerceIn(0, size - MAX_TRACKS)
        return start until start + MAX_TRACKS
    }

    private fun write(target: Path?, json: String, sync: Boolean = false) {
        target ?: return
        val task = Runnable {
            try {
                Files.createDirectories(target.parent)
                val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
                Files.write(tmp, json.toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                DangoLogger.warn("Resume state not saved: {}", e.javaClass.simpleName)
            }
        }
        if (!sync) {
            writer.execute(task)
            return
        }
        // 退出时：排在已经提交的异步写入后面，保证最后落盘的是最终进度；写线程不可用就直接写
        try {
            writer.submit(task).get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            task.run()
        }
    }
}
