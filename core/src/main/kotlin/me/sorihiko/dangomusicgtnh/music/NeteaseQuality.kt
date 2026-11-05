package me.sorihiko.dangomusicgtnh.music

/** The order is used for fallback and persisted in the client configuration. */
enum class NeteaseQuality(val id: String, val label: String) {
    STANDARD("standard", "Standard"),
    HIGHER("higher", "Higher"),
    EXHIGH("exhigh", "High quality"),
    LOSSLESS("lossless", "Lossless"),
    HIRES("hires", "Hi-Res"),
    MASTER("jymaster", "Master"),
    ;

    internal val legacyBitrate get() = when (this) {
        STANDARD -> 128_000
        HIGHER -> 192_000
        else -> 320_000
    }
}

data class NeteaseStream(val quality: NeteaseQuality?, val bitrate: Long, val format: String)
