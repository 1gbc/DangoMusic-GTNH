package me.sorihiko.dangomusicgtnh

import me.sorihiko.dangomusicgtnh.config.ConfigManager
import me.sorihiko.dangomusicgtnh.event.Event
import me.sorihiko.dangomusicgtnh.event.EventManager
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleManager
import me.sorihiko.dangomusicgtnh.platform.Platform
import me.sorihiko.dangomusicgtnh.music.SessionFiles

import java.nio.file.Path

/**
 * Dango Music 的核心：事件系统 + 模块 + 设置。所有 Minecraft 版本共用这一份。
 * 和游戏打交道的地方都通过 [Platform]，由各版本的平台层在启动时传进来。
 */
class DangoMusic private constructor(val platform: Platform) {
    companion object {
        const val MOD_ID = "dangomusicgtnh"
        const val NAME = "DangoMusic GTNH Edition"

        lateinit var instance: DangoMusic
            private set

        /** 平台层在 mod 初始化时调用：注册模块、读取设置 */
        @JvmStatic
        fun start(platform: Platform, modules: List<Module>) {
            instance = DangoMusic(platform)
            instance.init(modules)
        }

        @JvmStatic
        fun callEvent(event: Event) {
            if (::instance.isInitialized) instance.eventManager.callEvent(event)
        }
    }

    /** 实例配置和缓存保留在游戏目录，账号资料使用系统用户目录。 */
    val clientDir: Path = platform.configDir
    val accountsDir: Path by lazy { SessionFiles.accountDirectory(clientDir) }

    val eventManager = EventManager()
    val moduleManager = ModuleManager(eventManager)
    val configManager = ConfigManager(moduleManager, clientDir.resolve("settings.json"))

    private fun init(modules: List<Module>) {
        modules.forEach { moduleManager.register(it) }
        moduleManager.load()

        configManager.load()
        eventManager.addListener(configManager)
        configManager.installShutdownHook()
    }
}
