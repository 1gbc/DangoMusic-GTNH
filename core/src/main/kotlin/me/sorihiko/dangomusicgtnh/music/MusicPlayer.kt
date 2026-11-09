package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.DangoLogger

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出（安卓等不带声音库的 Java 改用游戏的 OpenAL，见 [AudioOutput]）。
 * 播放进度 = 起始偏移 + 声卡实际已经播放的时长，所以歌词同步是精确的，拖动进度条也没问题。
 */
object MusicPlayer {

    enum class State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var current: NeteaseTrack? = null
        private set
    @Volatile var message: String = ""
        private set
    @Volatile var neteaseQuality = NeteaseQuality.LOSSLESS
        private set
    @Volatile var neteaseStream: NeteaseStream? = null
        private set

    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var index = -1
        private set

    @Volatile var shuffle = false
    /** 歌曲自然播完时重播当前曲目；手动切歌仍按队列 / Shuffle 工作。 */
    @Volatile var repeatOne = false
    /** 0..1 */
    @Volatile var volume = 0.2f
    @Volatile var playbackFades = true

    /** 声音从哪里放：0 自动（安卓用游戏的 OpenAL，电脑用 Java Sound），1 Java Sound，2 游戏的 OpenAL。换了就从当前位置重新开始放 */
    @Volatile var outputMode = OUTPUT_AUTO
        set(v) {
            if (field == v) return
            field = v
            if (state == State.PLAYING) seekLocal(positionMs)
        }

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }
    private val radioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicRadio").apply { isDaemon = true } }
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicPrefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /**
     * 开着淡入淡出时，声卡里最多排多少毫秒还没放的音频。越大越不容易被 GC / 卡顿打断，
     * 但暂停、切歌的淡出要等排队的这段放完才开始。
     */
    @Volatile var audioBufferMs = 400

    /** 预取好的下一首（网易云）：地址、整首音频和分析结果；切到它时直接解码，不再等下载 */
    private class Prepared(
        val key: String, val quality: NeteaseQuality, val normalized: Boolean, val url: NeteaseApi.SongUrl,
        val bytes: ByteArray, val measuredDuration: Long, val measuredGain: Float?, val atMs: Long,
    )
    @Volatile private var prepared: Prepared? = null
    /** 正在预取的曲目；换成别的值（或 null）就让进行中的预取停下 */
    @Volatile private var prefetchKey: String? = null
    private val decoderWorker = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        // 解码线程优先级略高于游戏的后台线程：GTNH 负载重时少断音
        { r -> Thread(r, "Dango-MusicDecode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2 } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    /** 电台式的队列（私人 FM）：快放完时用它再要几首接在后面；普通歌单是 null */
    @Volatile var radio: (() -> List<NeteaseTrack>)? = null
        private set

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: FadingAudioOutput? = null
    @Volatile private var seekBaseMs = 0L
    @Volatile private var pausedAtMs = 0L
    @Volatile private var loadingPosition = 0L
    private var loadingPaused = false

    val durationMs: Long get() = current?.durationMs ?: 0L

    // ---------------------------------------------------------------- 鼓点检测（文闪用）

    /** (歌曲内时间 ms, 强度 0..1)，解码时根据低频能量突增检测出来 */
    private class Beat(val timeMs: Long, val strength: Float)
    private val beats = ArrayDeque<Beat>()

    /**
     * 当前的"鼓点冲击"强度：最近一个鼓点之后迅速衰减，0..1。
     * posMs 用歌曲内时间（和歌词同一条时间线）。
     */
    fun beatPulse(posMs: Long): Float {
        var best = 0f
        synchronized(beats) {
            for (b in beats) {
                val dt = posMs - b.timeMs
                if (dt < 0 || dt > 600) continue
                best = max(best, b.strength * kotlin.math.exp(-dt / 110f))
            }
        }
        return best
    }

    private fun clearBeats() = synchronized(beats) { beats.clear() }

    /** 最近检测到的鼓点：[0] 时间（歌曲内 ms），[1] 强度 0..1，从早到晚 */
    fun beatSnapshot(): Array<FloatArray> = synchronized(beats) {
        Array(beats.size) { val b = beats.elementAt(it); floatArrayOf(b.timeMs.toFloat(), b.strength) }
    }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> if (current?.source == MusicSource.APPLE) applePosition else seekBaseMs + (line?.positionMicros ?: 0L) / 1000
            State.PAUSED -> pausedAtMs
            State.LOADING -> loadingPosition
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    enum class Mode(val label: String, val glyph: String) {
        // 顺序播放到最后一首会回到第一首（列表循环）：用循环箭头，不再和播放队列的 ≡ 撞图标；单曲循环另外叠一个“1”
        SEQUENTIAL("Sequential playback", "↻"),
        SHUFFLE("Shuffle playback", "⇄"),
        REPEAT_ONE("Repeat one", "↻"),
    }

    private var originalQueue: List<NeteaseTrack> = emptyList()
    @Volatile private var heartMode = false

    val mode: Mode get() = when {
        heartMode -> Mode.SEQUENTIAL
        repeatOne -> Mode.REPEAT_ONE
        shuffle -> Mode.SHUFFLE
        else -> Mode.SEQUENTIAL
    }

    @Synchronized fun selectMode(mode: Mode) {
        if (!heartMode && this.mode == mode) return
        val selected = queue.getOrNull(index)
        heartMode = false
        shuffle = mode == Mode.SHUFFLE
        repeatOne = mode == Mode.REPEAT_ONE
        if (repeatOne || radio != null || originalQueue.isEmpty()) return

        if (shuffle && selected != null) {
            val remaining = originalQueue.toMutableList()
            if (remaining.remove(selected)) {
                queue = listOf(selected) + remaining.shuffled()
                index = 0
                return
            }
        }
        queue = if (shuffle) originalQueue.shuffled() else originalQueue
        index = queue.indexOf(selected)
    }

    @Synchronized fun startHeartMode(
        tracks: List<NeteaseTrack>,
        expectedQueue: List<NeteaseTrack>,
        expectedTrack: NeteaseTrack?,
    ) {
        if (tracks.isEmpty() || queue !== expectedQueue || current !== expectedTrack ||
            pendingFade?.pausing == false) return
        originalQueue = tracks
        radio = null
        heartMode = true
        queue = tracks
        playIndex(0)
    }

    @Synchronized @JvmOverloads fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int, radio: (() -> List<NeteaseTrack>)? = null) {
        heartMode = false
        originalQueue = tracks
        this.radio = radio
        if (shuffle && !repeatOne && radio == null && tracks.isNotEmpty()) {
            val selected = tracks[((startIndex % tracks.size) + tracks.size) % tracks.size]
            queue = listOf(selected) + tracks.toMutableList().apply { removeAt(((startIndex % size) + size) % size) }.shuffled()
            playIndex(0)
        } else {
            queue = tracks
            playIndex(startIndex)
        }
    }

    @Synchronized fun playAll(tracks: List<NeteaseTrack>, radio: (() -> List<NeteaseTrack>)? = null) {
        if (tracks.isEmpty()) return
        val start = if (shuffle && !repeatOne && radio == null) tracks.indices.random() else 0
        playQueue(tracks, start, radio)
    }

    @Synchronized fun playShuffled(tracks: List<NeteaseTrack>) {
        if (tracks.isEmpty()) return
        heartMode = false
        originalQueue = tracks
        radio = null
        queue = tracks.shuffled()
        playIndex(0)
    }

    private val failedTracks = HashSet<String>()
    private val failureTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Dango-Skip").apply { isDaemon = true } }

    /** 退出游戏时不要把“停止”当成用户主动停止而清掉续播进度 */
    @Volatile private var shuttingDown = false

    init {
        // 播放中每 15 秒记一次进度，崩溃或强退也最多丢 15 秒
        failureTimer.scheduleWithFixedDelay({
            try {
                synchronized(this) {
                    if (state == State.PLAYING && current?.source != MusicSource.APPLE) ResumeStore.save(queue, index, positionMs)
                }
            } catch (_: Throwable) {
            }
        }, 15L, 15L, TimeUnit.SECONDS)
    }

    /**
     * 进游戏时恢复上次的队列和进度：停在暂停状态，不自动出声、不提前下载；
     * 按播放时才从记住的进度开始加载（和网易云桌面版一样）。
     */
    @Synchronized fun restoreSession(session: ResumeStore.Session): Boolean {
        if (current != null || state != State.STOPPED || session.index !in session.tracks.indices) return false
        val track = session.tracks[session.index]
        if (track.source == MusicSource.APPLE) return false
        queue = session.tracks
        originalQueue = session.tracks
        index = session.index
        current = track
        audio = null
        neteaseStream = null
        val duration = track.durationMs
        pausedAtMs = if (duration > 0L) session.positionMs.coerceIn(0L, max(0L, duration - 1000L)) else session.positionMs.coerceAtLeast(0L)
        message = ""
        state = State.PAUSED
        ResumeStore.adopt(session.tracks)
        return true
    }
    private class PendingFade(var pausing: Boolean, var finish: () -> Unit)
    private var pendingFade: PendingFade? = null
    @Volatile var skipNotice = 0L
        private set

    @Synchronized fun playIndex(i: Int) {
        failedTracks.clear()
        playIndexInternal(i)
    }

    private fun playIndexInternal(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        val track = q[idx]
        index = idx
        if (!fadeOut { loadTrack(track, 0L, false) }) loadTrack(track, 0L, false)
        if (radio != null && idx >= q.size - 2) extendRadio(null)
    }

    @Synchronized fun setNeteaseQuality(quality: NeteaseQuality) {
        if (quality == neteaseQuality) return
        neteaseQuality = quality
        val track = current ?: return
        if (!active || track.source != MusicSource.NETEASE) return
        // 恢复的上次会话还没加载：只记下音质，按播放时再按新音质加载，启动时不提前下载
        if (state == State.PAUSED && audio == null && line == null) return
        if (pendingFade?.pausing == false) return // The selected track will load at the new quality.
        loadTrack(track, positionMs, pendingFade?.pausing == true || state == State.PAUSED || (state == State.LOADING && loadingPaused))
    }

    private fun loadTrack(track: NeteaseTrack, position: Long, paused: Boolean) {
        if (track.source == MusicSource.APPLE) {
            if (!AppleWebPlayer.load(track)) { message = AppleWebPlayer.message; return }
            ++generation
            stopLine()
            audio = null
            neteaseStream = null
            current = track
            state = State.LOADING
            loadingPosition = 0L
            message = "Loading Apple Music song"
            return
        }
        if (current?.source == MusicSource.APPLE) AppleWebPlayer.command("stop")
        val gen = ++generation
        stopLine()
        audio = null
        neteaseStream = null
        current = track
        loadingPosition = position
        loadingPaused = paused
        state = State.LOADING
        message = "加载中…"
        ResumeStore.save(queue, index, position)
        val quality = neteaseQuality
        loader.execute { load(gen, track, quality, position, paused) }
    }

    /** 电台：再要几首接到队列后面（去掉已经在队列里的），然后执行 [then] */
    private fun extendRadio(then: (() -> Unit)?) {
        val fetch = radio ?: return
        radioWorker.execute {
            if (radio !== fetch) return@execute
            // 已经有好几首没放的就不用再要了（连续调用时只要一次）
            if (queue.size - index <= 2) {
                val more = try { fetch() } catch (e: Exception) {
                    DangoLogger.warn("Radio fetch failed: {}", e.javaClass.simpleName)
                    emptyList()
                }
                synchronized(this) {
                    if (radio !== fetch) return@execute
                    val have = queue.mapTo(HashSet()) { it.key }
                    val fresh = more.filter { have.add(it.key) }
                    if (fresh.isNotEmpty()) queue = queue + fresh
                }
            }
            synchronized(this) {
                if (radio === fetch) then?.invoke()
            }
        }
    }

    /**
     * 歌单还在加载时就开始放了：加载出更多后把队列换成更长的版本。
     * 只在队列还是 [from] 本身、并且 [to] 以它为开头时才换，当前这首和播放位置都不变。
     */
    @Synchronized fun growQueue(from: List<NeteaseTrack>, to: List<NeteaseTrack>) {
        if (originalQueue !== from || radio != null || to.size <= from.size) return
        for (i in from.indices) if (from[i].key != to[i].key) return
        queue = if (queue === from) to else queue + to.drop(from.size).shuffled()
        originalQueue = to
    }

    /** 插入歌曲并立即播放，之后继续原队列。 */
    @Synchronized fun playNext(track: NeteaseTrack) {
        val q = queue
        if (q.isEmpty() || index !in q.indices) {
            queue = listOf(track)
            originalQueue = queue
            playIndex(0)
            return
        }
        queue = q.subList(0, index + 1) + track + q.subList(index + 1, q.size)
        originalQueue = originalQueue + track
        playIndex(index + 1)
    }

    @Synchronized fun next() {
        if (MusicSources.selected == MusicSource.APPLE && queue.none { it.source == MusicSource.APPLE }) { AppleWebPlayer.command("next"); return }
        val q = queue
        if (q.isEmpty()) return
        // 电台按顺序往下放；放到最后一首时先要几首新的再切
        if (radio != null) {
            if (index + 1 < q.size) playIndex(index + 1)
            else { val from = index; extendRadio { if (index == from) playIndex(from + 1) } }
            return
        }
        playIndex(index + 1)
    }

    @Synchronized fun prev() {
        if (MusicSources.selected == MusicSource.APPLE) {
            if (queue.none { it.source == MusicSource.APPLE }) AppleWebPlayer.command("previous")
            else if (mode != Mode.SHUFFLE && positionMs > 3000) AppleWebPlayer.command("seek", 0.0)
            else playIndex(index - 1)
            return
        }
        if (mode != Mode.SHUFFLE && pendingFade?.pausing != false && positionMs > 3000) {
            if (!fadeOut { seekLocal(0L, fadeIn = true) }) seekLocal(0L, fadeIn = playbackFades)
            return
        }
        playIndex(index - 1)
    }

    @Synchronized fun togglePause() {
        if (MusicSources.selected == MusicSource.APPLE) {
            AppleWebPlayer.command(if (state == State.PLAYING) "pause" else "play"); return
        }
        when (state) {
            State.PLAYING -> {
                val fading = pendingFade
                if (fading != null) {
                    if (fading.pausing) {
                        pendingFade = null
                        line?.fadeTo(1f)
                    }
                } else if (!fadeOut(pausing = true, finish = ::pauseLine)) {
                    pauseLine()
                }
            }
            State.PAUSED -> {
                val l = line
                if (l != null) {
                    // 声卡输出只是停住了，接着放就行；解码线程看到状态变回 PLAYING 会继续写数据
                    l.fadeTo(1f)
                    l.start()
                    state = State.PLAYING
                } else if (audio == null) {
                    // 恢复的上次会话：还没加载过，从记住的进度开始加载
                    current?.let { loadTrack(it, pausedAtMs, false) }
                } else {
                    state = State.PLAYING
                    seekLocal(pausedAtMs, fadeIn = playbackFades)
                }
            }
            else -> {}
        }
    }

    private fun pauseLine() {
        pausedAtMs = positionMs
        state = State.PAUSED
        val out = line
        if (out != null) {
            out.stop()
            pausedAtMs = seekBaseMs + out.positionMicros / 1000
        }
        if (current?.source != MusicSource.APPLE) ResumeStore.save(queue, index, pausedAtMs)
    }

    private fun fadeOut(pausing: Boolean = false, finish: () -> Unit): Boolean {
        val out = line ?: return false
        if (!playbackFades || state != State.PLAYING) return false
        val pending = pendingFade
        if (pending != null) {
            pending.pausing = pausing
            pending.finish = finish
        } else {
            val request = PendingFade(pausing, finish)
            pendingFade = request
            out.fadeTo(0f)
            checkFade(request, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450L + audioBufferMs.coerceIn(0, 1500)))  // 排队的音频放完淡出才真正开始
        }
        return true
    }

    private fun checkFade(request: PendingFade, deadline: Long) {
        failureTimer.schedule({
            synchronized(this) {
                if (pendingFade !== request) return@synchronized
                if (!playbackFades || System.nanoTime() - deadline >= 0L || line?.let { it.lost || it.fadeOutPlayed } != false) {
                    finishFade()
                } else {
                    checkFade(request, deadline)
                }
            }
        }, 8, TimeUnit.MILLISECONDS)
    }

    private fun finishFade() {
        val request = pendingFade ?: return
        pendingFade = null
        request.finish()
    }

    fun seek(ms: Long) {
        if (current?.source == MusicSource.APPLE) { AppleWebPlayer.command("seek", ms.coerceIn(0L, durationMs).toDouble() / 1000); return }
        if (audio == null) {
            // 恢复后还没加载：只改记住的进度，按播放时从这里开始
            synchronized(this) {
                if (state == State.PAUSED && line == null && current != null) {
                    pausedAtMs = ms.coerceIn(0L, max(0L, durationMs - 500))
                }
            }
            return
        }
        seekLocal(ms)
    }

    @Synchronized private fun seekLocal(ms: Long, fadeIn: Boolean = false) {
        if (pendingFade?.pausing == false) return
        val bytes = audio ?: run {
            if (state == State.PAUSED && line == null && current != null) pausedAtMs = ms.coerceIn(0L, max(0L, durationMs - 500))
            return
        }
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (pendingFade?.pausing == true) state = State.PAUSED
        if (state == State.PAUSED) pausedAtMs = target
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target, fadeIn)
    }

    @Synchronized fun stop() = stop(clearResume = true)

    /** [clearResume] = false：不是用户主动停止（比如切换音源），保留续播记录 */
    @Synchronized fun stop(clearResume: Boolean) {
        if (MusicSources.selected == MusicSource.APPLE || current?.source == MusicSource.APPLE) AppleWebPlayer.command("stop")
        heartMode = false
        radio = null
        ++generation
        stopLine()
        audio = null
        neteaseStream = null
        current = null
        state = State.STOPPED
        message = ""
        prefetchKey = null
        prepared = null
        if (clearResume && !shuttingDown) ResumeStore.clear()
    }

    @Synchronized fun shutdown() {
        shuttingDown = true
        try {
            val track = current
            if (track != null && track.source != MusicSource.APPLE && state != State.STOPPED && state != State.ERROR) {
                ResumeStore.save(queue, index, positionMs, sync = true)
            }
        } catch (_: Throwable) {
        }
        AppleWebPlayer.disconnect()
        stop()
        loader.shutdownNow()
        decoderWorker.shutdownNow()
        radioWorker.shutdownNow()
        prefetcher.shutdownNow()
        failureTimer.shutdownNow()
    }

    @Volatile private var applePosition = 0L

    @Synchronized fun acceptAppleState(track: NeteaseTrack, position: Long, playing: Boolean) {
        if (MusicSources.selected != MusicSource.APPLE) return
        if (current?.source != MusicSource.APPLE) {
            ++generation
            stopLine()
            audio = null
            neteaseStream = null
        }
        if (queue.none { it.key == track.key }) { queue = listOf(track); originalQueue = queue }
        if (queue.getOrNull(index)?.key != track.key) index = queue.indexOfFirst { it.key == track.key }
        current = track
        applePosition = position
        pausedAtMs = position
        state = if (playing) State.PLAYING else State.PAUSED
        message = AppleWebPlayer.message
    }

    @Synchronized fun finishAppleTrack() {
        if (current?.source != MusicSource.APPLE || queue.isEmpty()) return
        playIndex(if (repeatOne) index else index + 1)
    }

    @Synchronized fun clearAppleState() {
        if (current?.source != MusicSource.APPLE) return
        current = null
        state = State.STOPPED
        message = AppleWebPlayer.message
    }

    // ---------------------------------------------------------------- 加载 / 解码

    private fun load(gen: Int, track: NeteaseTrack, quality: NeteaseQuality, position: Long, paused: Boolean) {
        if (gen != generation) return
        try {
            val ready = if (track.source == MusicSource.NETEASE) takePrepared(track, quality) else null
            // 这首正在预取但还没好：停掉预取，免得两份下载抢带宽
            if (ready == null && prefetchKey == track.key) prefetchKey = null
            val kugou = if (track.source == MusicSource.KUGOU) KugouMusicSource.loadAudio(track) { gen == generation } else null
            val url = ready?.url ?: when (track.source) {
                MusicSource.KUGOU -> kugou?.info
                MusicSource.NETEASE -> NeteaseApi.songUrl(track, quality) { gen == generation }
                else -> MusicSources.api(track.source).songUrl(track)
            }
            if (gen != generation) return
            if (url == null) return fail(gen, if (track.source == MusicSource.NETEASE) "拿不到播放地址"
                else if (NativeAccounts.get(track.source) != null) "No playable MP3. Your login may have expired, or this song is restricted by membership or region."
                else "No guest MP3 available. Login, payment or regional restrictions may apply.")
            if (track.source == MusicSource.NETEASE && ready == null) message = "正在下载网易云音源…"
            val bytes = ready?.bytes ?: kugou?.bytes ?: if (track.source == MusicSource.NETEASE) {
                NeteaseApi.requestAudio(url.url, { gen == generation }) { percent ->
                    if (gen == generation) message = "正在下载网易云音源 $percent%"
                }
            }
                else SourceHttp.bytes(url.url, if (track.source == MusicSource.QQ) "https://y.qq.com/" else "https://m.kugou.com/")
            if (gen != generation) return
            if (bytes == null || bytes.isEmpty() || (!track.cloud && bytes.size < 10_000)) {
                return fail(gen, if (track.source != MusicSource.NETEASE) "Unable to download this guest MP3. Try another song."
                    else "网易云未提供有效的音频数据。")
            }
            // Cloud list duration units are not reliable. Use compressed audio timing before enabling seeking.
            val measuredDuration = ready?.measuredDuration
                ?: if (track.cloud) AudioAnalysis.duration(bytes) { gen == generation } else 0L
            val measuredGain = if (ready != null) ready.measuredGain
                else if (normalize) AudioAnalysis.gain(bytes) { gen == generation } else null
            val stream = if (track.source == MusicSource.NETEASE) neteaseStreamOf(url, bytes) else null
            synchronized(this) {
                if (gen != generation) return
                if (measuredDuration > 0) track.durationMs = measuredDuration
                audio = bytes
                neteaseStream = stream
                message = when {
                    url.trialOnly -> "Preview only: membership is needed for the full song"
                    url.replaced -> "The original was unavailable, playing another version"
                    else -> ""
                }
                trackGain = if (normalize) capByPeak(measuredGain, url) ?: loudnessGain(url) else 1f
                if (paused) {
                    pausedAtMs = position
                    state = State.PAUSED
                }
                startDecode(gen, bytes, position, fadeIn = position == 0L)
                schedulePrefetch()
            }
            DangoLogger.info("Audio analysis: source={} cloud={} durationMs={} localGain={} appliedGain={} prefetched={}",
                track.source.key, track.cloud, measuredDuration, measuredGain != null, trackGain, ready != null)
        } catch (e: Throwable) {
            if (gen != generation) return
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            if (track.source == MusicSource.NETEASE) {
                DangoLogger.warn("Music load failed: {}", e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "加载失败：" + describe(e))
            } else {
                DangoLogger.warn("Source {} load failed: {}", track.source.key, e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "Playback request failed. Check your connection or log in again.")
            }
        }
    }

    private fun takePrepared(track: NeteaseTrack, quality: NeteaseQuality): Prepared? {
        val p = prepared ?: return null
        if (System.currentTimeMillis() - p.atMs >= 30 * 60_000L) {
            prepared = null
            return null
        }
        // 网易云的地址会过期，但音频已经在内存里；只认同一首、同一音质、同一响度设置。
        // 点了别的歌时留着它：之后切回原来的下一首还能用
        if (p.key != track.key || p.quality != quality || p.normalized != normalize) return null
        prepared = null
        return p
    }

    /** 队列里接下来要放的那首（单曲循环、电台还没要到下一首时没有） */
    private fun upcomingTrack(): NeteaseTrack? {
        if (!heartMode && repeatOne) return null
        val q = queue
        val i = index
        if (q.size < 2 || i !in q.indices) return null
        if (radio != null && i + 1 >= q.size) return null
        return q[(i + 1) % q.size]
    }

    /**
     * 当前这首开始解码后，后台低优先级下载下一首（整首 + 响度分析），切过去时不用再等。
     * 只缓存一首；队列、音质或响度设置变了就作废重下。
     */
    private fun schedulePrefetch() {
        val next = upcomingTrack() ?: return
        if (next.source != MusicSource.NETEASE) return
        val quality = neteaseQuality
        val norm = normalize
        val key = next.key
        val held = prepared
        if (held != null && held.key == key && held.quality == quality && held.normalized == norm) return
        if (held != null) prepared = null  // 不再是下一首了，释放内存
        if (prefetchKey == key) return
        prefetchKey = key
        prefetcher.execute {
            val alive = { prefetchKey == key }
            try {
                // 让当前这首先稳定播放几秒，再开始占带宽
                Thread.sleep(5_000L)
                if (!alive()) return@execute
                val url = NeteaseApi.songUrl(next, quality, alive) ?: return@execute
                val bytes = NeteaseApi.requestAudio(url.url, alive, PREFETCH_TIMEOUT_SECONDS) { } ?: return@execute
                if (!alive() || bytes.isEmpty() || (!next.cloud && bytes.size < 10_000)) return@execute
                val duration = if (next.cloud) AudioAnalysis.duration(bytes, alive) else 0L
                val gain = if (norm) AudioAnalysis.gain(bytes, alive) else null
                synchronized(this) {
                    if (!alive()) return@execute
                    prepared = Prepared(key, quality, norm, url, bytes, duration, gain, System.currentTimeMillis())
                }
                DangoLogger.info("Prefetched next track ({} KB)", bytes.size / 1024)
            } catch (e: Throwable) {
                if (alive()) DangoLogger.info("Prefetch skipped: {}", e.javaClass.simpleName)
            } finally {
                if (prefetchKey == key) prefetchKey = null
            }
        }
    }

    private fun neteaseStreamOf(url: NeteaseApi.SongUrl, bytes: ByteArray): NeteaseStream {
        val reported = url.neteaseStream ?: NeteaseStream(null, 0L, "")
        val format = when (Mp3Probe.kind(bytes)) {
            "flac" -> "flac"
            "ogg-vorbis" -> "vorbis"
            "id3", "mpeg-like" -> "mp3"
            else -> reported.format
        }
        // If the file disagrees with the response, don't attach the response's quality label.
        if (reported.format.isNotEmpty() && format != reported.format && !(format == "vorbis" && reported.format == "ogg")) {
            return NeteaseStream(null, 0L, format)
        }
        if (reported.quality?.let { it >= NeteaseQuality.LOSSLESS } == true && format in setOf("mp3", "vorbis")) {
            return reported.copy(quality = null, format = format)
        }
        return reported.copy(format = format)
    }

    /** 失败跳过独立计时，不占用加载线程；旧请求不能切掉用户新选的歌。 */
    @Synchronized private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        if (pendingFade?.pausing == false) {
            finishFade()
            return
        }
        pendingFade = null
        state = State.ERROR
        neteaseStream = null
        message = msg
        current?.let { failedTracks.add(it.key) }
        if (failedTracks.size >= 10 || queue.none { it.key !in failedTracks }) {
            message = msg + " · " + "Auto-skip stopped after repeated playback failures."
            return
        }
        failureTimer.schedule({
            synchronized(this) {
                if (gen == generation && state == State.ERROR && failedTracks.size < 10) {
                    val candidates = queue.indices.filter { queue[it].key !in failedTracks }
                    if (candidates.isNotEmpty()) {
                        val next = candidates.firstOrNull { it > index } ?: candidates.first()
                        skipNotice = System.nanoTime()
                        playIndexInternal(next)
                    }
                }
            }
        }, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun describe(e: Throwable): String = e.javaClass.simpleName

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    /** 测试用：-Ddangomusic.openal=true 时电脑上也用游戏的 OpenAL 放歌 */
    private val forceOpenAl = java.lang.Boolean.getBoolean("dangomusicgtnh.openal")
    private var loggedOutput = ""

    /** 这次该先试游戏的 OpenAL 吗 */
    private fun preferGameAudio(): Boolean = when (outputMode) {
        OUTPUT_JAVA_SOUND -> false
        OUTPUT_GAME -> true
        // 安卓上 Java Sound 是模拟出来的，放歌会掉帧；电脑上 Java Sound 不占游戏的声音通道，更稳
        else -> forceOpenAl || AndroidRuntime.detected || !AudioOutput.javaSoundNative
    }

    /**
     * 打开声音输出：按 [outputMode] 先试游戏的 OpenAL 或 Java Sound，打不开再换另一个。
     */
    /** 声卡缓冲：排队上限再留 0.3 秒余量，至少 0.25 秒（以前固定 0.25 秒） */
    internal fun lineBufferBytes(rate: Int, channels: Int): Int {
        val ms = (audioBufferMs.coerceIn(100, 1500) + 300).coerceAtLeast(250)
        return (rate.toLong() * channels * 2 * ms / 1000).toInt() / (channels * 2) * (channels * 2)
    }

    private fun openOutput(format: AudioFormat, bufferBytes: Int): AudioOutput {
        if (preferGameAudio()) platformOutput(format)?.let { return it }
        return try {
            JavaSoundOutput(openLine(format, bufferBytes)).also { logOutput("Java Sound") }
        } catch (e: Throwable) {
            platformOutput(format) ?: throw e
        }
    }

    /**
     * 声音系统重启后重新打开输出。游戏要一会儿才能把声音系统开回来，最多等几秒；
     * 原来用的是游戏的 OpenAL 就继续等它，等不到再按平常的顺序打开。
     */
    private fun reopenOutput(gen: Int, old: AudioOutput, rate: Int, channels: Int, sourceBits: Int?, startMs: Long): FadingAudioOutput? {
        DangoLogger.info("Dango Music: game audio restarted, reopening output")
        try { old.close() } catch (_: Exception) {}
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        var out: AudioOutput? = null
        val deadline = System.currentTimeMillis() + 5000L
        while (out == null && gen == generation && System.currentTimeMillis() < deadline) {
            out = platformOutput(format)
            if (out == null) Thread.sleep(100)
        }
        if (gen != generation) {
            try { out?.close() } catch (_: Exception) {}
            return null
        }
        val opened = out ?: openOutput(format, lineBufferBytes(rate, channels))
        synchronized(this) {
            if (gen != generation) {
                opened.close()
                return null
            }
            val fading = fadingOutput(opened, gen, rate, channels, state == State.PAUSED)
            if (pendingFade != null) fading.fadeTo(0f)
            seekBaseMs = startMs
            line = fading
            outputInfo = OutputInfo(rate, format.sampleSizeInBits, sourceBits, opened is JavaSoundOutput)
            if (state == State.PAUSED) fading.stop() else fading.start()
            return fading
        }
    }

    private fun platformOutput(format: AudioFormat): AudioOutput? = try {
        me.sorihiko.dangomusicgtnh.DangoMusic.instance.platform.openAudioOutput(format.sampleRate.toInt(), format.channels)
            ?.also { logOutput("game OpenAL") }
    } catch (e: Throwable) {
        DangoLogger.warn("Game audio output failed: {}", e.toString())
        null
    }

    private fun logOutput(name: String) {
        if (loggedOutput == name) return
        loggedOutput = name
        DangoLogger.info("Dango Music: playing through {}", name)
    }

    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        try {
            val l = AudioSystem.getSourceDataLine(format)
            try {
                l.open(format, bufferBytes)
            } catch (e: Exception) {
                l.open(format)
            }
            return l
        } catch (e: Throwable) {
            last = e
            DangoLogger.warn("Default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mi in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mi)
                if (!mixer.isLineSupported(info)) continue
                val l = mixer.getLine(info) as SourceDataLine
                l.open(format)
                DangoLogger.info("Using audio device: {}", mi.name)
                return l
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }

    /** 音量均衡（设置里开关）：按音源给的响度把每首歌调到差不多响 */
    @Volatile var normalize = true
    @Volatile var originalAudio = false

    data class OutputInfo(val rate: Int, val bits: Int, val sourceBits: Int?, val javaSound: Boolean)

    @Volatile var outputInfo: OutputInfo? = null
        private set
    /** 这首歌要乘的倍数（1 = 原样） */
    @Volatile private var trackGain = 1f

    /**
     * 本地只分析了开头，峰值可能比整首歌的小（安静前奏 + 很响的副歌）。音源给了整首歌的峰值时，
     * 再按它压一次，保证放大后副歌也不会超过上限
     */
    private fun capByPeak(measured: Float?, url: NeteaseApi.SongUrl): Float? =
        measured?.let { if (url.peak > 0f) minOf(it, 0.98f / url.peak) else it }

    private fun loudnessGain(url: NeteaseApi.SongUrl): Float {
        if (!normalize || url.gainDb == 0f) return 1f
        var gain = Math.pow(10.0, url.gainDb / 20.0).toFloat()
        // 有峰值就别把峰值推过 1（会破音）
        if (url.peak > 0f) gain = minOf(gain, 0.98f / url.peak)
        return gain.coerceIn(0.25f, 2f)
    }

    private fun fadingOutput(output: AudioOutput, gen: Int, rate: Int, channels: Int, fadeIn: Boolean) =
        FadingAudioOutput(output, rate, channels, fadeIn, { gen == generation }, { state == State.PAUSED }, { playbackFades }, { audioBufferMs })

    @Synchronized private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long, fadeIn: Boolean) {
        if (gen != generation) return
        decoderWorker.execute {
            if (gen == generation) decodeLoop(gen, bytes, startMs, fadeIn)
        }
    }

    private fun decodeLoop(gen: Int, bytes: ByteArray, startMs: Long, fadeIn: Boolean) {
        var out: FadingAudioOutput? = null
        var source: AudioDecoder? = null
        try {
            val started = AudioDecoder.start(bytes, startMs) { gen == generation }
            val decoder = started.decoder
            source = decoder
            val skipped = started.skippedMs
            val first = started.first
            if (gen != generation) return
            clearBeats()
            EnvironmentAudio.reset()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f
            // 音量均衡和环境音效用浮点算，最后统一软限幅再变回 16 位：中间不会被截断
            val limiter = SoftLimiter()
            var mix = FloatArray(0)

            var appliedVolume = -1f
            var pending = first
            while (gen == generation) {
                val frame = pending ?: decoder.read()
                pending = null
                if (frame == null) {
                    val lastOutput = out
                    if (lastOutput == null) fail(gen, "No decodable audio was found.") else finishTrack(gen, lastOutput)
                    break
                }
                val output = out ?: run {
                    val format = AudioFormat(frame.rate.toFloat(), 16, frame.channels, true, false)
                    val opened = openOutput(format, lineBufferBytes(frame.rate, frame.channels))
                    synchronized(this) {
                        if (gen != generation) {
                            opened.close()
                            return
                        }
                        val fading = fadingOutput(opened, gen, frame.rate, frame.channels, fadeIn || state == State.PAUSED)
                        out = fading
                        seekBaseMs = skipped.toLong()
                        line = fading
                        outputInfo = OutputInfo(frame.rate, format.sampleSizeInBits, frame.sourceBits, opened is JavaSoundOutput)
                        if (state == State.PAUSED) {
                            pausedAtMs = seekBaseMs
                            fading.stop()
                        } else {
                            fading.start()
                            state = State.PLAYING
                        }
                        fading
                    }
                }
                // 暂停：不再往声卡写数据，原地等待。
                // （Java Sound 在输出停止时被写数据会自动重新开始或直接返回，
                //   不这样等的话会一口气把整首歌"读完"，然后误以为放完了跳下一首）
                while (state == State.PAUSED && gen == generation) {
                    synchronized(this) {
                        if (state == State.PAUSED && line === output && output.isRunning) output.stop()
                    }
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    output.setVolume(volume)
                    appliedVolume = volume
                }
                val len = frame.length
                val pcm = frame.samples

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = frame.durationMs
                val ch = frame.channels.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < len) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (len / ch).coerceAtLeast(1)
                if (avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                // 音量均衡、环境音效（水下变闷、洞穴回声……）在鼓点检测之后加，不影响节奏判断
                val raw = originalAudio
                if (!raw) {
                    val gain = if (normalize) trackGain else 1f
                    if (mix.size < len) mix = FloatArray(len)
                    for (idx in 0 until len) mix[idx] = pcm[idx] * gain
                    EnvironmentAudio.process(mix, len, ch, frame.rate, frameMs)
                    limiter.process(mix, len, ch, frame.rate, pcm)
                }
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                try {
                    output.write(data, 0, data.size)
                } catch (e: Throwable) {
                    if (!output.lost || gen != generation) throw e
                    // 游戏重启了声音系统：换一个新的输出，从这一块接着放（缓冲里没放完的零点几秒丢掉）
                    val reopened = reopenOutput(gen, output, frame.rate, frame.channels, frame.sourceBits, (decodedMs - frameMs).toLong()) ?: throw e
                    out = reopened
                    if (reopened.lost) throw e
                    reopened.setVolume(volume)
                    appliedVolume = volume
                    reopened.write(data, 0, data.size)
                }
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                fail(gen, "播放失败：" + describe(e))
            }
        } finally {
            try { source?.close() } catch (_: Exception) {}
            synchronized(this) {
                if (line === out) {
                    line = null
                    outputInfo = null
                }
            }
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun finishTrack(gen: Int, out: FadingAudioOutput) {
        while (gen == generation) {
            // 最后一点正在放的时候声音系统重启了：就当放完了
            try { out.drain() } catch (e: Throwable) { if (!out.lost) throw e }
            synchronized(this) {
                if (gen != generation) return
                failedTracks.clear()
                finishFade()
                if (gen != generation) return
                if (state != State.PAUSED) {
                    seekBaseMs = positionMs
                    line = null
                    outputInfo = null
                    if (!heartMode && repeatOne) {
                        // 复用已下载的音频，从头解码，不重新请求播放地址或下载。
                        seekLocal(0L, fadeIn = true)
                    } else {
                        next()
                    }
                    return
                }
            }
            while (state == State.PAUSED && gen == generation) Thread.sleep(15)
        }
    }

    private fun stopLine() {
        pendingFade = null
        val l = line
        line = null
        outputInfo = null
        try {
            l?.stop()
            l?.flush()
            l?.close()
        } catch (_: Exception) {
        }
    }

    const val OUTPUT_AUTO = 0
    const val OUTPUT_JAVA_SOUND = 1
    const val OUTPUT_GAME = 2

}

/** 预取不赶时间：慢网络下也给足时间把整首下完 */
private const val PREFETCH_TIMEOUT_SECONDS = 600L
